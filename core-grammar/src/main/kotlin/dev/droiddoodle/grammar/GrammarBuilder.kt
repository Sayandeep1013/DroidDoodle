package dev.droiddoodle.grammar

import dev.droiddoodle.model.ArgType
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeSize
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.ToolPosition
import dev.droiddoodle.model.ToolSchema
import dev.droiddoodle.model.ArrangeLayout

/**
 * Emits a GBNF grammar from the tool registry plus the live board.
 *
 * Three structural properties fall out of the shape of `root`:
 *   - `respond` can only be last, and may also stand alone
 *   - `find` can only be first
 *   - node ids cannot be hallucinated
 *
 * And one thing this deliberately does not do: **GBNF cannot count**, so
 * `max_steps` is not enforceable here. Step-count and `$k` ordering are static
 * validation concerns. Pretending otherwise would be the kind of quiet spec lie
 * that produces a confusing bug later. See docs/25-inference.md §3.
 */
public object GrammarBuilder {

    public fun build(spec: GrammarSpec): String {
        val rules = LinkedHashMap<String, String>()

        val firstOnly = spec.tools.filter { it.position == ToolPosition.FIRST_ONLY }
        val lastOnly = spec.tools.filter { it.position == ToolPosition.LAST_ONLY }
        val anywhere = spec.tools.filter { it.position == ToolPosition.ANY }

        require(anywhere.isNotEmpty()) { "at least one position-free tool is required" }
        require(lastOnly.size <= 1) { "at most one LAST_ONLY tool is supported" }

        // Step 1 is a different world from the rest of the plan: no earlier step
        // has run, so no `$k` reference can resolve. Emitting one shared
        // `noderef` meant the grammar offered the model a reference it was
        // guaranteed to be rejected for, and on an empty board -- where
        // `existing` is empty and `noderef` collapses to steprefs alone -- the
        // grammar offered *nothing else*. Three suite cases died that way; the
        // model was being led somewhere it could only lose.
        //
        // So the first position gets its own rules, built from `noderef-first`,
        // which contains only ids that already exist. Where that leaves a tool
        // with an unsatisfiable required argument, the tool is dropped from the
        // first position entirely. UNRESOLVED_STEP_REF at step 1 is now
        // unrepresentable rather than merely validated against.
        val firstNodeRefs = spec.existingIds.isNotEmpty()
        fun availableFirst(tool: ToolSchema) =
            firstNodeRefs || spec.orderedArgs(tool).none {
                it.type == ArgType.NODE_REF || it.type == ArgType.NODE_REF_LIST
            }

        val anywhereFirst = anywhere.filter(::availableFirst)
        val firstOnlyFirst = firstOnly.filter(::availableFirst)
        require(anywhereFirst.isNotEmpty()) {
            "no tool can occupy the first step; the grammar would admit nothing"
        }

        val stepAlternatives = anywhere.joinToString(" | ") { ruleName(it) }
        val firstAlternatives =
            (firstOnlyFirst.map { ruleName(it) + FIRST } + "step-first").joinToString(" | ")

        val lastRule = lastOnly.firstOrNull()?.let { ruleName(it) }

        rules["root"] = buildString {
            append(lit("{\"steps\":["))
            append(" ( ")
            if (lastRule != null) append("$lastRule | ")
            append("first ( ")
            append(lit(","))
            append(" step )*")
            if (lastRule != null) {
                append(" ( ")
                append(lit(","))
                append(" $lastRule )?")
            }
            append(" ) ")
            append(lit("]}"))
        }
        rules["first"] = firstAlternatives
        rules["step-first"] = anywhereFirst.joinToString(" | ") { ruleName(it) + FIRST }
        rules["step"] = stepAlternatives

        for (tool in spec.tools) {
            rules[ruleName(tool)] = toolRule(spec, tool)
        }
        for (tool in (anywhereFirst + firstOnlyFirst)) {
            rules[ruleName(tool) + FIRST] = toolRule(spec, tool, FIRST)
        }

        rules.putAll(terminalRules(spec, firstNodeRefs))

        return rules.entries.joinToString("\n") { (name, body) -> "$name ::= $body" } + "\n"
    }

    private fun ruleName(tool: ToolSchema): String = "tool-" + tool.name.replace('_', '-')

    /** Suffix marking the first-position variant of a rule. */
    private const val FIRST = "-first"

    private fun toolRule(spec: GrammarSpec, tool: ToolSchema, suffix: String = ""): String {
        val args = spec.orderedArgs(tool)
        val required = args.filter { it.required }
        val optional = args.filter { !it.required }

        val body = when {
            args.isEmpty() -> ""

            required.isNotEmpty() -> {
                // A comma always precedes an optional argument, because at least
                // one required argument was emitted before it.
                val head = required.joinToString(" ${lit(",")} ") { kv(it, suffix) }
                val tail = optional.joinToString("") { " ( ${lit(",")} ${kv(it, suffix)} )?" }
                head + tail
            }

            else -> {
                // All arguments optional. Independent `("," kv)?` groups would
                // emit a leading comma whenever the first argument is skipped, so
                // the alternatives are expanded by suffix instead: one branch per
                // possible first-present argument.
                //
                // The empty branch is deliberately omitted, which makes "at
                // least one argument" a grammar-level guarantee rather than a
                // validation-time one. `find` is the tool this exists for.
                optional.indices.joinToString(" | ") { start ->
                    val head = kv(optional[start], suffix)
                    val tail = optional.drop(start + 1)
                        .joinToString("") { " ( ${lit(",")} ${kv(it, suffix)} )?" }
                    "( $head$tail )"
                }
            }
        }

        // The body is parenthesised unconditionally, and this is load-bearing.
        //
        // In GBNF `|` binds looser than concatenation, so an unbracketed
        // alternation splits the *whole* rule rather than just the body:
        //
        //   tool-find ::= "{...{" ( A ) | ( B ) | ( C ) "}}"
        //
        // means ( "{...{" A ) | ( B ) | ( C "}}" ). The middle alternatives are
        // bare argument fragments with no braces, so the sampler will happily
        // emit `"type":"OBJECT"` straight into the steps array. That is not a
        // hypothetical: it produced every grammar violation in the first
        // on-device run, and because the grammar still *parsed*, generation
        // silently degraded instead of failing.
        return lit("{\"tool\":\"${tool.name}\",\"args\":{") +
            (if (body.isEmpty()) "" else " ( $body ) ") +
            lit("}}")
    }

