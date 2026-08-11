package dev.droiddoodle.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.droiddoodle.agent.Outcome
import dev.droiddoodle.agent.PlanThenExecuteStrategy
import dev.droiddoodle.agent.ReferenceTable
import dev.droiddoodle.agent.ToolRegistry
import dev.droiddoodle.agent.TraceRecord
import dev.droiddoodle.agent.TurnDeps
import dev.droiddoodle.agent.TurnRequest
import dev.droiddoodle.agent.TurnSummary
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.Clock
import dev.droiddoodle.model.IdGenerator
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.model.Viewport
import dev.droiddoodle.world.Board
import dev.droiddoodle.world.BoardOps
import dev.droiddoodle.world.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class UiState(
    val board: Board = Board.EMPTY,
    val refs: ReferenceTable = ReferenceTable(),
    val history: History = History(),
    val transcript: List<TurnSummary> = emptyList(),
    val traces: List<TraceRecord> = emptyList(),
    val selected: NodeId? = null,
    val thinking: Boolean = false,
    val message: String? = null,
    /** Non-null while a destructive plan is waiting on the user. */
    val pendingConfirmation: PendingConfirmation? = null,
) {
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
}

internal data class PendingConfirmation(
    val userMessage: String,
    val doomed: List<NodeId>,
)

internal class BoardViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val strategy = PlanThenExecuteStrategy()
    private val registry = ToolRegistry()
    private val engine = DemoEngine()
    private val settings = SettingsSnapshot.DEFAULTS

    fun send(text: String, confirmationGranted: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.thinking) return

        _state.update { it.copy(thinking = true, message = null, pendingConfirmation = null) }

        viewModelScope.launch {
            val current = _state.value
            val result = strategy.run(
                request = TurnRequest(
                    userMessage = trimmed,
                    board = current.board,
                    viewport = Viewport(top = -6, left = -6, rows = 14, cols = 14),
                    refs = current.refs.copy(selected = current.selected),
                    history = current.transcript,
                    settings = settings,
                    confirmationGranted = confirmationGranted,
                ),
                deps = TurnDeps(
                    engine = engine,
                    registry = registry,
                    clock = Clock { System.currentTimeMillis() },
                    turnIds = IdGenerator { "turn-" + System.nanoTime().toString(36) },
                ),
            )

            if (result.outcome == Outcome.AWAITING_CONFIRMATION) {
                _state.update {
                    it.copy(
                        thinking = false,
                        traces = (it.traces + result.trace).takeLast(TRACE_LIMIT),
                        pendingConfirmation = PendingConfirmation(trimmed, result.pendingConfirmation),
                    )
                }
                return@launch
            }

            _state.update { previous ->
                // A turn that changed nothing must not consume an undo step.
                val history = if (result.diff.isNotEmpty()) {
                    previous.history.record(previous.board)
                } else {
                    previous.history
                }
                previous.copy(
                    board = result.board,
                    refs = result.refs,
                    history = history,
                    transcript = (
                        previous.transcript + TurnSummary(trimmed, result.summary)
                        ).takeLast(TRANSCRIPT_LIMIT),
                    traces = (previous.traces + result.trace).takeLast(TRACE_LIMIT),
                    thinking = false,
                    message = result.respondText ?: result.summary,
                    selected = previous.selected?.takeIf { result.board.node(it) != null },
                )
            }
        }
    }

    fun confirmPending() {
        val pending = _state.value.pendingConfirmation ?: return
        send(pending.userMessage, confirmationGranted = true)
    }

    fun dismissPending() {
        _state.update { it.copy(pendingConfirmation = null, message = "cancelled") }
    }

    fun select(id: NodeId?) {
        _state.update { it.copy(selected = id) }
    }

    /**
     * Drag-to-move. Placement goes through the same resolver the agent uses, so
     * a human drag and a model move obey identical rules -- including the
     * refusal when the target cell is taken.
     */
    fun dragTo(id: NodeId, cell: Cell) {
        val current = _state.value
        when (val moved = BoardOps.moveNode(current.board, id, Placement.Absolute(cell))) {
            is Res.Ok -> {
                if (moved.value.diff.isEmpty()) return
                _state.update {
                    it.copy(
                        board = moved.value.board,
                        history = it.history.record(current.board),
                        refs = it.refs.copy(lastModified = id),
                        message = null,
                    )
                }
            }
            is Res.Err -> _state.update { it.copy(message = moved.error.message) }
        }
    }

    fun undo() {
        val current = _state.value
        val restore = current.history.undo(current.board) ?: return
        _state.update {
            it.copy(board = restore.board, history = restore.history, message = "undone")
        }
    }

    fun redo() {
        val current = _state.value
        val restore = current.history.redo(current.board) ?: return
        _state.update {
            it.copy(board = restore.board, history = restore.history, message = "redone")
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val TRACE_LIMIT = 200
        const val TRANSCRIPT_LIMIT = 20
    }
}
