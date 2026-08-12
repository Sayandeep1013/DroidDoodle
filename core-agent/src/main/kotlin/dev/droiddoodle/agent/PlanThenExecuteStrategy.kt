package dev.droiddoodle.agent

import dev.droiddoodle.grammar.GrammarBuilder
import dev.droiddoodle.grammar.GrammarSpec
import dev.droiddoodle.grammar.PlanEnvelopeChecker
import dev.droiddoodle.grammar.PlanStep
import dev.droiddoodle.inference.SamplingParams
import dev.droiddoodle.model.CellDelta
import dev.droiddoodle.model.DeltaKind
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingKeys
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.ToolCatalog
import dev.droiddoodle.world.Board
import dev.droiddoodle.world.BoardOps
import kotlinx.serialization.json.JsonPrimitive

public interface LoopStrategy {
    public val id: String
    public suspend fun run(request: TurnRequest, deps: TurnDeps): TurnResult
}

/**
 * Decision D4. One constrained generation produces an entire ordered plan; the
 * runtime validates and executes it, halting on the first failure.
 *
 * The ceiling of three inferences per user message -- initial, one retrieval
 * re-plan, one repair -- is a hard property of this strategy rather than an
 * emergent behaviour, which is what makes worst-case latency predictable.
 *
 * See docs/23-agent-runtime.md.
 */
public class PlanThenExecuteStrategy : LoopStrategy {

    override val id: String = "plan_then_execute"

