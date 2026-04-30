package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.parser.ArityTable

/**
 * The catalog of built-in procedures the resolver knows about, with short
 * human-readable descriptions for hover and completion later.
 *
 * Why separate from [ArityTable.BUILT_INS]? Because [ArityTable] only needs the
 * arity number to drive the parser; the resolver layer wants prose for IDE
 * features. Keeping the two side-by-side means we have a single source of
 * truth for *which commands exist* and never disagree on the set.
 */
object BuiltIns {

    /** All built-in symbols, keyed by canonical (upper-case) name. */
    val all: Map<String, Symbol.Procedure.BuiltIn> = listOf(
        // Movement -----------------------------------------------------------
        builtin("FORWARD", 1, "Move the turtle forward by the given distance."),
        builtin("FD", 1, "Alias for FORWARD."),
        builtin("BACK", 1, "Move the turtle backward by the given distance."),
        builtin("BK", 1, "Alias for BACK."),
        builtin("RIGHT", 1, "Rotate the turtle clockwise by the given degrees."),
        builtin("RT", 1, "Alias for RIGHT."),
        builtin("LEFT", 1, "Rotate the turtle counter-clockwise by the given degrees."),
        builtin("LT", 1, "Alias for LEFT."),
        builtin("SETHEADING", 1, "Set the turtle's heading to an absolute angle."),
        builtin("SETH", 1, "Alias for SETHEADING."),
        // Pen state ---------------------------------------------------------
        builtin("PENUP", 0, "Lift the pen — subsequent moves do not draw."),
        builtin("PU", 0, "Alias for PENUP."),
        builtin("PENDOWN", 0, "Lower the pen — subsequent moves draw."),
        builtin("PD", 0, "Alias for PENDOWN."),
        builtin("HIDETURTLE", 0, "Hide the turtle marker."),
        builtin("HT", 0, "Alias for HIDETURTLE."),
        builtin("SHOWTURTLE", 0, "Show the turtle marker."),
        builtin("ST", 0, "Alias for SHOWTURTLE."),
        builtin("HOME", 0, "Move the turtle back to the origin, heading 0°."),
        builtin("CLEARSCREEN", 0, "Clear the canvas and return the turtle to HOME."),
        builtin("CS", 0, "Alias for CLEARSCREEN."),
        // Variables / control -----------------------------------------------
        builtin("MAKE", 2, "Bind a value to a name. Usage: MAKE \"name expression."),
        builtin("STOP", 0, "Return immediately from the current procedure."),
        builtin("OUTPUT", 1, "Return a value from the current procedure."),
        builtin("OP", 1, "Alias for OUTPUT."),
        // I/O ---------------------------------------------------------------
        builtin("PRINT", 1, "Print a value to the console."),
        builtin("PR", 1, "Alias for PRINT."),
    ).associateBy { it.name }

    private fun builtin(name: String, arity: Int, description: String): Symbol.Procedure.BuiltIn =
        Symbol.Procedure.BuiltIn(name = name, arity = arity, description = description)

    fun lookup(name: String): Symbol.Procedure.BuiltIn? = all[name.canonicalName()]
}
