package dev.droiddoodle.model

/**
 * Time as an injected dependency, per architecture rule R4.
 *
 * Core modules perform no I/O and read no ambient state -- including the system
 * clock. Injecting time is what lets a whole turn, trace timings included, be
 * reproduced byte-for-byte in a test.
 */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        /** Advances by a fixed step on every read, so timings are deterministic. */
        public fun fixed(startMillis: Long = 0L, stepMillis: Long = 1L): Clock {
            var current = startMillis
            return Clock {
                val value = current
                current += stepMillis
                value
            }
        }
    }
}

/** Turn id allocation, injected for the same reason as [Clock]. */
public fun interface IdGenerator {
    public fun next(): String

    public companion object {
        public fun sequential(prefix: String = "turn"): IdGenerator {
            var n = 0
            return IdGenerator { "$prefix-${++n}" }
        }
    }
}
