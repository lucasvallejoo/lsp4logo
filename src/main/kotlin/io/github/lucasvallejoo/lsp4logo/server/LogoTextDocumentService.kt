package io.github.lucasvallejoo.lsp4logo.server

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI

/**
 * Handles the textDocument-namespace family of LSP notifications and requests
 * (didOpen, didChange, didClose, didSave; later: definition, semanticTokens, …).
 *
 * **Notifications** ([didOpen], [didChange], [didClose]) are *writers*: they
 * compute a new [DocumentSnapshot] and publish it atomically through the
 * [DocumentStore]. They never expose mutable state.
 *
 * **Requests** (definition, semanticTokens, inlayHint, completion — coming in
 * phase 4) are *readers*: each one captures the current snapshot in its first
 * line and computes a result purely against that frozen view. This is what
 * makes simultaneous independent requests a non-issue: there is no shared
 * mutable state for them to fight over.
 */
class LogoTextDocumentService(
    private val store: DocumentStore,
) : TextDocumentService {

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        store.open(URI.create(doc.uri), doc.version, doc.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // We declared TextDocumentSyncKind.Full at initialize time, so each
        // change event carries the complete new content. The last change
        // event wins (LSP guarantees a single contentChanges entry under Full).
        val doc = params.textDocument
        val change = params.contentChanges.firstOrNull() ?: return
        store.update(URI.create(doc.uri), doc.version, change.text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        store.close(URI.create(params.textDocument.uri))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No-op for now. We could trigger a deeper analysis on save later.
    }
}
