package io.github.lucasvallejoo.lsp4logo.server

import io.github.lucasvallejoo.lsp4logo.features.Definition
import io.github.lucasvallejoo.lsp4logo.features.Diagnostics
import io.github.lucasvallejoo.lsp4logo.features.SemanticTokens
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.SemanticTokens as LspSemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles the textDocument-namespace family of LSP notifications and requests.
 *
 * **Notifications** ([didOpen], [didChange], [didClose]) are *writers*: they
 * compute a new [DocumentSnapshot] and publish it atomically through the
 * [DocumentStore], then push a fresh diagnostics notification to the client.
 *
 * **Requests** ([definition], [semanticTokensFull]) are *readers*: each one
 * captures the current snapshot in its first line and computes a result purely
 * against that frozen view. Each request is dispatched on the shared
 * [executor] via [CompletableFutures.computeAsync] so that simultaneous
 * requests run in parallel — and so that LSP's `$/cancelRequest` actually
 * cancels mid-flight work.
 */
class LogoTextDocumentService(
    private val store: DocumentStore,
    private val executor: ExecutorService,
    private val clientRef: AtomicReference<LanguageClient?>,
) : TextDocumentService {

    // ---- writers ---------------------------------------------------------

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        val snapshot = store.open(URI.create(doc.uri), doc.version, doc.text)
        Diagnostics.publish(clientRef.get(), snapshot)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val doc = params.textDocument
        val change = params.contentChanges.firstOrNull() ?: return
        val snapshot = store.update(URI.create(doc.uri), doc.version, change.text) ?: return
        Diagnostics.publish(clientRef.get(), snapshot)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = URI.create(params.textDocument.uri)
        store.close(uri)
        // Clear diagnostics on the client side when a document closes.
        clientRef.get()?.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No-op for now. Could trigger a deeper analysis on save later.
    }

    // ---- readers (run in parallel on `executor`) -------------------------

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        val uri = URI.create(params.textDocument.uri)
        val pos = params.position
        return CompletableFutures.computeAsync(executor) { cancel ->
            cancel.checkCanceled()
            val snapshot = store.snapshotOf(uri) ?: return@computeAsync forLeft(emptyList())
            cancel.checkCanceled()
            val locations = Definition.locate(snapshot, pos)
            forLeft(locations)
        }
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<LspSemanticTokens> {
        val uri = URI.create(params.textDocument.uri)
        return CompletableFutures.computeAsync(executor) { cancel ->
            cancel.checkCanceled()
            val snapshot = store.snapshotOf(uri) ?: return@computeAsync LspSemanticTokens(emptyList())
            cancel.checkCanceled()
            SemanticTokens.encode(snapshot)
        }
    }

    // LSP4J's Either<List, List> returns require a MutableList; helper to keep
    // call-sites tidy while staying type-safe at the boundary.
    private fun forLeft(
        locs: List<Location>,
    ): Either<MutableList<out Location>, MutableList<out LocationLink>> =
        Either.forLeft(locs.toMutableList())
}
