package dev.droiddoodle.world

import dev.droiddoodle.model.Limits

/**
 * Undo does not replay inverse operations. Each committed turn pushes the
 * previous immutable [Board] reference; undo pops and restores it.
 *
 * Because boards are immutable with structural sharing this is cheap and exactly
 * correct -- it cannot drift from the real prior state the way a hand-written
 * inverse operation can.
 *
 * Granularity is one **turn**, not one tool call: a plan that creates a village,
 * a tavern and a smith is undone in a single step, matching what the user
 * perceives as one action. See docs/20-world-model.md §10.
 */
public data class History(
    public val past: List<Board> = emptyList(),
    public val future: List<Board> = emptyList(),
    public val depth: Int = Limits.UNDO_DEPTH,
) {
    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()

    /** Called once per committed turn with the board as it was before the turn. */
    public fun record(previous: Board): History =
        copy(past = (past + previous).takeLast(depth), future = emptyList())

    public fun undo(current: Board): Restore? {
        val restored = past.lastOrNull() ?: return null
        return Restore(
            history = copy(
                past = past.dropLast(1),
                future = (listOf(current) + future).take(depth),
            ),
            board = restored,
        )
    }

    public fun redo(current: Board): Restore? {
        val restored = future.firstOrNull() ?: return null
        return Restore(
            history = copy(
                past = (past + current).takeLast(depth),
                future = future.drop(1),
            ),
            board = restored,
        )
    }

    public data class Restore(
        public val history: History,
        public val board: Board,
    )
}
