package io.github.lucasvallejoo.lsp4logo.server

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/**
 * The LSP entry point. Owns the [DocumentStore] (the single source of truth
 * about open documents) and the two LSP4J service implementations.
 *
 * No mutable state lives directly on this class except the optional [client]
 * back-reference used to push diagnostics — and that one is only written
 * during the `connect` callback, which the launcher invokes before any
 * request can arrive.
 */
class LogoLanguageServer : LanguageServer, LanguageClientAware {

    private val store = DocumentStore()
    private val textDocuments = LogoTextDocumentService(store)
    private val workspace = LogoWorkspaceService()
    private var client: LanguageClient? = null

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)
        }
        val info = ServerInfo("lsp4logo", "0.1.0")
        return CompletableFuture.completedFuture(InitializeResult(capabilities, info))
    }

    override fun shutdown(): CompletableFuture<Any> =
        CompletableFuture.completedFuture(null)

    override fun exit() {
        // Process termination is handled by the launcher when stdin closes.
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments
    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        this.client = client
    }
}
