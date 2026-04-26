package io.github.lucasvallejoo.lsp4logo.ast

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Expression nodes. Every expression yields a value at runtime (numbers, words,
 * or the result of a procedure that uses `OUTPUT`).
 *
 * The hierarchy is intentionally small: LOGO's expression sub-language is
 * arithmetic + comparisons + function calls. Listing every variant in one
 * `sealed interface` keeps pattern matching exhaustive across the analyzer.
 */
sealed interface Expr : Node {

    /** Numeric literal (`100`, `0.5`, `.25`). */
    data class NumberLit(val value: Double, override val range: Range) : Expr

    /** Variable reference (`:x`). [name] does *not* include the leading colon. */
    data class VariableRef(val name: String, override val range: Range) : Expr

    /** Word literal (`"x`). [name] does *not* include the leading double-quote. */
    data class WordLit(val name: String, override val range: Range) : Expr

    /** Binary infix expression. [opRange] is preserved separately for diagnostics that point at the operator alone. */
    data class BinaryOp(
        val left: Expr,
        val op: BinOpKind,
        val opRange: Range,
        val right: Expr,
        override val range: Range,
    ) : Expr

    /** `-x`. Distinct from binary subtraction. */
    data class UnaryMinus(val operand: Expr, override val range: Range) : Expr

    /** `(expr)`. Preserved so refactorings round-trip parenthesisation. */
    data class Grouping(val inner: Expr, override val range: Range) : Expr

    /**
     * Procedure call appearing in expression position (its return value is consumed).
     * Resolution of [name] to a declaration happens in the analysis phase.
     */
    data class ProcedureCall(
        val name: NameRef,
        val args: List<Expr>,
        override val range: Range,
    ) : Expr
}
