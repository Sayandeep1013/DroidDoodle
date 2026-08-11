package dev.droiddoodle.model

/**
 * A node identifier of the form `n<positive integer>`.
 *
 * Ids are short on purpose. Every id appears in the viewport digest and in every
 * tool argument, so `n7` versus a UUID is a meaningful saving across a full
 * prompt. See docs/20-world-model.md §2.
 *
 * Counters are monotonic per board and never reused: deleting `n7` does not free
 * the id. Reuse would let a stale entry in the reference table or an undo record
 * silently address a different node.
 */
@JvmInline
public value class NodeId(public val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid node id: '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("^n[1-9][0-9]*$")

        public fun of(seq: Int): NodeId {
            require(seq >= 1) { "node sequence must be >= 1, was $seq" }
            return NodeId("n$seq")
        }

        public fun parseOrNull(raw: String): NodeId? =
            if (PATTERN.matches(raw)) NodeId(raw) else null
    }
}

/** An edge identifier of the form `e<positive integer>`. Same reuse rules as [NodeId]. */
@JvmInline
public value class EdgeId(public val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid edge id: '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("^e[1-9][0-9]*$")

        public fun of(seq: Int): EdgeId {
            require(seq >= 1) { "edge sequence must be >= 1, was $seq" }
            return EdgeId("e$seq")
        }

        public fun parseOrNull(raw: String): EdgeId? =
            if (PATTERN.matches(raw)) EdgeId(raw) else null
    }
}

/**
 * A reference to a node in a tool argument: either a node that already exists,
 * or a forward reference to one created earlier in the same plan.
 *
 * Step references are what make multi-node construction possible under
 * plan-then-execute. With no observation feedback, a plan that creates a village
 * and then places a tavern next to it cannot know the village's id; `$1` names
 * it. See docs/21-tools.md §2.
 */
public sealed interface NodeRef {
    public data class Existing(public val id: NodeId) : NodeRef
    /** [step] is 1-indexed and must be strictly less than the referencing step. */
    public data class Step(public val step: Int) : NodeRef

    public companion object {
        public fun parseOrNull(raw: String): NodeRef? = when {
            raw.startsWith("$") -> raw.drop(1).toIntOrNull()
                ?.takeIf { it >= 1 }
                ?.let { Step(it) }
            else -> NodeId.parseOrNull(raw)?.let { Existing(it) }
        }
    }
}
