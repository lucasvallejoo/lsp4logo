package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import org.eclipse.lsp4j.Position as LspPosition
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionTest {

    private val uri = URI.create("file:///tmp/x.logo")

    private fun snap(src: String): DocumentSnapshot = DocumentSnapshot.analyse(uri, version = 1, source = src)

    /**
     * Locate the (line, column) of the first occurrence of [needle] in [src]
     * so tests can address source positions without offset arithmetic.
     */
    private fun pos(src: String, needle: String): LspPosition {
        val offset = src.indexOf(needle).also { check(it >= 0) { "needle '$needle' not in source" } }
        var line = 0; var col = 0
        for (i in 0 until offset) if (src[i] == '\n') { line++; col = 0 } else col++
        return LspPosition(line, col)
    }

    // ---------- procedure references ---------------------------------------

    @Test fun `cursor on procedure call jumps to its declaration name`() {
        val src = """
            TO SQUARE :SIDE
              FD :SIDE
            END
            SQUARE 100
        """.trimIndent()
        val s = snap(src)

        // Cursor on the "SQUARE" call site
        val callPos = pos(src, "SQUARE 100")
        val locs = Definition.locate(s, callPos)
        assertEquals(1, locs.size)
        // The target range should equal the declaration's name range,
        // i.e. point at the SQUARE in "TO SQUARE :SIDE".
        val declLine = 0
        assertEquals(declLine, locs[0].range.start.line)
        // Column of "SQUARE" in "TO SQUARE :SIDE" is 3.
        assertEquals(3, locs[0].range.start.character)
        assertEquals(uri.toString(), locs[0].uri)
    }

    @Test fun `cursor on built-in returns no location`() {
        val src = "FD 50"
        val s = snap(src)
        val locs = Definition.locate(s, pos(src, "FD"))
        assertTrue(locs.isEmpty())
    }

    // ---------- variable references ----------------------------------------

    @Test fun `cursor on parameter reference jumps to its declaration`() {
        val src = """
            TO F :SIZE
              FD :SIZE
            END
        """.trimIndent()
        val s = snap(src)
        val locs = Definition.locate(s, pos(src, ":SIZE\nEND".substringBefore('\n')))
        // Note: pos picks the *first* occurrence of ":SIZE" — that is the parameter
        // declaration site itself. Definition on a declaration should still
        // resolve to itself (a binding exists at the declaration range).
        assertEquals(1, locs.size)
        // Now query the *use* site
        val useLine = 1
        val useCol = src.lines()[1].indexOf(":SIZE")
        val useLocs = Definition.locate(s, LspPosition(useLine, useCol))
        assertEquals(1, useLocs.size)
        // Both should resolve to the same target — the param's declarationRange (line 0).
        assertEquals(0, useLocs[0].range.start.line)
    }

    @Test fun `cursor on global variable reference jumps to MAKE`() {
        val src = """
            MAKE "depth 5
            FD :depth
        """.trimIndent()
        val s = snap(src)
        val useLine = 1
        val useCol = src.lines()[1].indexOf(":depth")
        val locs = Definition.locate(s, LspPosition(useLine, useCol))
        assertEquals(1, locs.size)
        // Target: the "depth" word literal on line 0
        assertEquals(0, locs[0].range.start.line)
    }

    // ---------- nothing-at-position ---------------------------------------

    @Test fun `cursor on whitespace returns no location`() {
        val src = "FD  50" // two spaces
        val s = snap(src)
        val locs = Definition.locate(s, LspPosition(0, 3))
        assertTrue(locs.isEmpty())
    }

    @Test fun `cursor on number literal returns no location`() {
        val src = "FD 50"
        val s = snap(src)
        val locs = Definition.locate(s, LspPosition(0, 3))
        assertTrue(locs.isEmpty())
    }

    @Test fun `cursor outside the document returns no location`() {
        val src = "FD 50"
        val s = snap(src)
        val locs = Definition.locate(s, LspPosition(99, 0))
        assertTrue(locs.isEmpty())
    }
}
