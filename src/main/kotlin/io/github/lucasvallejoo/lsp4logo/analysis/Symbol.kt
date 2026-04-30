package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.ast.Parameter
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Stmt
import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * A named entity discoverable by the resolver: a procedure (built-in or user-defined),
 * a procedure parameter, or a global variable introduced by `MAKE`.
 *
 * **Case sensitivity.** LOGO names are case-insensitive (`:Size`, `:size`, `:SIZE` are
 * the same variable). [name] is therefore stored in canonical (upper-case) form so
 * that map keys and `equals` work as expected. The user's original spelling is still
 * reachable through the underlying AST node ([Variable.Parameter.parameter],
 * [Variable.Global.declarationSite], or [Procedure.UserDefined.declaration]).
 */
sealed interface Symbol {

    /** Canonical (upper-cased) name. Use [displayName] for user-facing strings. */
    val name: String

    /** The exact spelling chosen by the author at the declaration site. */
    val displayName: String

    /** A callable procedure. Either user-declared with `TO … END` or a baked-in command. */
    sealed interface Procedure : Symbol {
        val arity: Int

        data class UserDefined(
            override val name: String,
            override val displayName: String,
            override val arity: Int,
            val declaration: ProcedureDecl,
        ) : Procedure

        data class BuiltIn(
            override val name: String,
            override val arity: Int,
            val description: String,
        ) : Procedure {
            override val displayName: String get() = name
        }
    }

    /** A variable — a procedure parameter or a global created by `MAKE`. */
    sealed interface Variable : Symbol {
        /** Where the variable was *declared* (parameter site or first MAKE). */
        val declarationRange: Range

        data class ParameterSym(
            override val name: String,
            override val displayName: String,
            override val declarationRange: Range,
            val parameter: Parameter,
            val owner: ProcedureDecl,
        ) : Variable

        data class Global(
            override val name: String,
            override val displayName: String,
            override val declarationRange: Range,
            /** The `MAKE "name value` statement that first introduced this global. */
            val declarationSite: Stmt.ProcedureCall,
        ) : Variable
    }
}

/** Convert a LOGO identifier to its canonical (case-insensitive) form. */
internal fun String.canonicalName(): String = this.uppercase()
