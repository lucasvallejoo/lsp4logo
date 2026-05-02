package io.github.lucasvallejoo.lsp4logo.analysis.turtle

import io.github.lucasvallejoo.lsp4logo.analysis.ResolvedProgram
import io.github.lucasvallejoo.lsp4logo.analysis.canonicalName
import io.github.lucasvallejoo.lsp4logo.ast.BinOpKind
import io.github.lucasvallejoo.lsp4logo.ast.Expr
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Stmt

/**
 * Computes the symbolic turtle state after each statement of a LOGO program.
 *
 * **What it does well**
 *  - Linear arithmetic in parameters: `FORWARD :SIDE`, `BACK :SIZE * 0.7`, `RIGHT :A + 30`.
 *  - Concrete heading combined with symbolic distance: `FD :SIDE` after `RT 90` is
 *    handled correctly because `sin(90°) = 1` is concrete and `:SIDE · 1 = :SIDE`.
 *  - `REPEAT n [body]` with concrete `n` (up to [maxRepeatExpansion]) is unrolled
 *    literally — this is what makes "this REPEAT closes the shape" detectable
 *    without any geometry magic: if the unrolled iterations bring the turtle
 *    back to its starting state, the formatter can simply say "home".
 *  - `IF cond [body]` with `STOP`/`OUTPUT` in the body: state after the IF
 *    equals state before, modelling the canonical "guard then exit" pattern.
 *  - Top-level `MAKE "name <concrete>` registers a known global value so
 *    later references like `:depth` resolve to a number, not Unknown.
 *
 * **What it deliberately does NOT do**
 *  - Nonlinear arithmetic (parameter × parameter, sin of a parameter): degraded
 *    gracefully to [SymbolicValue.Unknown].
 *  - Inlining user-defined procedure calls: their effect is conservatively
 *    [TurtleState.UNKNOWN] after the call. Inlining would require a fixed-point
 *    over recursion and is out of scope for this assignment.
 *  - Branch merging across IF when the body has no STOP/OUTPUT: state is
 *    conservatively Unknown.
 *
 * The interpreter is a *pure function* of the [ResolvedProgram] it was built
 * with — no I/O, no clock, no globals. Two calls with the same input produce
 * the same output.
 */
