package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * A recoverable syntactic error.
 *
 * Errors are *collected*, not thrown. The parser synchronises and continues so
 * that a single typo doesn't blank out the rest of the file's editor support.
 */
data class ParseError(val message: String, val range: Range)
