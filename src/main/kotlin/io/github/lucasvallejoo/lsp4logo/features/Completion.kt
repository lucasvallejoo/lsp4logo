package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.Symbol
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.LspConversions
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position as LspPosition

/**
 * Suggests completion items for the cursor position.
 *
 * **Context detection**. We peek at the character immediately before the
 * cursor:
 *  - `:` ⇒ user is typing a variable reference; suggest only variables
 *    visible at this position (parameters of the enclosing procedure plus
 *    any globals introduced by `MAKE`). We hand the client the leading `:`
 *    in the label but strip it from `insertText` so the trigger character
 *    does not duplicate.
 *  - `"` ⇒ user is naming a *new* global with `MAKE "name`; we deliberately
 *    return nothing rather than suggest existing globals (introducing a new
 *    name is the common case).
 *  - anything else ⇒ user is starting a procedure call or a fresh
 *    identifier; suggest procedures (built-in and user-defined) plus
 *    variables-with-leading-colon for completeness.
 *
 * **Casing.** LOGO is case-insensitive; the client's prefix filter is
 * generally case-insensitive too. We use the user's declared spelling for
 * display (`displayName`) so the dropdown reflects what they wrote.
 *
 * Pure, snapshot-bound — no I/O, no global state.
 */
object Completion {

    fun compute(snapshot: DocumentSnapshot, lspPos: LspPosition): List<CompletionItem> {
        val offset = LspConversions.fromLspPosition(snapshot.source, lspPos) ?: return emptyList()
        val charBefore = if (offset > 0) snapshot.source[offset - 1] else ' '
        return when (charBefore) {
            ':' -> variablesInScope(snapshot, offset, leadingColon = false)
            '"' -> emptyList()
            else -> procedures(snapshot) + variablesInScope(snapshot, offset, leadingColon = true)
        }
    }

    private fun procedures(snapshot: DocumentSnapshot): List<CompletionItem> =
        snapshot.resolved.procedures.map(::procedureItem)

    private fun procedureItem(proc: Symbol.Procedure): CompletionItem {
        val item = CompletionItem(proc.displayName)
        item.kind = CompletionItemKind.Function
        item.insertText = proc.displayName
        item.detail = describeArity(proc)
        item.documentation = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
            when (proc) {
                is Symbol.Procedure.BuiltIn -> proc.description
                is Symbol.Procedure.UserDefined -> "user-defined procedure"
            },
        )
        return item
    }

    private fun describeArity(proc: Symbol.Procedure): String = when (proc) {
        is Symbol.Procedure.UserDefined -> {
            val params = proc.declaration.parameters.joinToString(" ") { ":${it.name}" }
            if (params.isEmpty()) "(no args)" else "($params)"
        }
        is Symbol.Procedure.BuiltIn -> when (proc.arity) {
            0 -> "(no args)"
            1 -> "(1 arg)"
            else -> "(${proc.arity} args)"
        }
    }

    private fun variablesInScope(
        snapshot: DocumentSnapshot,
        offset: Int,
        leadingColon: Boolean,
    ): List<CompletionItem> {
        val visible = snapshot.resolved.variablesInScopeAt(offset)
        return visible.map { variableItem(it, leadingColon) }
    }

    private fun variableItem(variable: Symbol.Variable, leadingColon: Boolean): CompletionItem {
        val item = CompletionItem(":${variable.displayName}")
        item.kind = CompletionItemKind.Variable
        item.insertText = if (leadingColon) ":${variable.displayName}" else variable.displayName
        item.detail = when (variable) {
            is Symbol.Variable.ParameterSym -> "parameter of ${variable.owner.name}"
            is Symbol.Variable.Global -> "global variable"
        }
        return item
    }
}
