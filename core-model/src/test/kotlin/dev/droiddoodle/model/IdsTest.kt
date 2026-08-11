package dev.droiddoodle.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun `accepts well formed node ids`() {
        assertEquals("n1", NodeId("n1").value)
        assertEquals("n42", NodeId.of(42).value)
    }

    @Test
    fun `rejects malformed node ids`() {
        for (bad in listOf("", "n", "n0", "n01", "x1", "N1", "n1 ", " n1", "nn1", "n-1", "1")) {
            assertFailsWith<IllegalArgumentException>("expected '$bad' to be rejected") {
                NodeId(bad)
            }
            assertNull(NodeId.parseOrNull(bad), "expected parseOrNull('$bad') to be null")
        }
    }

    @Test
    fun `rejects non positive sequence numbers`() {
        assertFailsWith<IllegalArgumentException> { NodeId.of(0) }
        assertFailsWith<IllegalArgumentException> { NodeId.of(-1) }
    }

    @Test
    fun `edge ids follow the same rules`() {
        assertEquals("e7", EdgeId.of(7).value)
        assertNull(EdgeId.parseOrNull("e0"))
        assertNull(EdgeId.parseOrNull("n1"))
    }

    @Test
    fun `node refs parse both existing ids and step references`() {
        assertEquals(NodeRef.Existing(NodeId("n3")), NodeRef.parseOrNull("n3"))
        assertEquals(NodeRef.Step(2), NodeRef.parseOrNull("\$2"))
        assertNull(NodeRef.parseOrNull("\$0"))
        assertNull(NodeRef.parseOrNull("\$x"))
        assertNull(NodeRef.parseOrNull("bogus"))
    }

    @Test
    fun `cell bounds match the 65 by 65 addressable field`() {
        assertTrue(Cell(-32, 32).inBounds)
        assertTrue(Cell(0, 0).inBounds)
        assertTrue(!Cell(-33, 0).inBounds)
        assertTrue(!Cell(0, 33).inBounds)
    }

    @Test
    fun `symmetric edges dedupe regardless of direction`() {
        val a = Edge(EdgeId.of(1), EdgeType.CONNECTS, NodeId("n1"), NodeId("n2"))
        val b = Edge(EdgeId.of(2), EdgeType.CONNECTS, NodeId("n2"), NodeId("n1"))
        assertEquals(a.dedupeKey, b.dedupeKey)
    }

    @Test
    fun `directed edges do not dedupe across direction`() {
        val a = Edge(EdgeId.of(1), EdgeType.OWNS, NodeId("n1"), NodeId("n2"))
        val b = Edge(EdgeId.of(2), EdgeType.OWNS, NodeId("n2"), NodeId("n1"))
        assertTrue(a.dedupeKey != b.dedupeKey)
    }
}