    override suspend fun run(request: TurnRequest, deps: TurnDeps): TurnResult {
        val started = deps.clock.nowMillis()
        val turnId = deps.turnIds.next()
        val settings = request.settings
        val maxSteps = settings.int(SettingKeys.AGENT_MAX_STEPS)
        val maxOutput = settings.int(SettingKeys.MODEL_MAX_TOKENS)
        val contextBudget = (settings.int(SettingKeys.MODEL_CONTEXT_TOKENS) - maxOutput)
            .coerceAtLeast(256)

        val assembler = ContextAssembler(deps.registry.schemas) { deps.engine.tokenCount(it) }
        val rounds = mutableListOf<InferenceRound>()

        var assembleMillis = 0L
        var grammarMillis = 0L
        var inferenceMillis = 0L

        var extraBlock: String? = null
        var role = RoundRole.INITIAL
        var retrievalUsed = false
        var repairUsed = false
        var board = request.board

        while (true) {
            // -- phase 1: assemble ----------------------------------------
            val t0 = deps.clock.nowMillis()
            val assembled = when (
                val a = assembler.assemble(
                    request.copy(board = board),
                    contextBudget,
                    extraBlock,
                )
            ) {
                is Res.Ok -> a.value
                is Res.Err -> return reject(
                    turnId, started, deps, request, board, rounds,
                    ValidationOutcome.failed(a.error),
                    FailureInfo(null, "CONTEXT_OVERFLOW", a.error),
                    Timings(deps.clock.nowMillis() - started, assembleMillis, grammarMillis, inferenceMillis, 0),
                )
            }
            assembleMillis += deps.clock.nowMillis() - t0

            // -- phase 2: grammar ------------------------------------------
            val t1 = deps.clock.nowMillis()
            val spec = GrammarSpec(
                tools = deps.registry.schemas,
                existingIds = board.nodes.keys.sortedBy { it.value.drop(1).toInt() },
                maxSteps = maxSteps,
                agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
            )
            val grammar = GrammarBuilder.build(spec)
            grammarMillis += deps.clock.nowMillis() - t1

            // -- phase 3: generate -----------------------------------------
            val t2 = deps.clock.nowMillis()
            val generation = deps.engine.generate(
                prompt = assembled.text,
                grammar = grammar,
                params = SamplingParams(
                    temperature = settings.float(SettingKeys.MODEL_TEMPERATURE),
                    topP = settings.float(SettingKeys.MODEL_TOP_P),
                    maxTokens = maxOutput,
                ),
            )
            inferenceMillis += deps.clock.nowMillis() - t2

            rounds += InferenceRound(
                role = role,
                prompt = assembled.text,
                promptTokens = generation.promptTokens,
                blockTokens = assembled.blockTokens,
                shedBlocks = assembled.shedBlocks,
                grammarHash = grammarHash(grammar),
                rawOutput = generation.text,
                outputTokens = generation.outputTokens,
                prefillMillis = generation.prefillMillis,
                decodeMillis = generation.decodeMillis,
                cachedPrefixTokens = generation.cachedPrefixTokens,
                stopReason = generation.stopReason,
            )

            // -- phase 4: parse ---------------------------------------------
            // Expected to be infallible: the grammar makes malformed output
            // unrepresentable. A failure here is a GRAMMAR DEFECT, not a model
            // defect, and there is deliberately no regex extraction, no JSON
            // repair and no retry -- those hide exactly the defect we want
            // reported.
            val envelope = when (val parsed = PlanEnvelopeChecker(spec).check(generation.text)) {
                is Res.Ok -> parsed.value
                is Res.Err -> return reject(
                    turnId, started, deps, request, board, rounds,
                    ValidationOutcome.failed(parsed.error),
                    FailureInfo(null, "GRAMMAR_VIOLATION", parsed.error),
                    Timings(deps.clock.nowMillis() - started, assembleMillis, grammarMillis, inferenceMillis, 0),
                )
            }

            // -- phase 5: retrieval ------------------------------------------
            val firstStep = envelope.steps.first()
            if (firstStep.tool == ToolCatalog.FIND) {
                if (retrievalUsed) {
                    val message = "find may only be used once per turn"
                    return reject(
                        turnId, started, deps, request, board, rounds,
                        ValidationOutcome.failed(message),
                        FailureInfo(1, "RETRIEVAL_EXHAUSTED", message),
                        Timings(deps.clock.nowMillis() - started, assembleMillis, grammarMillis, inferenceMillis, 0),
                    )
                }
                val ctx = ExecContext(board, settings, emptyMap(), 1)
                val hits = (deps.registry.execute(firstStep, ctx) as? Res.Ok)
                    ?.value?.findResults.orEmpty()
                extraBlock = renderFound(board, hits)
                retrievalUsed = true
                role = RoundRole.RETRIEVAL_REPLAN
                continue
            }

            // -- phase 6: static validation -----------------------------------
            StaticValidator.validate(envelope.steps, maxSteps)?.let { problem ->
                // Static failures cannot become true later, so partially
                // applying a plan already known to be broken only creates mess.
                return reject(
                    turnId, started, deps, request, board, rounds,
                    ValidationOutcome.failed(problem),
                    FailureInfo(null, "STATIC_VALIDATION", problem),
                    Timings(deps.clock.nowMillis() - started, assembleMillis, grammarMillis, inferenceMillis, 0),
                )
            }

            // -- phase 7: confirmation ----------------------------------------
            val doomed = ConfirmationGate.affectedNodes(board, envelope.steps)
            val needsConfirmation = ConfirmationGate.required(board, envelope.steps, settings)
            if (needsConfirmation && !request.confirmationGranted) {
                return TurnResult(
                    outcome = Outcome.AWAITING_CONFIRMATION,
                    board = board,
                    refs = request.refs,
                    executed = emptyList(),
                    diff = emptyList(),
                    summary = "waiting for confirmation to delete ${doomed.size} node(s)",
                    pendingConfirmation = doomed.toList(),
                    trace = TraceRecord(
                        turnId, started, id, deps.engine.modelId, settings.values,
                        request.userMessage, rounds, envelope.steps.map { it.tool },
                        ValidationOutcome.PASSED,
                        ConfirmationOutcome(true, false, doomed.map { it.value }),
                        emptyList(), emptyList(), Outcome.AWAITING_CONFIRMATION,
                        Timings(deps.clock.nowMillis() - started, assembleMillis, grammarMillis, inferenceMillis, 0),
                    ),
                )
            }

            // -- phase 8: execute ----------------------------------------------
            val t3 = deps.clock.nowMillis()
            val execution = execute(envelope.steps, board, settings, deps)
            val executeMillis = deps.clock.nowMillis() - t3

            // -- phase 3b: optional repair --------------------------------------
            if (execution.failure != null &&
                settings.bool(SettingKeys.AGENT_AUTO_REPAIR) &&
                !repairUsed
            ) {
                repairUsed = true
                role = RoundRole.REPAIR
                board = execution.board
                extraBlock = "last attempt failed at step ${execution.failure.stepIndex}: " +
                    execution.failure.message
                continue
            }

            // -- phases 9 and 10: commit and trace -------------------------------
            val outcome = when {
                execution.failure == null -> Outcome.OK
                execution.diff.isNotEmpty() -> Outcome.PARTIAL
                else -> Outcome.REJECTED
            }
            val timings = Timings(
                totalMillis = deps.clock.nowMillis() - started,
                assembleMillis = assembleMillis,
                grammarMillis = grammarMillis,
                inferenceMillis = inferenceMillis,
                executeMillis = executeMillis,
            )
            return TurnResult(
                outcome = outcome,
                board = execution.board,
                refs = request.refs.updatedBy(execution.diff, execution.board),
                executed = execution.steps,
                diff = execution.diff,
                failure = execution.failure,
                summary = Summaries.fromDiff(execution.diff, execution.failure),
                respondText = execution.respondText,
                settingWrites = execution.settingWrites,
                trace = TraceRecord(
                    turnId, started, id, deps.engine.modelId, settings.values,
                    request.userMessage, rounds, envelope.steps.map { it.tool },
                    ValidationOutcome.PASSED,
                    if (needsConfirmation) {
                        ConfirmationOutcome(true, true, doomed.map { it.value })
                    } else {
                        null
                    },
                    execution.steps,
                    execution.diff.map { it.summary },
                    outcome,
                    timings,
                ),
            )
        }
    }

