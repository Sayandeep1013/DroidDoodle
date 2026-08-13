package dev.droiddoodle.suite

import dev.droiddoodle.agent.Outcome
import dev.droiddoodle.agent.LoopStrategy
import dev.droiddoodle.agent.PlanThenExecuteStrategy
import dev.droiddoodle.agent.ReferenceTable
import dev.droiddoodle.agent.ToolRegistry
import dev.droiddoodle.agent.TurnDeps
import dev.droiddoodle.agent.TurnRequest
import dev.droiddoodle.agent.TurnResult
import dev.droiddoodle.grammar.GrammarSpec
import dev.droiddoodle.grammar.PlanEnvelopeChecker
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.inference.MockEngine
import dev.droiddoodle.inference.MockResponse
import dev.droiddoodle.inference.OutputCheck
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.Clock
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.IdGenerator
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.model.Viewport
import dev.droiddoodle.world.Board
import dev.droiddoodle.world.BoardOps

// ---- board fixtures (docs/31-prompt-suite.md §2) -------------------------

public fun board(): Board = Board.EMPTY

public fun Board.put(
    label: String,
    row: Int,
    col: Int,
    type: NodeType = NodeType.PLACE,
    kind: String = "",
): Board = when (
    val r = BoardOps.addNode(this, type, label, kind, Placement.Absolute(Cell(row, col)))
) {
    is Res.Ok -> r.value.board
    is Res.Err -> error("fixture could not place '$label': ${r.error.message}")
}

public fun Board.link(from: String, to: String, type: EdgeType): Board = when (
    val r = BoardOps.addEdge(this, type, idOf(from), idOf(to))
) {
    is Res.Ok -> r.value.board
    is Res.Err -> error("fixture could not link $from->$to: ${r.error.message}")
}

/**
 * Label lookups are case-insensitive, matching `nodeExists`.
 *
 * They were not, and the first device runs punished the model for it: Gemma
 * created a node labelled "village" and the edge and position assertions
 * reported it missing while the failure message printed `have: [village]`
 * right beside "no node labelled 'Village'". Label casing is cosmetic free
 * text; the suite measures whether the right structure was built.
 */
public fun Board.idOf(label: String): NodeId =
    nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }?.id
        ?: error("no node labelled '$label'")

public fun Board.idOrNull(label: String): NodeId? =
    nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }?.id

public val EMPTY: Board get() = Board.EMPTY

public val VILLAGE: Board
    get() = board()
        .put("Village", 0, 0)
        .put("Tavern", 0, 1)
        .put("Borin", 1, 0, NodeType.CHARACTER, "blacksmith")
        .link("Village", "Tavern", EdgeType.CONTAINS)
        .link("Village", "Borin", EdgeType.CONTAINS)

/**
 * VILLAGE plus a Castle away from the cluster, for the anaphora cases: "it" and
 * "that" must resolve through the reference table to n4, never by the model
 * guessing from the board.
 */
public val VILLAGE_WITH_CASTLE: Board get() = VILLAGE.put("Castle", 2, 2)

/** VILLAGE with every cell in rows -1..1, columns -1..1 filled. */
public val CROWDED: Board
    get() {
        var b = VILLAGE
        var n = 1
        for (row in -1..1) {
            for (col in -1..1) {
                if (b.nodeAt(Cell(row, col)) == null) {
                    b = b.put("filler${n++}", row, col, NodeType.OBJECT)
                }
            }
        }
        return b
    }

/** Twenty nodes spread well beyond an 8x8 viewport. */
public val BOARD_20: Board
    get() {
        var b = VILLAGE.put("Dragon", 9, 9, NodeType.CHARACTER, "dragon")
        for (i in 1..16) {
            b = b.put("far$i", 5 + i / 6, 5 + i % 6, NodeType.OBJECT)
        }
        return b
    }

// ---- assertions ----------------------------------------------------------

public data class SuiteOutcome(val board: Board, val result: TurnResult)

public typealias Assertion = (SuiteOutcome) -> String?

public fun nodeCount(n: Int): Assertion = { o ->
    if (o.board.size == n) null else "expected $n nodes, found ${o.board.size}"
}

public fun nodeExists(label: String, type: NodeType? = null, kind: String? = null): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label' (have: ${o.board.nodes.values.map { it.label }})"
        type != null && node.type != type -> "'$label' is ${node.type}, expected $type"
        kind != null && !node.kind.equals(kind, ignoreCase = true) ->
            "'$label' has kind '${node.kind}', expected '$kind'"
        else -> null
    }
}

public fun nodeAbsent(label: String): Assertion = { o ->
    if (o.board.idOrNull(label) == null) null else "'$label' should have been removed"
}

