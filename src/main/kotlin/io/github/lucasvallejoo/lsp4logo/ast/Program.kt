package io.github.lucasvallejoo.lsp4logo.ast

import io.github.lucasvallejoo.lsp4logo.parser.ParseError
import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Procedure declaration: `TO name :p1 :p2 … <body> END`.
 *
 * [nameRange] is preserved separately from [range] so that go-to-declaration
 * can point at the *name* alone, not the whole `TO…END` block.
 */
data class ProcedureDecl(
    val name: String,
    val nameRange: Range,
    val parameters: List<Parameter>,
    val body: List<Stmt>,
    override val range: Range,
) : TopLevel

/**
 * Root of every parsed LOGO source file.
 *
 * [errors] collects every recoverable parse error. The parser keeps going
 * after an error so that the rest of the file still produces a useful AST —
 * critical for an LSP, which wants to highlight, navigate, and analyse code
 * even while the user is mid-edit.
 */
data class Program(
    val items: List<TopLevel>,
    val errors: List<ParseError>,
    override val range: Range,
) : Node
