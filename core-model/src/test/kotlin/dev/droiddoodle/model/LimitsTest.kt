package dev.droiddoodle.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LimitsTest {

    @Test
    fun `labels must be non blank and within length`() {
        assertNull(Limits.checkLabel("Village"))
        assertNotNull(Limits.checkLabel(""))
        assertNotNull(Limits.checkLabel("   "))
        assertNull(Limits.checkLabel("x".repeat(Limits.LABEL_MAX)))
        assertNotNull(Limits.checkLabel("x".repeat(Limits.LABEL_MAX + 1)))
    }

    @Test
    fun `kind may be blank but not overlong`() {
        assertNull(Limits.checkKind(""))
        assertNull(Limits.checkKind("blacksmith"))
        assertNotNull(Limits.checkKind("x".repeat(Limits.KIND_MAX + 1)))
    }

    @Test
    fun `custom relations require a label`() {
        assertNotNull(Limits.checkEdgeLabel(EdgeType.CUSTOM, ""))
        assertNull(Limits.checkEdgeLabel(EdgeType.CUSTOM, "haunts"))
        assertNull(Limits.checkEdgeLabel(EdgeType.OWNS, ""))
        assertNotNull(Limits.checkEdgeLabel(EdgeType.OWNS, "x".repeat(Limits.EDGE_LABEL_MAX + 1)))
    }

    @Test
    fun `attribute keys are constrained and values are bounded`() {
        assertNull(Limits.checkAttributes(mapOf("secret" to "vampire")))
        assertNull(Limits.checkAttributes(mapOf("afraid_of2" to "frogs")))
        assertNotNull(Limits.checkAttributes(mapOf("Secret" to "vampire")))
        assertNotNull(Limits.checkAttributes(mapOf("secret identity" to "vampire")))
        assertNotNull(Limits.checkAttributes(mapOf("" to "v")))
        assertNotNull(Limits.checkAttributes(mapOf("k" to "v".repeat(Limits.ATTR_VALUE_MAX + 1))))
    }

    @Test
    fun `attribute count is capped`() {
        val tooMany = (1..Limits.ATTRS_MAX + 1).associate { "k$it" to "v" }
        assertNotNull(Limits.checkAttributes(tooMany))
        val justEnough = (1..Limits.ATTRS_MAX).associate { "k$it" to "v" }
        assertNull(Limits.checkAttributes(justEnough))
    }

    @Test
    fun `attribute keys normalise to lowercase snake case`() {
        assertEquals("secret_identity", Limits.normalizeAttrKey("Secret Identity"))
        assertEquals("afraid_of", Limits.normalizeAttrKey("  afraid-of "))
        assertEquals("secret", Limits.normalizeAttrKey("SECRET"))
    }

    @Test
    fun `respond text is bounded`() {
        assertNull(Limits.checkRespondText("ok"))
        assertNotNull(Limits.checkRespondText(""))
        assertNotNull(Limits.checkRespondText("x".repeat(Limits.RESPOND_MAX + 1)))
    }
}
