package io.github.lucasvallejoo.lsp4logo.ast

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Statement nodes.
 *
 * Only [Repeat] and [If] have first-class structural representation because
 * they take a *block* (`[ … ]`), which is not an [Expr]. Every other LOGO
 * "command" — `FORWARD`, `MAKE`, `STOP`, user-defined procedures — collapses
 * into a single uniform [ProcedureCall]. That uniformity buys us a lot:
 * one resolution rule, one inlay-hint visitor, one refactoring target.
 */
sealed interface Stmt : TopLevel {

    /** `REPEAT count [ body ]`. */
    data class Repeat(
        val count: Expr,
        val body: List<Stmt>,
        override val range: Range,
    ) : Stmt

    /** `IF condition [ body ]`. (No `ELSE` in this subset.) */
    data class If(
        val condition: Expr,
        val body: List<Stmt>,
        override val range: Range,
    ) : Stmt

    /**
     * Any procedure call used as a statement (built-in or user-defined).
     *
     * Examples: `FORWARD :x`, `MAKE "y 10`, `STOP`, `SQUARE 50`, `TREE :d :s`.
     * The semantic difference between built-ins and user procedures is resolved
     * in the analysis phase via the [io.github.lucasvallejoo.lsp4logo.parser.ArityTable].
     */
    data class ProcedureCall(
        val name: NameRef,
        val args: List<Expr>,
        override val range: Range,
    ) : Stmt
}
