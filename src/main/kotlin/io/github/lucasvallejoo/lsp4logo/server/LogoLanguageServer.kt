package io.github.lucasvallejoo.lsp4logo.server

import io.github.lucasvallejoo.lsp4logo.features.SemanticTokens
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.SemanticTokensServerFull
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The LSP entry point. Owns the [DocumentStore] (the single source of truth
 * about open documents), the dedicated [executor] that runs LSP request
 * handlers in parallel, and the two LSP4J service implementations.
 *
 * `connect` populates [clientRef]; everything that needs to push notifications
 * to the client (only [Diagnostics][io.github.lucasvallejoo.lsp4logo.features.Diagnostics]
 * for now) reads from that reference rather than capturing the client at
 * construction time, because LSP4J calls `connect` strictly after
 * construction.
 *
 * @param executor the executor used to run request handlers asynchronously.
 *                 Defaulting to a work-stealing pool gives true parallelism
 *                 across CPU cores while keeping individual request stacks
 *                 cheap.
 */
class LogoLanguageServer(
    private val executor: ExecutorService = Executors.newWorkStealingPool(),
) : LanguageServer, LanguageClientAware {

    private val store = DocumentStore()
    private val clientRef = AtomicReference<LanguageClient?>(null)
    private val textDocuments = LogoTextDocumentService(store, executor, clientRef)
    private val workspace = LogoWorkspaceService()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)
            // Go-to-declaration
            definitionProvider = Either.forLeft(true)
            // Syntax highlighting via semantic tokens
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                SemanticTokens.legend,
                SemanticTokensServerFull(/* delta = */ false),
                /* range = */ false,
            )
            // Turtle-state inlay hints
            inlayHintProvider = Either.forLeft(true)
        }
        val info = ServerInfo("lsp4logo", "0.1.0")
        return CompletableFuture.completedFuture(InitializeResult(capabilities, info))
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)

    override fun exit() {
        // The launcher will close stdin which terminates the JVM in the
        // standard launcher case. Still — be a good citizen and shut the
        // executor down so threads do not linger.
        executor.shutdownNow()
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments
    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        clientRef.set(client)
    }
}
