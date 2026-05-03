package io.github.lucasvallejoo.lsp4logo

import io.github.lucasvallejoo.lsp4logo.server.LogoLanguageServer
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import kotlin.system.exitProcess

/**
 * Standard LSP entry point. Reads JSON-RPC over stdin, writes over stdout.
 *
 * Why the explicit `exitProcess(0)`: the work-stealing executor used inside
 * [LogoLanguageServer] (and the JSON-RPC dispatch threads in LSP4J) keep the
 * JVM alive even after the JSON-RPC listening loop returns — closing stdin
 * alone is not enough. The LSP spec mandates that the server terminate the
 * process after the `exit` notification (or, by extension, after the
 * connection drops), so we make that contract concrete here.
 */
fun main() {
    val server = LogoLanguageServer()
    val launcher = Launcher.createLauncher(
        server,
        LanguageClient::class.java,
        System.`in`,
        System.out,
    )
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
    // Give any final response bytes a moment to drain through stdout buffers
    // before we hard-exit the JVM. Without this, fast scripted clients (e.g.
    // scripts/demo.py) can race the exitProcess and miss the last reply.
    System.out.flush()
    Thread.sleep(150)
    exitProcess(0)
}