public fun attrEquals(label: String, key: String, value: String): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label'"
        node.attributes[key] != value ->
            "'$label'.$key is ${node.attributes[key]}, expected '$value'"
        else -> null
    }
}

/**
 * Checked separately from [attrEquals]: colour lives on `Node.style`, not in
 * the free-text attribute map, so a case testing "make it red" must assert
 * this rather than an attribute to actually exercise the `color` argument
 * instead of passing on outcome and node count alone.
 */
public fun colorEquals(label: String, color: NodeColor): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label'"
        node.style.color != color -> "'$label' is ${node.style.color}, expected $color"
        else -> null
    }
}

public fun cellEquals(label: String, row: Int, col: Int): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label'"
        node.cell != Cell(row, col) -> "'$label' is at ${node.cell}, expected r${row}c$col"
        else -> null
    }
}

/**
 * Directional assertions check the inequality, not exact adjacency, matching the
 * guarantee in docs/20-world-model.md §7. Cases needing exact adjacency use
 * [cellEquals] and declare a board where the target cell is free.
 */
/**
 * Looks up a node for a positional assertion, or explains its absence.
 *
 * `first { }` throws when the model simply did not create the node, which took
 * the whole case out of the run rather than failing it -- `multi-03` was lost
 * that way in the first device run. A missing node is an ordinary failure and
 * has to read as one.
 */
private fun Board.cellOfOrNull(label: String) =
    nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }?.cell

public fun northOf(a: String, b: String): Assertion = { o ->
    val ca = o.board.cellOfOrNull(a)
    val cb = o.board.cellOfOrNull(b)
    when {
        ca == null -> "no node labelled '$a' (have: ${o.board.nodes.values.map { it.label }})"
        cb == null -> "no node labelled '$b' (have: ${o.board.nodes.values.map { it.label }})"
        ca.row < cb.row -> null
        else -> "'$a' at $ca is not north of '$b' at $cb"
    }
}

public fun westOf(a: String, b: String): Assertion = { o ->
    val ca = o.board.cellOfOrNull(a)
    val cb = o.board.cellOfOrNull(b)
    when {
        ca == null -> "no node labelled '$a' (have: ${o.board.nodes.values.map { it.label }})"
        cb == null -> "no node labelled '$b' (have: ${o.board.nodes.values.map { it.label }})"
        ca.col < cb.col -> null
        else -> "'$a' at $ca is not west of '$b' at $cb"
    }
}

public fun sameRow(vararg labels: String): Assertion = { o ->
    val missing = labels.filter { o.board.cellOfOrNull(it) == null }
    val rows = labels.mapNotNull { o.board.cellOfOrNull(it)?.row }.toSet()
    when {
        missing.isNotEmpty() -> "missing ${missing.joinToString(", ")}"
        rows.size == 1 -> null
        else -> "expected one row, found $rows"
    }
}

public fun edgeExists(from: String, to: String, type: EdgeType): Assertion = { o ->
    // idOf throws on a missing node, for the same reason as above.
    val a = o.board.idOrNull(from)
    val b = o.board.idOrNull(to)
    when {
        a == null -> "no node labelled '$from'"
        b == null -> "no node labelled '$to'"
        o.board.edgesBetween(a, b, type).isNotEmpty() -> null
        else -> "no $type edge from '$from' to '$to'"
    }
}

public fun outcomeIs(expected: Outcome): Assertion = { o ->
    if (o.result.outcome == expected) {
        null
    } else {
        "outcome was ${o.result.outcome}, expected $expected (${o.result.failure?.message ?: ""})"
    }
}

public fun failureCodeIs(code: String): Assertion = { o ->
    if (o.result.failure?.code == code) {
        null
    } else {
        "failure code was ${o.result.failure?.code}, expected $code"
    }
}

public fun settingWritten(key: String, value: String): Assertion = { o ->
    if (o.result.settingWrites.any { it.first == key && it.second == value }) {
        null
    } else {
        "expected $key=$value, got ${o.result.settingWrites}"
    }
}

public fun confirmationRequested(minimumNodes: Int): Assertion = { o ->
    when {
        o.result.outcome != Outcome.AWAITING_CONFIRMATION ->
            "expected AWAITING_CONFIRMATION, got ${o.result.outcome}"
        o.result.pendingConfirmation.size < minimumNodes ->
            "expected at least $minimumNodes doomed nodes, got ${o.result.pendingConfirmation.size}"
        else -> null
    }
}

public fun respondGiven(): Assertion = { o ->
    if (!o.result.respondText.isNullOrBlank()) null else "expected the model to respond"
}

public fun boardUnchanged(): Assertion = { o ->
    if (o.result.diff.isEmpty()) null else "expected no board change, got ${o.result.diff.size} deltas"
}

