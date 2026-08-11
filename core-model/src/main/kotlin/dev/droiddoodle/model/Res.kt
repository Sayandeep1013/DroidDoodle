package dev.droiddoodle.model

/**
 * A two-type result. Named `Res` rather than `Result` so it never collides with
 * `kotlin.Result`, which is auto-imported everywhere and carries no error type.
 */
public sealed interface Res<out V, out E> {
    public data class Ok<out V>(public val value: V) : Res<V, Nothing>
    public data class Err<out E>(public val error: E) : Res<Nothing, E>

    public val isOk: Boolean get() = this is Ok

    public fun valueOrNull(): V? = (this as? Ok)?.value

    public fun errorOrNull(): E? = (this as? Err)?.error

    public fun <R> map(transform: (V) -> R): Res<R, E> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    public fun <R> flatMap(transform: (V) -> Res<R, @UnsafeVariance E>): Res<R, E> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    public fun <R> mapError(transform: (E) -> R): Res<V, R> = when (this) {
        is Ok -> this
        is Err -> Err(transform(error))
    }

    public companion object {
        public fun <V> ok(value: V): Res<V, Nothing> = Ok(value)
        public fun <E> err(error: E): Res<Nothing, E> = Err(error)
    }
}
