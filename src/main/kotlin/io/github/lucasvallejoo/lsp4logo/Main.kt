package io.github.lucasvallejoo.lsp4logo

import io.github.lucasvallejoo.lsp4logo.server.LogoLanguageServer
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

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
}