class AbstractInterpreter(
    private val resolved: ResolvedProgram,
    private val maxRepeatExpansion: Int = 100,
) {

    /** A point at which the editor can show an inlay hint with the post-statement state. */
    data class HintEntry(val statement: Stmt, val stateAfter: TurtleState)

    /** Result of tracing a contiguous block of statements. */
    private data class TraceResult(
        val finalState: TurtleState,
        val hints: List<HintEntry>,
        /** True if the block exited early via STOP/OUTPUT — the caller must respect this. */
        val terminatedEarly: Boolean,
    )

    /** Pre-computed values of top-level globals introduced by `MAKE "x <concrete>`. */
    private val knownGlobals: Map<String, SymbolicValue> = computeKnownGlobals()

    /** Public entry point: trace every top-level statement of the program. */
    fun traceTopLevel(): List<HintEntry> =
        traceBlock(
            stmts = resolved.program.items.filterIsInstance<Stmt>(),
            initial = TurtleState.initial(),
            owner = null,
        ).hints

    /** Public entry point: trace one user-declared procedure body, parameters symbolic. */
    fun traceProcedure(decl: ProcedureDecl): List<HintEntry> =
        traceBlock(
            stmts = decl.body,
            initial = TurtleState.initial(),
            owner = decl,
        ).hints

    // ---- block tracing ---------------------------------------------------

    private fun traceBlock(stmts: List<Stmt>, initial: TurtleState, owner: ProcedureDecl?): TraceResult {
        var state = initial
        val hints = mutableListOf<HintEntry>()
        for (stmt in stmts) {
            if (isStopOrOutput(stmt)) {
                hints.add(HintEntry(stmt, state))
                return TraceResult(state, hints.toList(), terminatedEarly = true)
            }
            val (next, _) = step(stmt, state, owner, hints)
            state = next
        }
        return TraceResult(state, hints.toList(), terminatedEarly = false)
    }

    private fun step(
        stmt: Stmt,
        before: TurtleState,
        owner: ProcedureDecl?,
        hintsOut: MutableList<HintEntry>,
    ): Pair<TurtleState, Boolean> {
        val after: TurtleState = when (stmt) {
            is Stmt.ProcedureCall -> applyCall(stmt, before, owner)
            is Stmt.Repeat -> applyRepeat(stmt, before, owner, hintsOut)
            is Stmt.If -> applyIf(stmt, before, owner, hintsOut)
        }
        hintsOut.add(HintEntry(stmt, after))
        return after to false
    }

    private fun isStopOrOutput(stmt: Stmt): Boolean =
        stmt is Stmt.ProcedureCall && stmt.name.name.canonicalName().let { it == "STOP" || it == "OUTPUT" }

    // ---- built-ins -------------------------------------------------------

    private fun applyCall(call: Stmt.ProcedureCall, s: TurtleState, owner: ProcedureDecl?): TurtleState {
        val name = call.name.name.canonicalName()
        val args = call.args.map { evalExpr(it, owner) }
        return when (name) {
            "FORWARD", "FD" -> moveBy(s, args[0])
            "BACK", "BK" -> moveBy(s, -args[0])
            "RIGHT", "RT" -> s.copy(heading = normaliseAngle(s.heading + args[0]))
            "LEFT", "LT" -> s.copy(heading = normaliseAngle(s.heading - args[0]))
            "SETHEADING", "SETH" -> s.copy(heading = normaliseAngle(args[0]))
            "PENUP", "PU" -> s.copy(penDown = false)
            "PENDOWN", "PD" -> s.copy(penDown = true)
            "HOME", "CLEARSCREEN", "CS" -> TurtleState.initial().copy(penDown = s.penDown)
            "MAKE", "PRINT", "PR", "STOP", "OUTPUT", "OP",
            "HIDETURTLE", "HT", "SHOWTURTLE", "ST" -> s
            else -> TurtleState.UNKNOWN.copy(penDown = s.penDown) // user-defined: conservative
        }
    }

    private fun moveBy(s: TurtleState, distance: SymbolicValue): TurtleState {
        val dx = distance * sinDeg(s.heading)
        val dy = distance * cosDeg(s.heading)
        return s.copy(x = s.x + dx, y = s.y + dy)
    }

    /**
     * Snap heading into `[0, 360)` *only when concrete*; otherwise leave it as
     * a linear/Unknown value. The visual hint is the same either way; this
     * keeps `RIGHT 360` displayed as `h=0°` instead of `h=360°`.
     */
    private fun normaliseAngle(v: SymbolicValue): SymbolicValue = when (v) {
        is SymbolicValue.Concrete -> {
            val mod = v.value.mod(360.0)
            SymbolicValue.Concrete(if (mod < 0) mod + 360.0 else mod)
        }
        else -> v
    }

    // ---- REPEAT ----------------------------------------------------------

    private fun applyRepeat(
        rep: Stmt.Repeat,
        before: TurtleState,
        owner: ProcedureDecl?,
        hintsOut: MutableList<HintEntry>,
    ): TurtleState {
        val countVal = evalExpr(rep.count, owner)
        val n = (countVal as? SymbolicValue.Concrete)?.value?.toInt()

        if (n == null || n < 0 || n > maxRepeatExpansion) {
            // Trace one iteration so hints *inside* the body still appear; the
            // post-block state, however, is conservatively Unknown.
            val once = traceBlock(rep.body, before, owner)
            hintsOut.addAll(once.hints)
            return TurtleState.UNKNOWN.copy(penDown = before.penDown)
        }
        if (n == 0) return before

        // First iteration emits the hints visible to the editor.
        val first = traceBlock(rep.body, before, owner)
        hintsOut.addAll(first.hints)

        // Iterations 2..n only advance the state — emitting their hints would
        // drown the editor with copies of the same lines.
        var state = first.finalState
        repeat(n - 1) {
            state = traceBlock(rep.body, state, owner).finalState
        }
        return state
    }

    // ---- IF --------------------------------------------------------------

    private fun applyIf(
        ifs: Stmt.If,
        before: TurtleState,
        owner: ProcedureDecl?,
        hintsOut: MutableList<HintEntry>,
    ): TurtleState {
        // Always trace the body (for the hints inside it), but discard the
        // resulting state unless the body is purely a STOP/OUTPUT guard.
        val trace = traceBlock(ifs.body, before, owner)
        hintsOut.addAll(trace.hints)
        return if (trace.terminatedEarly) before else TurtleState.UNKNOWN.copy(penDown = before.penDown)
    }

    // ---- expression evaluation ------------------------------------------

    private fun evalExpr(expr: Expr, owner: ProcedureDecl?): SymbolicValue = when (expr) {
        is Expr.NumberLit -> SymbolicValue.Concrete(expr.value)
        is Expr.WordLit -> SymbolicValue.Unknown
        is Expr.VariableRef -> evalVariable(expr.name, owner)
        is Expr.UnaryMinus -> -evalExpr(expr.operand, owner)
        is Expr.Grouping -> evalExpr(expr.inner, owner)
        is Expr.BinaryOp -> evalBinop(expr, owner)
        is Expr.ProcedureCall -> SymbolicValue.Unknown // user-procedure return values are opaque
    }

    private fun evalVariable(name: String, owner: ProcedureDecl?): SymbolicValue {
        val canonical = name.canonicalName()
        if (owner != null) {
            val isParam = resolved.parametersByOwner[owner]?.any { it.name == canonical } == true
            if (isParam) return SymbolicValue.param(canonical)
        }
        return knownGlobals[canonical] ?: SymbolicValue.Unknown
    }

    private fun evalBinop(b: Expr.BinaryOp, owner: ProcedureDecl?): SymbolicValue {
        val l = evalExpr(b.left, owner)
        val r = evalExpr(b.right, owner)
        return when (b.op) {
            BinOpKind.Add -> l + r
            BinOpKind.Sub -> l - r
            BinOpKind.Mul -> l * r
            BinOpKind.Div -> l / r
            BinOpKind.Eq, BinOpKind.Lt, BinOpKind.Gt,
            BinOpKind.Le, BinOpKind.Ge, BinOpKind.Ne -> SymbolicValue.Unknown
        }
    }

    // ---- known globals ---------------------------------------------------

    private fun computeKnownGlobals(): Map<String, SymbolicValue> {
        // Walk top-level MAKE "name <expr> statements; if expr is concrete,
        // remember the value. Reassignments of a name we already know mark
        // it Unknown (we cannot reason about ordering across procedure calls).
        val out = HashMap<String, SymbolicValue>()
        val killed = HashSet<String>()
        for (item in resolved.program.items) {
            if (item !is Stmt.ProcedureCall) continue
            if (item.name.name.canonicalName() != "MAKE") continue
            val target = item.args.getOrNull(0) as? Expr.WordLit ?: continue
            val canonical = target.name.canonicalName()
            if (canonical in killed) continue
            val expr = item.args.getOrNull(1) ?: continue
            val v = evalExpr(expr, owner = null)
            if (canonical in out) {
                // Reassignment with a different value (or any reassignment to be safe):
                // we no longer trust the "global is constant" assumption.
                if (out[canonical] != v) {
                    out.remove(canonical); killed += canonical
                }
            } else if (v is SymbolicValue.Concrete) {
                out[canonical] = v
            }
        }
        return out
    }
}
