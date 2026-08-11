package dev.droiddoodle.app

import dev.droiddoodle.inference.GenerationResult
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.inference.SamplingParams
import dev.droiddoodle.inference.StopReason
import kotlinx.coroutines.delay

/**
 * A deterministic stand-in for the real model, used until P8 lands.
 *
 * It pattern-matches a handful of phrases onto hand-written plans. That is
 * emphatically **not** intelligence -- it is a fixture that lets the whole
 * pipeline (context assembly, grammar, validation, execution, trace, rendering)
 * be exercised on a real device with no model file present.
 *
 * The point of P7 is to prove the canvas and the runtime work together. Proving
 * a model can drive them is P8's job, and conflating the two would let a broken
 * pipeline hide behind a plausible-looking demo.
 */
internal class DemoEngine : LlmEngine {

    override val modelId: String = "demo-scripted"
    override val contextTokens: Int = 4096

    override fun tokenCount(text: String): Int = (text.length / 4).coerceAtLeast(1)

    override suspend fun generate(
        prompt: String,
        grammar: String,
        params: SamplingParams,
    ): GenerationResult {
        // A visible pause, so the UI's thinking state is exercised rather than
        // skipped past. Real decode on a mid-range CPU is measured in seconds.
        delay(450)
        val message = prompt.substringAfterLast("\n> ").trim().lowercase()
        val text = planFor(message, prompt)
        return GenerationResult(
            text = text,
            promptTokens = tokenCount(prompt),
            outputTokens = tokenCount(text),
            prefillMillis = 180,
            decodeMillis = 270,
            stopReason = StopReason.COMPLETE,
        )
    }

    override fun close(): Unit = Unit

    private fun planFor(message: String, prompt: String): String {
        val firstId = Regex("^(n\\d+) ", RegexOption.MULTILINE).find(prompt)?.groupValues?.get(1)

        fun steps(vararg s: String) = """{"steps":[${s.joinToString(",")}]}"""
        fun create(type: String, label: String, extra: String = "") =
            """{"tool":"create_node","args":{"type":"$type","label":"$label"$extra}}"""

        return when {
            "village" in message -> steps(
                create("PLACE", "Village"),
                create("PLACE", "Tavern", ""","at":{"rel":"NEXT_TO","ref":"${'$'}1"}"""),
                create(
                    "CHARACTER", "Borin",
                    ""","kind":"blacksmith","at":{"rel":"NEXT_TO","ref":"${'$'}1"}""",
                ),
                """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}2","relation":"CONTAINS"}}""",
                """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}3","relation":"CONTAINS"}}""",
            )

            "dungeon" in message -> steps(
                create("PLACE", "Dungeon"),
                create("PLACE", "Room 1", ""","at":{"rel":"SOUTH_OF","ref":"${'$'}1"}"""),
                create("PLACE", "Room 2", ""","at":{"rel":"SOUTH_OF","ref":"${'$'}2"}"""),
                create("CHARACTER", "Dragon", ""","kind":"dragon","at":{"rel":"EAST_OF","ref":"${'$'}3"}"""),
            )

            "frog" in message -> steps(
                *(1..5).map { create("CHARACTER", "Frog $it", ""","kind":"frog"""") }.toTypedArray(),
            )

            "castle" in message && firstId != null -> steps(
                create("PLACE", "Castle", ""","at":{"rel":"NORTH_OF","ref":"$firstId"}"""),
            )

            "vampire" in message -> {
                val target = Regex("^(n\\d+) char", RegexOption.MULTILINE)
                    .find(prompt)?.groupValues?.get(1)
                if (target == null) {
                    steps(respond("I need a character on the board first."))
                } else {
                    steps("""{"tool":"update_node","args":{"node":"$target","set":{"secret":"vampire"}}}""")
                }
            }

            "red" in message && firstId != null ->
                steps("""{"tool":"update_node","args":{"node":"$firstId","color":"RED"}}""")

            "note" in message -> steps(create("NOTE", message.take(40).ifBlank { "note" }))

            else -> steps(
                respond("Try: create a village, make a dungeon, add a castle, or create five frogs."),
            )
        }
    }

    private fun respond(text: String) = """{"tool":"respond","args":{"text":"$text"}}"""
}