    // ---- execution -----------------------------------------------------

    private data class Execution(
        val board: Board,
        val steps: List<StepOutcome>,
        val diff: List<CellDelta>,
        val failure: FailureInfo?,
        val respondText: String?,
        val settingWrites: List<Pair<String, String>>,
    )

    private fun execute(
        steps: List<PlanStep>,
        startBoard: Board,
        settings: dev.droiddoodle.model.SettingsSnapshot,
        deps: TurnDeps,
    ): Execution {
        var board = startBoard
        val created = mutableMapOf<Int, NodeId>()
        val outcomes = mutableListOf<StepOutcome>()
        val diff = mutableListOf<CellDelta>()
        val settingWrites = mutableListOf<Pair<String, String>>()
        var respondText: String? = null
        var failure: FailureInfo? = null

        for ((zeroBased, step) in steps.withIndex()) {
            val index = zeroBased + 1
            if (failure != null) {
                outcomes += StepOutcome(index, step.tool, step.args.toString(), result = StepResult.SKIPPED)
                continue
            }
            val t = deps.clock.nowMillis()
            val ctx = ExecContext(board, settings, created, index)
            when (val result = deps.registry.execute(step, ctx)) {
                is Res.Ok -> {
                    val effect = result.value
                    board = effect.board
                    diff += effect.diff
                    effect.createdNode?.let { created[index] = it }
                    effect.respondText?.let { respondText = it }
                    effect.settingWrite?.let { settingWrites += it }
                    outcomes += StepOutcome(
                        index, step.tool, step.args.toString(), effect.resolvedRefs,
                        StepResult.OK, null, deps.clock.nowMillis() - t,
                    )
                }

                is Res.Err -> {
                    // Halt and keep what already succeeded. Watching a village
                    // appear and a smith fail to place is friendlier and more
                    // instructive than watching nothing happen.
                    failure = FailureInfo(index, result.error.code.name, result.error.message)
                    outcomes += StepOutcome(
                        index, step.tool, step.args.toString(), emptyMap(),
                        StepResult.FAILED, result.error.message, deps.clock.nowMillis() - t,
                    )
                }
            }
        }
        return Execution(board, outcomes, diff, failure, respondText, settingWrites)
    }

    // ---- helpers -------------------------------------------------------

    private fun renderFound(board: Board, hits: List<NodeId>): String {
        if (hits.isEmpty()) return "found: nothing matched"
        return "found: " + hits.joinToString(", ") { id ->
            val node = board.node(id)
            if (node == null) id.value else "${id.value} \"${node.label}\""
        }
    }

    private fun grammarHash(grammar: String): String =
        "#" + Integer.toHexString(grammar.hashCode()).takeLast(4)

    private fun reject(
        turnId: String,
        started: Long,
        deps: TurnDeps,
        request: TurnRequest,
        board: Board,
        rounds: List<InferenceRound>,
        validation: ValidationOutcome,
        failure: FailureInfo,
        timings: Timings,
    ): TurnResult = TurnResult(
        outcome = Outcome.REJECTED,
        board = board,
        refs = request.refs,
        executed = emptyList(),
        diff = emptyList(),
        failure = failure,
        summary = "nothing changed: ${failure.message}",
        trace = TraceRecord(
            turnId = turnId,
            startedAtMillis = started,
            strategyId = id,
            modelId = deps.engine.modelId,
            settingsSnapshot = request.settings.values,
            userMessage = request.userMessage,
            rounds = rounds,
            plan = emptyList(),
            validation = validation,
            confirmation = null,
            steps = emptyList(),
            diff = emptyList(),
            outcome = Outcome.REJECTED,
            timings = timings,
        ),
    )
}

