package io.github.lucasvallejoo.lsp4logo.analysis.turtle

/**
 * Snapshot of the turtle as the abstract interpreter sees it.
 *
 * Coordinate convention follows classic LOGO:
 *  - `(0, 0)` is HOME, the origin.
 *  - heading `0°` points up the +Y axis (north).
 *  - heading increases clockwise: `90°` is east, `180°` is south, `270°` is west.
 *  - `FORWARD d` moves by `(d · sin(h), d · cos(h))`.
 *
 * Why expose `penDown`? Because a future "geometry-aware diagnostic" wants to
 * know whether moves actually drew. We track it now so the data is there if
 * we ever turn it on; the inlay-hint formatter currently ignores it.
 */
data class TurtleState(
    val x: SymbolicValue,
    val y: SymbolicValue,
    val heading: SymbolicValue,
    val penDown: Boolean = true,
) {

    /** Render as inlay-hint label text: ` x=…  y=…  h=…° `. */
    fun format(): String {
        if (this == initial()) return "home"
        val sb = StringBuilder()
        sb.append("x=").append(x.format())
        sb.append("  y=").append(y.format())
        sb.append("  h=").append(heading.format()).append("°")
        return sb.toString()
    }

    /** Whether anything in this state is [SymbolicValue.Unknown]. */
    val isFullyKnown: Boolean
        get() = x !is SymbolicValue.Unknown && y !is SymbolicValue.Unknown && heading !is SymbolicValue.Unknown

    companion object {
        fun initial(): TurtleState = TurtleState(
            x = SymbolicValue.Concrete(0.0),
            y = SymbolicValue.Concrete(0.0),
            heading = SymbolicValue.Concrete(0.0),
            penDown = true,
        )

        val UNKNOWN: TurtleState = TurtleState(
            x = SymbolicValue.Unknown,
            y = SymbolicValue.Unknown,
            heading = SymbolicValue.Unknown,
        )
    }
}
