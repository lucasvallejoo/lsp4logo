package io.github.lucasvallejoo.lsp4logo.util

/**
 * Zero-based source position.
 *
 * - [offset] is a UTF-16 character offset from the start of the document.
 * - [line] and [column] are zero-based, matching the LSP spec.
 *
 * The triple is redundant on purpose: [offset] is fast for substring extraction,
 * while [line]/[column] map directly to `lsp4j.Position` without recomputation.
 */
data class Position(val offset: Int, val line: Int, val column: Int) {
    companion object {
        val START: Position = Position(offset = 0, line = 0, column = 0)
    }
}

/** Half-open `[start, end)` source range. */
data class Range(val start: Position, val end: Position) {
    val length: Int get() = end.offset - start.offset

    companion object {
        val EMPTY: Range = Range(Position.START, Position.START)
    }
}
