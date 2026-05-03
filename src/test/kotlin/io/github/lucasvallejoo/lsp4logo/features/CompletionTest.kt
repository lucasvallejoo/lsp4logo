package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position as LspPosition
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionTest {

    private val uri = URI.create("file:///tmp/x.logo")
    private fun snap(src: String): DocumentSnapshot = DocumentSnapshot.analyse(uri, version = 1, source = src)

    /** Locate the (line, character) of the first occurrence of [needle] in [src]. */
    private fun pos(src: String, needle: String, offsetIntoNeedle: Int = needle.length): LspPosition {
        val idx = src.indexOf(needle).also { check(it >= 0) }
        val target = idx + offsetIntoNeedle
        var line = 0; var col = 0
        for (i in 0 until target) if (src[i] == '\n') { line++; col = 0 } else col++
        return LspPosition(line, col)
    }

    // ---------- procedures ------------------------------------------------

    @Test fun `completion at start of empty file suggests built-in procedures`() {
        val items = Completion.compute(snap(""), LspPosition(0, 0))
        val labels = items.map { it.label }.toSet()
        assertTrue("FORWARD" in labels, "expected FORWARD in completion, got $labels")
        assertTrue("MAKE" in labels)
        assertTrue("HOME" in labels)
    }

    @Test fun `completion includes user-defined procedures`() {
        val src = "TO SQUARE :SIDE END\n"
        val items = Completion.compute(snap(src), LspPosition(1, 0))
        assertTrue(
            items.any { it.label == "SQUARE" && it.kind == CompletionItemKind.Function },
            "expected SQUARE function item, got ${items.map { it.label to it.kind }}",
        )
    }

    @Test fun `procedure detail shows arity for built-ins`() {
        val items = Completion.compute(snap(""), LspPosition(0, 0))
        val home = items.first { it.label == "HOME" }
        val forward = items.first { it.label == "FORWARD" }
        val make = items.first { it.label == "MAKE" }
        assertEquals("(no args)", home.detail)
        assertEquals("(1 arg)", forward.detail)
        assertEquals("(2 args)", make.detail)
    }

    @Test fun `procedure detail shows parameter names for user procedures`() {
        val src = "TO TREE :DEPTH :SIZE END\n"
        val items = Completion.compute(snap(src), LspPosition(1, 0))
        val tree = items.first { it.label == "TREE" }
        assertEquals("(:DEPTH :SIZE)", tree.detail)
    }

    @Test fun `built-in documentation is the description from the catalog`() {
        val items = Completion.compute(snap(""), LspPosition(0, 0))
        val fd = items.first { it.label == "FORWARD" }
        val doc = fd.documentation.left
        assertTrue(doc.contains("forward"), "expected description, got '$doc'")
    }

    // ---------- variables -------------------------------------------------

    @Test fun `after colon, only variables in scope are suggested`() {
        val src = """
            MAKE "depth 5
            TO F :SIZE
              FD :
            END
        """.trimIndent()
        // Cursor right after the `:` in "FD :"
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4) // place cursor right after the `:` char
        val items = Completion.compute(snap(src), cursor)
        // No procedures in this list — only variables.
        assertTrue(
            items.none { it.kind == CompletionItemKind.Function },
            "did not expect Function items after ':', got ${items.map { it.label to it.kind }}",
        )
        val vars = items.filter { it.kind == CompletionItemKind.Variable }.map { it.label }.toSet()
        assertTrue(":SIZE" in vars, "expected :SIZE in scope, got $vars")
        assertTrue(":depth" in vars, "expected :depth global in scope, got $vars")
    }

    @Test fun `after colon, insertText omits the colon to avoid duplication`() {
        val src = """
            MAKE "x 1
            FD :
        """.trimIndent()
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4)
        val item = Completion.compute(snap(src), cursor).single { it.label == ":x" }
        assertEquals("x", item.insertText)
    }

    @Test fun `outside any procedure, only globals are visible`() {
        val src = """
            MAKE "depth 5
            TO F :SIZE END
            FD :
        """.trimIndent()
        // Cursor on the third line, after the `:`
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4)
        val items = Completion.compute(snap(src), cursor)
        val vars = items.filter { it.kind == CompletionItemKind.Variable }.map { it.label }.toSet()
        assertTrue(":depth" in vars, "expected global :depth, got $vars")
        assertTrue(":SIZE" !in vars, "did not expect :SIZE outside procedure F, got $vars")
    }

    @Test fun `parameter detail mentions its owning procedure`() {
        val src = """
            TO SQUARE :SIDE
              FD :
            END
        """.trimIndent()
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4)
        val items = Completion.compute(snap(src), cursor)
        val side = items.single { it.label == ":SIDE" }
        assertEquals("parameter of SQUARE", side.detail)
    }

    @Test fun `global detail says global variable`() {
        val src = """
            MAKE "depth 5
            FD :
        """.trimIndent()
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4)
        val items = Completion.compute(snap(src), cursor)
        val depth = items.single { it.label == ":depth" }
        assertEquals("global variable", depth.detail)
    }

    // ---------- the quote context ----------------------------------------

    @Test fun `after a double-quote, no completions are offered`() {
        // User is naming a brand-new global with MAKE "x ; we don't suggest existing ones.
        val src = """
            MAKE "depth 5
            MAKE "
        """.trimIndent()
        val cursor = pos(src, "MAKE \"", offsetIntoNeedle = 6)
        // The `"` at position 5 of the second line is the trigger character
        val items = Completion.compute(snap(src), cursor)
        assertTrue(items.isEmpty(), "expected no completions after \", got ${items.map { it.label }}")
    }

    // ---------- general non-trigger context -------------------------------

    @Test fun `at start of an identifier, both procedures and variables-with-colon appear`() {
        val src = """
            TO F :SIZE
              ${'F'}
            END
        """.trimIndent()
        // Cursor at start of partial identifier 'F' on line 1
        val items = Completion.compute(snap(src), LspPosition(1, 2))
        val labels = items.map { it.label }.toSet()
        // Procedures present
        assertTrue("FORWARD" in labels)
        // Variables-with-colon also present so the user can write `:SIZE` directly
        assertTrue(":SIZE" in labels)
        val sideItem = items.first { it.label == ":SIZE" }
        // insertText DOES include the colon since user is not after a `:` trigger
        assertEquals(":SIZE", sideItem.insertText)
    }

    // ---------- determinism ----------------------------------------------

    @Test fun `same snapshot, same cursor — identical results`() {
        val src = """
            TO F :A :B
              FD :
            END
        """.trimIndent()
        val cursor = pos(src, "FD :", offsetIntoNeedle = 4)
        val a = Completion.compute(snap(src), cursor).map { it.label }
        val b = Completion.compute(snap(src), cursor).map { it.label }
        assertEquals(a, b)
    }
}
