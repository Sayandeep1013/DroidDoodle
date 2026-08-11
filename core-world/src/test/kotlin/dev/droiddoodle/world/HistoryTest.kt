package dev.droiddoodle.world

import dev.droiddoodle.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {

    @Test
    fun `undo restores a board equal to the pre-turn value`() {
        val before = villageBoard()
        val after = BoardOps.addNode(before, NodeType.PLACE, "Castle").board()
        val history = History().record(before)

        val restore = history.undo(after)
        assertNotNull(restore)
        assertEquals(before, restore.board)
    }

    @Test
    fun `undo then redo returns to the later state`() {
        val before = villageBoard()
        val after = BoardOps.addNode(before, NodeType.PLACE, "Castle").board()

        val undone = History().record(before).undo(after)!!
        assertEquals(before, undone.board)

        val redone = undone.history.redo(undone.board)
        assertNotNull(redone)
        assertEquals(after, redone.board)
    }

    @Test
    fun `recording a new turn clears the redo stack`() {
        val a = villageBoard()
        val b = BoardOps.addNode(a, NodeType.PLACE, "Castle").board()
        val undone = History().record(a).undo(b)!!
        assertTrue(undone.history.canRedo)

        val afterNewTurn = undone.history.record(undone.board)
        assertFalse(afterNewTurn.canRedo)
    }

    @Test
    fun `undo on an empty history does nothing`() {
        assertNull(History().undo(villageBoard()))
        assertNull(History().redo(villageBoard()))
    }

    @Test
    fun `history is bounded to its depth`() {
        var history = History(depth = 3)
        var board = Board.EMPTY
        repeat(10) {
            val previous = board
            board = BoardOps.addNode(board, NodeType.OBJECT, "n$it").board()
            history = history.record(previous)
        }
        assertEquals(3, history.past.size)
    }

    @Test
    fun `a whole turn is one undo step regardless of how many nodes it created`() {
        // A plan that creates a village, a tavern and a smith is undone in a
        // single step, matching what the user perceives as one action.
        val before = Board.EMPTY
        var after = before
        for (label in listOf("Village", "Tavern", "Borin")) {
            after = BoardOps.addNode(after, NodeType.PLACE, label).board()
        }
        assertEquals(3, after.size)

        val restore = History().record(before).undo(after)!!
        assertEquals(0, restore.board.size)
        assertFalse(restore.history.canUndo)
    }
}
