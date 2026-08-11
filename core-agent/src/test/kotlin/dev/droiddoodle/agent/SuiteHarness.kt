package dev.droiddoodle.agent

import dev.droiddoodle.grammar.GrammarSpec
import dev.droiddoodle.grammar.PlanEnvelopeChecker
import dev.droiddoodle.inference.MockEngine
import dev.droiddoodle.inference.MockResponse
import dev.droiddoodle.inference.OutputCheck
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.Clock
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.IdGenerator
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.model.Viewport
import dev.droiddoodle.world.Board
import dev.droiddoodle.world.BoardOps
import kotlin.test.fail

// ---- board fixtures (docs/31-prompt-suite.md §2) -------------------------

internal fun board(): Board = Board.EMPTY

internal fun Board.put(
    label: String,
    row: Int,
    col: Int,
    type: NodeType = NodeType.PLACE,
    kind: String = "",
): Board = when (
    val r = BoardOps.addNode(this, type, label, kind, Placement.Absolute(Cell(row, col)))
) {
    is Res.Ok -> r.value.board
    is Res.Err -> fail("fixture could not place '$label': ${r.error.message}")
}

internal fun Board.link(from: String, to: String, type: EdgeType): Board = when (
    val r = BoardOps.addEdge(this, type, idOf(from), idOf(to))
) {
    is Res.Ok -> r.value.board
    is Res.Err -> fail("fixture could not link $from->$to: ${r.error.message}")
}

internal fun Board.idOf(label: String): NodeId =
    nodes.values.firstOrNull { it.label == label }?.id ?: fail("no node labelled '$label'")

internal fun Board.idOrNull(label: String): NodeId? =
    nodes.values.firstOrNull { it.label == label }?.id

internal val EMPTY: Board get() = Board.EMPTY

internal val VILLAGE: Board
    get() = board()
        .put("Village", 0, 0)
        .put("Tavern", 0, 1)
        .put("Borin", 1, 0, NodeType.CHARACTER, "blacksmith")
        .link("Village", "Tavern", EdgeType.CONTAINS)
        .link("Village", "Borin", EdgeType.CONTAINS)

/** VILLAGE with every cell in rows -1..1, columns -1..1 filled. */
internal val CROWDED: Board
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
internal val BOARD_20: Board
    get() {
        var b = VILLAGE.put("Dragon", 9, 9, NodeType.CHARACTER, "dragon")
        for (i in 1..16) {
            b = b.put("far$i", 5 + i / 6, 5 + i % 6, NodeType.OBJECT)
        }
        return b
    }

// ---- assertions ----------------------------------------------------------

internal data class SuiteOutcome(val board: Board, val result: TurnResult)

internal typealias Assertion = (SuiteOutcome) -> String?

internal fun nodeCount(n: Int): Assertion = { o ->
    if (o.board.size == n) null else "expected $n nodes, found ${o.board.size}"
}

internal fun nodeExists(label: String, type: NodeType? = null, kind: String? = null): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label' (have: ${o.board.nodes.values.map { it.label }})"
        type != null && node.type != type -> "'$label' is ${node.type}, expected $type"
        kind != null && !node.kind.equals(kind, ignoreCase = true) ->
            "'$label' has kind '${node.kind}', expected '$kind'"
        else -> null
    }
}

internal fun nodeAbsent(label: String): Assertion = { o ->
    if (o.board.idOrNull(label) == null) null else "'$label' should have been removed"
}

internal fun attrEquals(label: String, key: String, value: String): Assertion = { o ->
    val node = o.board.nodes.values.firstOrNull { it.label.equals(label, ignoreCase = true) }
    when {
        node == null -> "no node labelled '$label'"
        node.attributes[key] != value ->
            "'$label'.$key is ${node.attributes[key]}, expected '$value'"
        else -> null
    }
}

internal fun cellEquals(label: String, row: Int, col: Int): Assertion = { o ->
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
internal fun northOf(a: String, b: String): Assertion = { o ->
    val ca = o.board.nodes.values.first { it.label == a }.cell
    val cb = o.board.nodes.values.first { it.label == b }.cell
    if (ca.row < cb.row) null else "'$a' at $ca is not north of '$b' at $cb"
}

internal fun westOf(a: String, b: String): Assertion = { o ->
    val ca = o.board.nodes.values.first { it.label == a }.cell
    val cb = o.board.nodes.values.first { it.label == b }.cell
    if (ca.col < cb.col) null else "'$a' at $ca is not west of '$b' at $cb"
}

internal fun sameRow(vararg labels: String): Assertion = { o ->
    val rows = labels.map { l -> o.board.nodes.values.first { it.label == l }.cell.row }.toSet()
    if (rows.size == 1) null else "expected one row, found $rows"
}

internal fun edgeExists(from: String, to: String, type: EdgeType): Assertion = { o ->
    val found = o.board.edgesBetween(o.board.idOf(from), o.board.idOf(to), type)
    if (found.isNotEmpty()) null else "no $type edge from '$from' to '$to'"
}

internal fun outcomeIs(expected: Outcome): Assertion = { o ->
    if (o.result.outcome == expected) {
        null
    } else {
        "outcome was ${o.result.outcome}, expected $expected (${o.result.failure?.message ?: ""})"
    }
}

internal fun failureCodeIs(code: String): Assertion = { o ->
    if (o.result.failure?.code == code) {
        null
    } else {
        "failure code was ${o.result.failure?.code}, expected $code"
    }
}

internal fun settingWritten(key: String, value: String): Assertion = { o ->
    if (o.result.settingWrites.any { it.first == key && it.second == value }) {
        null
    } else {
        "expected $key=$value, got ${o.result.settingWrites}"
    }
}

internal fun confirmationRequested(minimumNodes: Int): Assertion = { o ->
    when {
        o.result.outcome != Outcome.AWAITING_CONFIRMATION ->
            "expected AWAITING_CONFIRMATION, got ${o.result.outcome}"
        o.result.pendingConfirmation.size < minimumNodes ->
            "expected at least $minimumNodes doomed nodes, got ${o.result.pendingConfirmation.size}"
        else -> null
    }
}

internal fun respondGiven(): Assertion = { o ->
    if (!o.result.respondText.isNullOrBlank()) null else "expected the model to respond"
}

internal fun boardUnchanged(): Assertion = { o ->
    if (o.result.diff.isEmpty()) null else "expected no board change, got ${o.result.diff.size} deltas"
}

// ---- runner --------------------------------------------------------------

internal data class SuiteCase(
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

internal object SuiteRunner {

    suspend fun run(case: SuiteCase): SuiteOutcome {
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
    fun verify(case: SuiteCase, outcome: SuiteOutcome) {
        val failures = case.assertions.mapNotNull { it(outcome) }
        if (failures.isNotEmpty()) {
            fail(
                "case ${case.id} failed:\n" + failures.joinToString("\n") { "  - $it" } +
                    "\n  outcome=${outcome.result.outcome} summary=${outcome.result.summary}",
            )
        }
    }
}
