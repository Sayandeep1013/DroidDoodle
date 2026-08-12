package dev.droiddoodle.suite

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Prompt Suite in RUNTIME mode: `MockEngine` plays a correct plan back and
 * the runtime must execute it correctly.
 *
 * This proves the runtime executes correct plans correctly. It proves **nothing
 * whatsoever** about any model -- that is MODEL mode, which needs a device and
 * is driven from the app. Conflating the two would make every later
 * measurement meaningless.
 *
 * RUNTIME mode is a hard gate: a failure here is a runtime bug.
 * See docs/31-prompt-suite.md.
 */
class PromptSuiteTest {

    /**
     * One reported test per case, generated from the same list the device
     * runner walks. Previously each case was a hand-written `@Test`, which is
     * what let the list live somewhere the app could not reach it.
     */
    @TestFactory
    fun `every case passes in RUNTIME mode`(): List<DynamicTest> =
        PromptSuite.ALL.map { case ->
            DynamicTest.dynamicTest(case.id) {
                runTest { SuiteRunner.verify(case, SuiteRunner.run(case)) }
            }
        }

    @Test
    fun `case ids are unique`() {
        // BY_ID silently drops duplicates, and a duplicate id would quietly
        // reduce coverage while the pass rate still read 100%.
        assertEquals(PromptSuite.ALL.size, PromptSuite.BY_ID.size)
    }

    @Test
    fun `every case has at least one assertion`() {
        // A case with no assertions cannot fail, which is worse than not having
        // the case at all: it inflates the pass rate P10 publishes.
        val empty = PromptSuite.ALL.filter { it.assertions.isEmpty() }.map { it.id }
        assertTrue(empty.isEmpty(), "cases with no assertions: $empty")
    }

    @Test
    fun `every case has a canned plan for RUNTIME mode`() {
        val planless = PromptSuite.ALL.filter { it.plans.isEmpty() }.map { it.id }
        assertTrue(planless.isEmpty(), "cases with no canned plan: $planless")
    }

    @Test
    fun `the suite still covers every category the spec names`() {
        // Drift detector. Losing a category to a bad merge would show up as a
        // suspiciously clean run rather than as a failure.
        val expected = setOf(
            "create", "multi", "modify", "move", "connect",
            "delete", "anaph", "arrange", "setting", "fail", "find", "ambig",
        )
        assertEquals(expected, PromptSuite.CATEGORIES.toSet())
    }
}
