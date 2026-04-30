package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Program
import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * The resolver's output: a fully-bound view of a [Program].
 *
 * Designed as a *queryable, immutable record*. Every LSP feature reads from
 * this in O(1)–O(n) without locking, which is what makes concurrent independent
 * request handling natural inside the document store.
 */
data class ResolvedProgram(
    val program: Program,
    /** All known procedures — user-defined (in declaration order) followed by built-ins. */
    val procedures: List<Symbol.Procedure>,
    /** Globals discovered via `MAKE "name …` statements, in declaration order. */
    val globalVariables: List<Symbol.Variable.Global>,
    /** Parameters by their owning procedure declaration. */
    val parametersByOwner: Map<ProcedureDecl, List<Symbol.Variable.ParameterSym>>,
    /** One [Binding] per name *reference* in the source. */
    val bindings: List<Binding>,
    /** Resolver-layer diagnostics (unresolved references, …). */
    val diagnostics: List<Diagnostic>,
) {

    /**
     * Find the binding whose [Binding.referenceRange] contains [offset].
     * Returns `null` if the cursor is not on any reference.
     */
    fun bindingAtOffset(offset: Int): Binding? = bindings.firstOrNull { it.containsOffset(offset) }

    /** All references to the given symbol. */
    fun referencesOf(symbol: Symbol): List<Binding> = bindings.filter { it.symbol == symbol }

    /**
     * The set of variables visible at the given source offset.
     *
     * Visibility rules (deliberate interpretation, documented in README):
     *  - If [offset] falls within a [ProcedureDecl] body, that procedure's
     *    parameters are visible.
     *  - All globals previously declared via `MAKE` are always visible.
     */
    fun variablesInScopeAt(offset: Int): List<Symbol.Variable> {
        val params: List<Symbol.Variable> = enclosingProcedureAt(offset)
            ?.let { parametersByOwner[it].orEmpty() }
            ?: emptyList()
        return params + globalVariables
    }

    /** The user-declared procedure whose body contains [offset], if any. */
    fun enclosingProcedureAt(offset: Int): ProcedureDecl? =
        program.items
            .filterIsInstance<ProcedureDecl>()
            .firstOrNull { it.range.contains(offset) }

    private fun Binding.containsOffset(offset: Int): Boolean =
        referenceRange.start.offset <= offset && offset < referenceRange.end.offset

    private fun Range.contains(offset: Int): Boolean =
        start.offset <= offset && offset < end.offset
}
