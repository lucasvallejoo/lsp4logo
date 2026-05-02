package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.turtle.AbstractInterpreter
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.LspConversions
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.Range as LspRange

/**
 * Convert the [AbstractInterpreter]'s trace into LSP [InlayHint]s placed at
 * the end of each statement. Each hint reads ` ⟶ x=…  y=…  h=…° ` (or
 * `home`, or `?`) — a one-line summary of where the turtle is after that
 * statement runs.
 *
 * Hints are returned for both top-level statements and the bodies of every
 * user-defined procedure (with parameters held symbolically inside the
 * procedure). The LSP spec lets the client request a sub-range, so we
 * filter by [requestedRange] before returning.
 */
object InlayHints {

    fun compute(snapshot: DocumentSnapshot, requestedRange: LspRange?): List<InlayHint> {
        val interp = AbstractInterpreter(snapshot.resolved)
        val all = mutableListOf<AbstractInterpreter.HintEntry>()

        // Top-level statements first.
        all += interp.traceTopLevel()

        // Then every user-declared procedure body.
        for (item in snapshot.resolved.program.items) {
            if (item is ProcedureDecl) all += interp.traceProcedure(item)
        }

        return all
            .filter { it.statement.range.start.offset < snapshot.source.length || it.statement.range.length > 0 }
            .filter { entry -> requestedRange == null || rangeIntersects(entry.statement.range, requestedRange) }
            .map { entry -> toInlayHint(entry) }
    }

    private fun toInlayHint(entry: AbstractInterpreter.HintEntry): InlayHint {
        val pos = LspConversions.toLspPosition(entry.statement.range.end)
        val label = "  ⟶ " + entry.stateAfter.format()
        val hint = InlayHint(pos, org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(label))
        hint.kind = InlayHintKind.Type
        hint.paddingLeft = true
        return hint
    }

    private fun rangeIntersects(
        a: io.github.lucasvallejoo.lsp4logo.util.Range,
        b: LspRange,
    ): Boolean {
        // Compare on lines for a tolerant intersection — column-precise filtering
        // is unnecessary for inlay hints which are visual hints, not selections.
        val aStart = a.start.line; val aEnd = a.end.line
        val bStart = b.start.line; val bEnd = b.end.line
        return aStart <= bEnd && bStart <= aEnd
    }
}