/** Checks that cannot become true later, applied across the whole plan. */
internal object StaticValidator {
    fun validate(steps: List<PlanStep>, maxSteps: Int): String? {
        if (steps.size > maxSteps) {
            return "plan has ${steps.size} steps; the limit is $maxSteps"
        }
        for ((zeroBased, step) in steps.withIndex()) {
            val index = zeroBased + 1
            for ((name, value) in step.args) {
                val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val ref = NodeRef.parseOrNull(raw) as? NodeRef.Step ?: continue
                if (ref.step >= index) {
                    return "step $index argument '$name' refers to \$${ref.step}, " +
                        "which has not run yet"
                }
                val producer = steps[ref.step - 1]
                if (producer.tool != ToolCatalog.CREATE_NODE) {
                    return "step $index refers to \$${ref.step}, but that step is " +
                        "${producer.tool} and creates no node"
                }
            }
        }
        return null
    }
}

internal object ConfirmationGate {
    fun affectedNodes(board: Board, steps: List<PlanStep>): Set<NodeId> {
        val out = LinkedHashSet<NodeId>()
        for (step in steps) {
            if (step.tool != ToolCatalog.DELETE_NODE) continue
            val raw = (step.args["node"] as? JsonPrimitive)?.content ?: continue
            val id = (NodeRef.parseOrNull(raw) as? NodeRef.Existing)?.id ?: continue
            out += BoardOps.deletionFootprint(board, id)
        }
        return out
    }

    fun required(
        board: Board,
        steps: List<PlanStep>,
        settings: dev.droiddoodle.model.SettingsSnapshot,
    ): Boolean {
        val affected = affectedNodes(board, steps)
        if (affected.isEmpty()) return false
        if (affected.size > settings.int(SettingKeys.AGENT_CONFIRM_THRESHOLD)) return true
        // Deleting a container is the one action whose blast radius the user is
        // least likely to have pictured, so it always confirms.
        return steps.any { step ->
            step.tool == ToolCatalog.DELETE_NODE &&
                (step.args["node"] as? JsonPrimitive)?.content
                    ?.let { NodeRef.parseOrNull(it) as? NodeRef.Existing }
                    ?.id
                    ?.let { board.childrenOf(it).isNotEmpty() } == true
        }
    }
}

/** Turn summaries are derived from the diff, never written by the model. */
internal object Summaries {
    fun fromDiff(diff: List<CellDelta>, failure: FailureInfo?): String {
        if (diff.isEmpty()) {
            return failure?.let { "nothing changed: ${it.message}" } ?: "nothing changed"
        }
        val created = diff.filter { it.kind == DeltaKind.CREATED }
        val parts = mutableListOf<String>()
        if (created.isNotEmpty()) {
            parts += "created " + created.joinToString(", ") { it.summary.substringAfter("\"").substringBefore("\"") }
        }
        for (kind in listOf(DeltaKind.UPDATED, DeltaKind.MOVED, DeltaKind.DELETED, DeltaKind.EDGE_ADDED, DeltaKind.EDGE_REMOVED)) {
            val n = diff.count { it.kind == kind }
            if (n > 0) parts += "${kind.name.lowercase().replace('_', ' ')} $n"
        }
        val base = parts.joinToString("; ")
        return if (failure == null) base else "$base; then failed at step ${failure.stepIndex}: ${failure.message}"
    }
}

/** Registered but unimplemented. See docs/23-agent-runtime.md §1. */
public class ReActStrategy : LoopStrategy {
    override val id: String = "react"
    override suspend fun run(request: TurnRequest, deps: TurnDeps): TurnResult =
        throw NotImplementedError("ReActStrategy is a phase 2 package")
}

/** Registered but unimplemented. See docs/23-agent-runtime.md §1. */
public class SingleShotStrategy : LoopStrategy {
    override val id: String = "single_shot"
    override suspend fun run(request: TurnRequest, deps: TurnDeps): TurnResult =
        throw NotImplementedError("SingleShotStrategy is a phase 2 package")
}