    private fun kv(arg: dev.droiddoodle.model.ArgSpec, suffix: String = ""): String =
        "${lit("\"${arg.name}\":")} ${typeRule(arg.type, suffix)}"

    private fun typeRule(type: ArgType, suffix: String = ""): String = when (type) {
        ArgType.STRING -> "string"
        ArgType.NODE_REF -> "noderef$suffix"
        ArgType.NODE_REF_LIST -> "noderef-list$suffix"
        ArgType.NODE_TYPE -> "nodetype"
        ArgType.EDGE_TYPE -> "edgetype"
        ArgType.COLOR -> "color"
        ArgType.SIZE -> "size"
        ArgType.PLACEMENT -> "placement$suffix"
        ArgType.ARRANGE_LAYOUT -> "layout"
        ArgType.ATTR_MAP -> "attrmap"
        ArgType.STRING_LIST -> "string-list"
        ArgType.SETTING_KEY -> "settingkey"
        ArgType.SETTING_VALUE -> "string"
    }

    private fun terminalRules(spec: GrammarSpec, firstNodeRefs: Boolean): Map<String, String> {
        val rules = LinkedHashMap<String, String>()

        val stepRefs = (1 until spec.maxSteps.coerceAtLeast(2))
            .joinToString(" | ") { lit("\$$it") }
        rules["stepref"] = stepRefs

        if (spec.existingIds.isNotEmpty()) {
            rules["existing"] = spec.existingIds
                .sortedBy { it.value.drop(1).toInt() }
                .joinToString(" | ") { lit(it.value) }
            rules["noderef"] = "${lit("\"")} ( existing | stepref ) ${lit("\"")}"
        } else {
            rules["noderef"] = "${lit("\"")} stepref ${lit("\"")}"
        }

        rules["noderef-list"] =
            "${lit("[")} noderef ( ${lit(",")} noderef )* ${lit("]")}"

        // The first position sees existing ids only. On an empty board there is
        // nothing to see, and the rule is omitted along with every tool that
        // would have needed it.
        if (firstNodeRefs) {
            rules["noderef$FIRST"] = "${lit("\"")} existing ${lit("\"")}"
            rules["noderef-list$FIRST"] =
                "${lit("[")} noderef$FIRST ( ${lit(",")} noderef$FIRST )* ${lit("]")}"
        }

        rules["nodetype"] = NodeType.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["edgetype"] = EdgeType.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["color"] = NodeColor.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["size"] = NodeSize.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["layout"] = ArrangeLayout.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["relation"] = Relation.entries.joinToString(" | ") { lit("\"${it.name}\"") }

        val absolutePlacements = listOf(
            "${lit("{\"cell\":{\"row\":")} int ${lit(",\"col\":")} int ${lit("}}")}",
            lit("{\"auto\":true}"),
        )
        val relative = { ref: String ->
            "${lit("{\"rel\":")} relation ${lit(",\"ref\":")} $ref ${lit("}")}"
        }
        rules["placement"] = (listOf(relative("noderef")) + absolutePlacements)
            .joinToString(" | ")

        // "north of $1" at step 1 is the trap in its most tempting form: asked
        // to create something on an empty board, a model reaches for a relative
        // placement and there is nothing to be relative to. Here it can only
        // choose a cell or `auto`, both of which succeed.
        rules["placement$FIRST"] = if (firstNodeRefs) {
            (listOf(relative("noderef$FIRST")) + absolutePlacements).joinToString(" | ")
        } else {
            absolutePlacements.joinToString(" | ")
        }

        // set_setting's key domain is exactly the agent-writable keys, so a write
        // to a protected setting cannot be emitted at all.
        rules["settingkey"] = spec.agentWritableSettingKeys
            .joinToString(" | ") { lit("\"$it\"") }

        rules["attrmap"] =
            "${lit("{")} ( pair ( ${lit(",")} pair )* )? ${lit("}")}"
        rules["pair"] = "string ${lit(":")} string"
        rules["string-list"] =
            "${lit("[")} ( string ( ${lit(",")} string )* )? ${lit("]")}"

        rules["string"] = "${lit("\"")} char* ${lit("\"")}"
        rules["char"] = "[^\"\\\\] | ${lit("\\")} [\"\\\\/bfnrt]"
        rules["int"] = "${lit("-")}? [0-9]+"

        return rules
    }

    /** Wraps a raw JSON fragment as a GBNF string literal, escaping as required. */
    private fun lit(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
