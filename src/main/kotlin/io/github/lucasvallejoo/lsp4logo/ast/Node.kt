package io.github.lucasvallejoo.lsp4logo.ast

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Common shape of every AST node.
 *
 * Carrying [range] on every node is *not* optional for an LSP: every feature
 * (go-to-declaration, hover, semantic tokens, change-signature) is fundamentally
 * a question of "what is at this source position?".
 */
sealed interface Node {
    val range: Range
}

/** Things that may appear at the top level of a LOGO program. */
sealed interface TopLevel : Node

/** Reference to a named entity (procedure name, variable name, …) with its source range. */
data class NameRef(val name: String, override val range: Range) : Node

/** A formal parameter on a procedure declaration. The name is stored without the leading `':'`. */
data class Parameter(val name: String, override val range: Range) : Node

/** Binary operator kinds. Source spelling is preserved separately on [Expr.BinaryOp]. */
enum class BinOpKind(val spelling: String) {
    Add("+"), Sub("-"), Mul("*"), Div("/"),
    Eq("="), Lt("<"), Gt(">"), Le("<="), Ge(">="), Ne("<>"),
}
