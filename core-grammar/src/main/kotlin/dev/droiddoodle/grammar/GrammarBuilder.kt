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

        val stepAlternatives = anywhere.joinToString(" | ") { ruleName(it) }
        val firstAlternatives = (firstOnly.map { ruleName(it) } + "step").joinToString(" | ")

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
        rules["step"] = stepAlternatives

        for (tool in spec.tools) {
            rules[ruleName(tool)] = toolRule(spec, tool)
        }

        rules.putAll(terminalRules(spec))

        return rules.entries.joinToString("\n") { (name, body) -> "$name ::= $body" } + "\n"
    }

    private fun ruleName(tool: ToolSchema): String = "tool-" + tool.name.replace('_', '-')

    private fun toolRule(spec: GrammarSpec, tool: ToolSchema): String {
        val args = spec.orderedArgs(tool)
        val required = args.filter { it.required }
        val optional = args.filter { !it.required }

        val body = when {
            args.isEmpty() -> ""

            required.isNotEmpty() -> {
                // A comma always precedes an optional argument, because at least
                // one required argument was emitted before it.
                val head = required.joinToString(" ${lit(",")} ") { kv(it) }
                val tail = optional.joinToString("") { " ( ${lit(",")} ${kv(it)} )?" }
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
                    val head = kv(optional[start])
                    val tail = optional.drop(start + 1)
                        .joinToString("") { " ( ${lit(",")} ${kv(it)} )?" }
                    "( $head$tail )"
                }
            }
        }

        return lit("{\"tool\":\"${tool.name}\",\"args\":{") +
            (if (body.isEmpty()) "" else " $body ") +
            lit("}}")
    }

    private fun kv(arg: dev.droiddoodle.model.ArgSpec): String =
        "${lit("\"${arg.name}\":")} ${typeRule(arg.type)}"

    private fun typeRule(type: ArgType): String = when (type) {
        ArgType.STRING -> "string"
        ArgType.NODE_REF -> "noderef"
        ArgType.NODE_REF_LIST -> "noderef-list"
        ArgType.NODE_TYPE -> "nodetype"
        ArgType.EDGE_TYPE -> "edgetype"
        ArgType.COLOR -> "color"
        ArgType.SIZE -> "size"
        ArgType.PLACEMENT -> "placement"
        ArgType.ARRANGE_LAYOUT -> "layout"
        ArgType.ATTR_MAP -> "attrmap"
        ArgType.STRING_LIST -> "string-list"
        ArgType.SETTING_KEY -> "settingkey"
        ArgType.SETTING_VALUE -> "string"
    }

    private fun terminalRules(spec: GrammarSpec): Map<String, String> {
        val rules = LinkedHashMap<String, String>()

        // On an empty board `existing` has no alternatives, so it is omitted and
        // noderef reduces to step references alone. A step-1 reference is then
        // caught by static validation as UNRESOLVED_STEP_REF -- conditioning
        // tool availability on step position is not expressible in GBNF.
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

        rules["nodetype"] = NodeType.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["edgetype"] = EdgeType.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["color"] = NodeColor.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["size"] = NodeSize.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["layout"] = ArrangeLayout.entries.joinToString(" | ") { lit("\"${it.name}\"") }
        rules["relation"] = Relation.entries.joinToString(" | ") { lit("\"${it.name}\"") }

        rules["placement"] = listOf(
            "${lit("{\"rel\":")} relation ${lit(",\"ref\":")} noderef ${lit("}")}",
            "${lit("{\"cell\":{\"row\":")} int ${lit(",\"col\":")} int ${lit("}}")}",
            lit("{\"auto\":true}"),
        ).joinToString(" | ")

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
