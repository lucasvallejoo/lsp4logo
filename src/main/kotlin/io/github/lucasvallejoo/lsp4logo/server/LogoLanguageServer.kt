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
 * Day 1 skeleton: announces minimal capabilities so a generic LSP client (e.g. LSP4IJ)
 * can complete the initialize handshake. Real features land in subsequent commits.
 */
class LogoLanguageServer : LanguageServer, LanguageClientAware {

    private val textDocuments = LogoTextDocumentService()
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