// ---- runner --------------------------------------------------------------

public data class SuiteCase(
    val id: String,
    val board: Board,
    val message: String,
    val plans: List<String>,
    val assertions: List<Assertion>,
    val refs: ReferenceTable = ReferenceTable(),
    val settings: SettingsSnapshot = SettingsSnapshot.DEFAULTS,
    val confirmationGranted: Boolean = false,
    /**
     * Set only for cases that deliberately exercise the executor's
     * defence-in-depth against output the grammar could never produce.
     */
    val skipOutputCheck: Boolean = false,
    val viewport: Viewport = Viewport(top = -6, left = -6, rows = 16, cols = 16),
)

public object SuiteRunner {

    /** Builds the grammar spec a case runs under. Shared by both modes. */
    public fun specFor(case: SuiteCase): GrammarSpec = GrammarSpec(
        tools = dev.droiddoodle.model.ToolCatalog.ALL,
        existingIds = case.board.nodes.keys.sortedBy { it.value.drop(1).toInt() },
        maxSteps = case.settings.int(dev.droiddoodle.model.SettingKeys.AGENT_MAX_STEPS),
        agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
    )

    /**
     * MODEL mode: run the case against a real engine, ignoring its canned plan.
     * This is what P10 measures; it says nothing until a real model drives it.
     */
    public suspend fun runWith(
        case: SuiteCase,
        engine: LlmEngine,
        clock: Clock,
        strategy: LoopStrategy = PlanThenExecuteStrategy(),
    ): SuiteOutcome {
        val result = strategy.run(
            request = TurnRequest(
                userMessage = case.message,
                board = case.board,
                viewport = case.viewport,
                refs = case.refs,
                settings = case.settings,
                confirmationGranted = case.confirmationGranted,
            ),
            deps = TurnDeps(
                engine = engine,
                registry = ToolRegistry(),
                clock = clock,
                // Prefixed with the case id. A bare sequential generator makes
                // every case's turn "turn-1", so an exported multi-case
                // document would carry colliding ids -- unusable as a record.
                turnIds = IdGenerator.sequential(prefix = case.id),
            ),
        )
        return SuiteOutcome(result.board, result)
    }

    /** Assertion failures for a case, empty when it passed. */
    public fun failures(case: SuiteCase, outcome: SuiteOutcome): List<String> =
        case.assertions.mapNotNull { it(outcome) }

    /** RUNTIME mode: MockEngine plays the canned plan back. */
    public suspend fun run(case: SuiteCase): SuiteOutcome {
        val spec = GrammarSpec(
            tools = dev.droiddoodle.model.ToolCatalog.ALL,
            existingIds = case.board.nodes.keys.sortedBy { it.value.drop(1).toInt() },
            maxSteps = case.settings.int(dev.droiddoodle.model.SettingKeys.AGENT_MAX_STEPS),
            agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
        )
        val checker = PlanEnvelopeChecker(spec)

        val engine = MockEngine(
            script = case.plans.map { MockResponse(it) },
            outputCheck = if (case.skipOutputCheck) {
                OutputCheck.None
            } else {
                OutputCheck { output, _ -> checker.check(output).map { } }
            },
        )

        val result = PlanThenExecuteStrategy().run(
            request = TurnRequest(
                userMessage = case.message,
                board = case.board,
                viewport = case.viewport,
                refs = case.refs,
                settings = case.settings,
                confirmationGranted = case.confirmationGranted,
            ),
            deps = TurnDeps(
                engine = engine,
                registry = ToolRegistry(),
                clock = Clock.fixed(),
                turnIds = IdGenerator.sequential(),
            ),
        )
        return SuiteOutcome(result.board, result)
    }

    /** A case passes only when every assertion holds. Partial credit is not recorded. */
    public fun verify(case: SuiteCase, outcome: SuiteOutcome) {
        val failures = failures(case, outcome)
        if (failures.isNotEmpty()) {
            error(
                "case ${case.id} failed:\n" + failures.joinToString("\n") { "  - $it" } +
                    "\n  outcome=${outcome.result.outcome} summary=${outcome.result.summary}",
            )
        }
    }
}

// ---- canned-plan helpers (RUNTIME mode only) -----------------------------

/**
 * Builds the plan envelope MockEngine replays. MODEL mode never calls these:
 * there, a real model produces the plan and these strings are ignored.
 */
public fun plan(vararg steps: String): String =
    """{"steps":[${steps.joinToString(",")}]}"""

public fun create(type: String, label: String, extra: String = ""): String =
    """{"tool":"create_node","args":{"type":"$type","label":"$label"$extra}}"""
