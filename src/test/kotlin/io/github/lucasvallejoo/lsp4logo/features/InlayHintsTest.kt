package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import org.eclipse.lsp4j.InlayHintKind
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlayHintsTest {

    private val uri = URI.create("file:///tmp/x.logo")
    private fun snap(src: String): DocumentSnapshot = DocumentSnapshot.analyse(uri, version = 1, source = src)

    @Test fun `concrete program produces a hint per top-level statement`() {
        val hints = InlayHints.compute(snap("FD 50\nRT 90\nFD 30"), requestedRange = null)
        // Three statements → three hints.
        assertEquals(3, hints.size)
    }

    @Test fun `hint label uses the arrow prefix`() {
        val hints = InlayHints.compute(snap("FD 50"), requestedRange = null)
        val label = hints.single().label.left
        assertTrue(label.contains("⟶"), "expected arrow in label, got '$label'")
    }

    @Test fun `hint at FD 50 reports y=50`() {
        val hints = InlayHints.compute(snap("FD 50"), requestedRange = null)
        val label = hints.single().label.left
        assertTrue(label.contains("y=50"), "expected y=50 in label, got '$label'")
    }

    @Test fun `hint after a closed REPEAT shows home`() {
        val src = """
            TO SQUARE :SIDE
              REPEAT 4 [ FD :SIDE RT 90 ]
            END
        """.trimIndent()
        val hints = InlayHints.compute(snap(src), requestedRange = null)
        // The hint that lives at the very end of the REPEAT (the post-block hint)
        // should read "home" because the shape closes.
        val labels = hints.map { it.label.left }
        assertTrue(labels.any { it.contains("home") }, "expected a 'home' hint, labels: $labels")
    }

    @Test fun `hint after an unclosed REPEAT does NOT say home`() {
        val src = """
            TO TRI :SIDE
              REPEAT 3 [ FD :SIDE RT 100 ]
            END
        """.trimIndent()
        val hints = InlayHints.compute(snap(src), requestedRange = null)
        val labels = hints.map { it.label.left }
        // The REPEAT-level hint should not be "home" (state-leak case).
        // We assert the *last* label inside the procedure is not "home".
        val procLabels = labels // simplification — full labels include ⟶ prefix
        assertTrue(
            procLabels.none { it.endsWith("home") },
            "expected no 'home' hint in the unclosed-shape case, got $procLabels",
        )
    }

    @Test fun `hint kind is Type and paddingLeft is true`() {
        val hint = InlayHints.compute(snap("FD 50"), requestedRange = null).single()
        assertEquals(InlayHintKind.Type, hint.kind)
        assertEquals(true, hint.paddingLeft)
    }

    @Test fun `hint position points at the end of the statement`() {
        // "FD 50" — statement range is offsets 0..5; in line/col terms (0,0)→(0,5).
        val hint = InlayHints.compute(snap("FD 50"), requestedRange = null).single()
        assertEquals(0, hint.position.line)
        assertEquals(5, hint.position.character)
    }

    @Test fun `requested range filters to overlapping statements only`() {
        val src = """
            FD 50
            RT 90
            FD 30
        """.trimIndent()
        // Request only line 1 (the RT 90 in the middle).
        val onlyMiddle = org.eclipse.lsp4j.Range(
            org.eclipse.lsp4j.Position(1, 0),
            org.eclipse.lsp4j.Position(1, 100),
        )
        val hints = InlayHints.compute(snap(src), requestedRange = onlyMiddle)
        assertEquals(1, hints.size)
    }

    @Test fun `procedure body hints use parameter symbols`() {
        val src = """
            TO F :SIDE
              FD :SIDE
            END
        """.trimIndent()
        val hints = InlayHints.compute(snap(src), requestedRange = null)
        // At least one label contains the symbolic ":SIDE" (the parameter name).
        assertTrue(
            hints.any { it.label.left.contains(":SIDE") },
            "expected a hint with :SIDE, labels: ${hints.map { it.label.left }}",
        )
    }
}
