package dev.droiddoodle.inference

/**
 * Turn delimiters an instruct-tuned model expects around the assembled context.
 *
 * This lives here rather than in `:core-agent` because it is model knowledge,
 * and intent criterion L3 says the agent knows nothing about models beyond
 * [LlmEngine]. The engine applies the template; the agent hands over a plain
 * assembled prompt and never learns which delimiters were used.
 *
 * A mismatched template degrades output in ways easily mistaken for the model
 * being weak, which is why the template in use is recorded in every trace
 * (docs/25-inference.md §6).
 *
 * The whole assembled context goes in the user turn. None of these templates
 * gain anything from a separate system turn here -- Gemma has no system role at
 * all -- and one turn keeps the KV prefix simple.
 *
 * No template emits a leading BOS token. Tokenisation is done with
 * `add_special = true`, which adds the right one (or none, for ChatML models
 * whose tokeniser sets `add_bos_token=false`). Writing BOS into the text as
 * well would double it.
 */
public enum class PromptTemplate(public val key: String) {

    /** Qwen, and most of the OpenAI-derived instruct formats. */
    CHATML("chatml"),

    /** Llama 3.x header blocks. */
    LLAMA3("llama3"),

    /** Gemma 2 and 3. Note `model`, not `assistant`, for the reply turn. */
    GEMMA("gemma"),

    /** Base models, and a control condition for measuring template effect. */
    PLAIN("plain"),
    ;

    /** Wraps [prompt] and opens the assistant turn, so decoding continues it. */
    public fun wrap(prompt: String): String = when (this) {
        CHATML ->
            "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
        LLAMA3 ->
            "<|start_header_id|>user<|end_header_id|>\n\n$prompt<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n"
        GEMMA ->
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        PLAIN ->
            prompt
    }

    /**
     * The template's own text, with no prompt in it. The engine tokenises this
     * once at load and subtracts it from the reported [LlmEngine.contextTokens],
     * so the agent's context budget is not quietly overspent by the delimiters.
     */
    public fun envelope(): String = wrap("")

    public companion object {
        /** Null for an unknown key, so a bad manifest is a rejected entry. */
        public fun fromKey(key: String): PromptTemplate? =
            entries.firstOrNull { it.key == key }
    }
}
