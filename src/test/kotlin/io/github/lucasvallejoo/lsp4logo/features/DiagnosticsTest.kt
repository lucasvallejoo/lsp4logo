package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.Diagnostic
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.Position
import io.github.lucasvallejoo.lsp4logo.util.Range
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsTest {

    private val uri = URI.create("file:///tmp/x.logo")

    @Test fun `severity is mapped on every level`() {
        assertEquals(
            DiagnosticSeverity.Error,
            Diagnostics.toLsp(
                Diagnostic("e", Range.EMPTY, Diagnostic.Severity.Error, "x"),
            ).severity,
        )
        assertEquals(
            DiagnosticSeverity.Warning,
            Diagnostics.toLsp(
                Diagnostic("w", Range.EMPTY, Diagnostic.Severity.Warning, "x"),
            ).severity,
        )
        assertEquals(
            DiagnosticSeverity.Information,
            Diagnostics.toLsp(
                Diagnostic("i", Range.EMPTY, Diagnostic.Severity.Information, "x"),
            ).severity,
        )
        assertEquals(
            DiagnosticSeverity.Hint,
            Diagnostics.toLsp(
                Diagnostic("h", Range.EMPTY, Diagnostic.Severity.Hint, "x"),
            ).severity,
        )
    }

    @Test fun `range, message, and source are copied verbatim`() {
        val r = Range(Position(0, 1, 2), Position(5, 1, 7))
        val d = Diagnostic("nope", r, Diagnostic.Severity.Error, "resolver")
        val lsp = Diagnostics.toLsp(d)
        assertEquals("nope", lsp.message)
        assertEquals("resolver", lsp.source)
        assertEquals(1, lsp.range.start.line)
        assertEquals(2, lsp.range.start.character)
        assertEquals(7, lsp.range.end.character)
    }

    @Test fun `publish is a no-op when there is no client`() {
        val snapshot = DocumentSnapshot.analyse(uri, version = 1, source = "FD 50")
        // must not throw
        Diagnostics.publish(client = null, snapshot = snapshot)
    }

    @Test fun `publish forwards the snapshot's diagnostics to the client with the correct uri and version`() {
        val received = AtomicReference<PublishDiagnosticsParams?>(null)
        val client = object : LanguageClient {
            override fun telemetryEvent(o: Any?) {}
            override fun publishDiagnostics(p: PublishDiagnosticsParams) { received.set(p) }
            override fun showMessage(p: org.eclipse.lsp4j.MessageParams?) {}
            override fun showMessageRequest(p: org.eclipse.lsp4j.ShowMessageRequestParams?) =
                java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem?>()
            override fun logMessage(p: org.eclipse.lsp4j.MessageParams?) {}
        }

        // A program with both a parser error (unclosed bracket) and a resolver
        // warning (unresolved variable) — exercises the unified diagnostic stream.
        val snapshot = DocumentSnapshot.analyse(uri, version = 7, source = "REPEAT 4 [ FD :missing")
        Diagnostics.publish(client, snapshot)

        val params = received.get()
        assertNotNull(params)
        assertEquals(uri.toString(), params!!.uri)
        assertEquals(7, params.version)
        assertTrue(params.diagnostics.isNotEmpty(), "expected diagnostics to be forwarded")
        val sources = params.diagnostics.mapNotNull { it.source }.toSet()
        assertTrue("parser" in sources, "expected a parser diagnostic, got $sources")
        assertTrue("resolver" in sources, "expected a resolver diagnostic, got $sources")
    }

    @Test fun `clean source produces no diagnostics`() {
        val snapshot = DocumentSnapshot.analyse(uri, version = 1, source = "FD 50")
        assertTrue(snapshot.diagnostics.isEmpty())
        // And toLsp on an empty list yields an empty list (sanity)
        assertNull(snapshot.diagnostics.firstOrNull())
    }
}
