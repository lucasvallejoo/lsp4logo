package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.parser.ParseError
import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Outward-facing diagnostic — a single layer-agnostic representation that
 * combines lexer/parser errors and resolver errors into one stream that the
 * LSP layer can hand straight to `publishDiagnostics`.
 *
 * Why unify? Because the IDE shows one squiggly list to the user; the
 * distinction between "syntax error" and "name resolution error" is internal
 * plumbing. Having one [Diagnostic] type means the LSP server has a single
 * conversion site at the boundary, not several.
 */
data class Diagnostic(
    val message: String,
    val range: Range,
    val severity: Severity,
    /** Free-form provenance string ("parser", "resolver", …). Mirrors LSP's `source`. */
    val source: String,
) {
    enum class Severity { Error, Warning, Information, Hint }

    companion object {
        fun fromParseError(error: ParseError): Diagnostic = Diagnostic(
            message = error.message,
            range = error.range,
            severity = Severity.Error,
            source = "parser",
        )
    }
}
