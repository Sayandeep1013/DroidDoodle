package dev.droiddoodle.agent

import dev.droiddoodle.model.CellDelta
import dev.droiddoodle.model.Clock
import dev.droiddoodle.model.IdGenerator
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.model.Viewport
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.world.Board

public enum class Outcome { OK, PARTIAL, REJECTED, ABORTED, AWAITING_CONFIRMATION }

public enum class StepResult { OK, FAILED, SKIPPED }

public enum class RoundRole { INITIAL, RETRIEVAL_REPLAN, REPAIR }

/**
 * How anaphora is resolved -- by lookup, not by the model.
 *
 * "Move that one to the left" resolves through this table. Asking a 1B model to
 * track referents across conversational turns is the fastest way to make it look
 * incompetent, whereas three deterministic key-value pairs make it look sharp.
 *
 * Computed by the runtime from committed turns; never written by the model.
 * See docs/22-context.md §5.
 */
public data class ReferenceTable(
    public val lastCreated: NodeId? = null,
    public val lastModified: NodeId? = null,
    public val selected: NodeId? = null,
) {
    public val referencedIds: List<NodeId>
        get() = listOfNotNull(lastCreated, lastModified, selected).distinct()

    public val isEmpty: Boolean get() = referencedIds.isEmpty()

    /** Rendered as prompt block 4, or null when there is nothing to say. */
    public fun render(): String? {
        if (isEmpty) return null
        val parts = buildList {
            lastCreated?.let { add("last_created=$it") }
            lastModified?.let { add("last_modified=$it") }
            selected?.let { add("selected=$it") }
        }
        return "refs: " + parts.joinToString(" ")
    }

    /** Recomputed after every committed turn from what actually changed. */
    public fun updatedBy(diff: List<CellDelta>, board: Board): ReferenceTable {
        val created = diff.lastOrNull {
            it.kind == dev.droiddoodle.model.DeltaKind.CREATED
        }?.nodeId
        val modified = diff.lastOrNull {
            it.kind in setOf(
                dev.droiddoodle.model.DeltaKind.UPDATED,
                dev.droiddoodle.model.DeltaKind.MOVED,
                dev.droiddoodle.model.DeltaKind.EDGE_ADDED,
            )
        }?.nodeId
        return ReferenceTable(
            lastCreated = created?.takeIf { board.node(it) != null } ?: lastCreated
                ?.takeIf { board.node(it) != null },
            lastModified = modified?.takeIf { board.node(it) != null } ?: lastModified
                ?.takeIf { board.node(it) != null },
            selected = selected?.takeIf { board.node(it) != null },
        )
    }
}

/** One prior exchange, rendered into prompt block 5. */
public data class TurnSummary(
    public val userMessage: String,
    public val outcomeLine: String,
)

public data class TurnRequest(
    public val userMessage: String,
    public val board: Board,
    public val viewport: Viewport = Viewport.DEFAULT,
    public val refs: ReferenceTable = ReferenceTable(),
    public val history: List<TurnSummary> = emptyList(),
    public val settings: SettingsSnapshot = SettingsSnapshot.DEFAULTS,
    /**
     * Set when the user has already approved a plan that tripped the
     * confirmation gate. Carried in the request rather than resolved through a
     * UI callback, so the gate stays testable headlessly.
     */
    public val confirmationGranted: Boolean = false,
)

public data class TurnDeps(
    public val engine: LlmEngine,
    public val registry: ToolRegistry,
    public val clock: Clock,
    public val turnIds: IdGenerator,
)

public data class StepOutcome(
    public val index: Int,
    public val tool: String,
    public val args: String,
    public val resolvedRefs: Map<String, String> = emptyMap(),
    public val result: StepResult,
    public val error: String? = null,
    public val durationMillis: Long = 0,
)

public data class FailureInfo(
    public val stepIndex: Int?,
    public val code: String,
    public val message: String,
)

public data class TurnResult(
    public val outcome: Outcome,
    public val board: Board,
    public val refs: ReferenceTable,
    public val executed: List<StepOutcome>,
    public val diff: List<CellDelta>,
    public val failure: FailureInfo? = null,
    /** Deterministic, derived from the diff -- never written by the model. */
    public val summary: String,
    public val respondText: String? = null,
    public val settingWrites: List<Pair<String, String>> = emptyList(),
    public val pendingConfirmation: List<NodeId> = emptyList(),
    public val trace: TraceRecord,
) {
    public val mutatedBoard: Boolean get() = diff.isNotEmpty()
}
