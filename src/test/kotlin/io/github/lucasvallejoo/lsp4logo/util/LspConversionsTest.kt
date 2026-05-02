package io.github.lucasvallejoo.lsp4logo.util

import org.eclipse.lsp4j.Position as LspPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LspConversionsTest {

    @Test fun `start of file maps to offset 0`() {
        assertEquals(0, LspConversions.fromLspPosition("FD 50", LspPosition(0, 0)))
    }

    @Test fun `mid-line column counts in chars`() {
        // "FD 50" — column 3 is the '5'
        assertEquals(3, LspConversions.fromLspPosition("FD 50", LspPosition(0, 3)))
    }

    @Test fun `second line offset accounts for the newline`() {
        val src = "FD 50\nRT 90"
        // line 1, column 0 → after "FD 50\n" (6 chars)
        assertEquals(6, LspConversions.fromLspPosition(src, LspPosition(1, 0)))
    }

    @Test fun `end-of-file position is a valid offset`() {
        val src = "FD 50"
        assertEquals(5, LspConversions.fromLspPosition(src, LspPosition(0, 5)))
    }

    @Test fun `position past end of document yields null`() {
        assertNull(LspConversions.fromLspPosition("FD 50", LspPosition(99, 0)))
    }

    @Test fun `toLspPosition copies fields verbatim`() {
        val p = Position(offset = 42, line = 3, column = 7)
        val lsp = LspConversions.toLspPosition(p)
        assertEquals(3, lsp.line)
        assertEquals(7, lsp.character)
    }

    @Test fun `roundtrip — toLsp then fromLsp via the same source recovers the offset`() {
        val src = "TO SQUARE :SIDE\n  REPEAT 4 [ FD :SIDE ]\nEND"
        // Pick an offset deep into the second line: the ":" of ":SIDE"
        val targetOffset = src.indexOf(":SIDE", startIndex = 20)
        // Walk to find what (line, col) corresponds to that offset
        var line = 0; var col = 0
        for (i in 0 until targetOffset) if (src[i] == '\n') { line++; col = 0 } else col++
        val recovered = LspConversions.fromLspPosition(src, LspPosition(line, col))
        assertEquals(targetOffset, recovered)
    }
}
