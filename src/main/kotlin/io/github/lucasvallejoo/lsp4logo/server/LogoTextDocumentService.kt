package io.github.lucasvallejoo.lsp4logo.server

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.services.TextDocumentService

/** Day 1 stub. Open/change/close handlers will populate a DocumentStore in Day 2. */
class LogoTextDocumentService : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) {}
    override fun didChange(params: DidChangeTextDocumentParams) {}
    override fun didClose(params: DidCloseTextDocumentParams) {}
    override fun didSave(params: DidSaveTextDocumentParams) {}
}
