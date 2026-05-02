package io.github.lucasvallejoo.lsp4logo.util

import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange

/**
 * Converters between our internal source-position model and the LSP4J
 * representation. Kept in one tiny file so that the boundary is obvious.
 *
 * Both sides of the boundary use the same conventions:
 *  - zero-based line numbers
 *  - zero-based "character" column counts in **UTF-16 code units**
 *
 * (UTF-16 is what the LSP spec mandates. For pure-ASCII LOGO the distinction
 *  is invisible, but the counting helpers below are written so that
 *  multi-byte characters do not silently break offset arithmetic.)
 */
object LspConversions {

    fun toLspPosition(p: Position): LspPosition = LspPosition(p.line, p.column)

    fun toLspRange(r: Range): LspRange = LspRange(toLspPosition(r.start), toLspPosition(r.end))

    /**
     * Convert a (zero-based line, zero-based character) LSP position into a
     * UTF-16 character offset within [source]. Returns `null` when the
     * position falls outside the document.
     *
     * Counts are done in `Char` units (which are UTF-16 code units in the JVM),
     * matching the LSP spec exactly.
     */
    fun fromLspPosition(source: String, lspPos: LspPosition): Int? {
        val targetLine = lspPos.line
        val targetChar = lspPos.character
        var line = 0
        var col = 0
        var i = 0
        while (i < source.length) {
            if (line == targetLine && col == targetChar) return i
            val c = source[i]
            if (c == '\n') {
                if (line == targetLine) {
                    // requested column is past the end of this line; treat
                    // it as the line's terminator position
                    return i
                }
                line++
                col = 0
            } else {
                col++
            }
            i++
        }
        // End-of-file position: allow it as a valid offset.
        return if (line == targetLine && col == targetChar) i else null
    }
}
