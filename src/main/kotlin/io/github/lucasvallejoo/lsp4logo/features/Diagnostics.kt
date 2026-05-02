package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.Diagnostic
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.LspConversions
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.Diagnostic as LspDiagnostic

/**
 * Convert internal [Diagnostic]s to LSP4J's wire format and push them to a
 * connected client. Pure adapter — no caching, no batching, no global state.
 *
 * The publish helper is fire-and-forget; a missing client (e.g. during
 * startup or in tests) is silently tolerated so that the document store can
 * keep producing snapshots even when no client is listening.
 */
object Diagnostics {

    fun toLsp(d: Diagnostic): LspDiagnostic = LspDiagnostic().apply {
        range = LspConversions.toLspRange(d.range)
        severity = when (d.severity) {
            Diagnostic.Severity.Error -> DiagnosticSeverity.Error
            Diagnostic.Severity.Warning -> DiagnosticSeverity.Warning
            Diagnostic.Severity.Information -> DiagnosticSeverity.Information
            Diagnostic.Severity.Hint -> DiagnosticSeverity.Hint
        }
        source = d.source
        message = d.message
    }

    fun publish(client: LanguageClient?, snapshot: DocumentSnapshot) {
        client ?: return
        val params = PublishDiagnosticsParams(
            snapshot.uri.toString(),
            snapshot.diagnostics.map(::toLsp),
            snapshot.version,
        )
        client.publishDiagnostics(params)
    }
}
