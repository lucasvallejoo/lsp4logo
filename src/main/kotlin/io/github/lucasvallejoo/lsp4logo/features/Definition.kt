package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.Symbol
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.LspConversions
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position as LspPosition

/**
 * Pure function `(snapshot, position) → list of declaration locations`.
 *
 * Because the snapshot is immutable and this function does no I/O, the LSP
 * dispatcher can call it on any thread, in parallel with other handlers
 * reading the same snapshot, without any locking. That is the architectural
 * payoff promised in [io.github.lucasvallejoo.lsp4logo.server.DocumentStore].
 */
object Definition {

    fun locate(snapshot: DocumentSnapshot, lspPos: LspPosition): List<Location> {
        val offset = LspConversions.fromLspPosition(snapshot.source, lspPos) ?: return emptyList()
        val binding = snapshot.resolved.bindingAtOffset(offset) ?: return emptyList()

        val targetRange = when (val sym = binding.symbol) {
            is Symbol.Procedure.UserDefined -> sym.declaration.nameRange
            is Symbol.Procedure.BuiltIn -> return emptyList() // built-ins have no source location
            is Symbol.Variable.ParameterSym -> sym.declarationRange
            is Symbol.Variable.Global -> sym.declarationRange
        }

        return listOf(Location(snapshot.uri.toString(), LspConversions.toLspRange(targetRange)))
    }
}
