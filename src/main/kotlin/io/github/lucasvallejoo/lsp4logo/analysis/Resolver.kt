package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.ast.Expr
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Program
import io.github.lucasvallejoo.lsp4logo.ast.Stmt
import io.github.lucasvallejoo.lsp4logo.ast.TopLevel

/**
 * The Resolver walks a parsed [Program], binds every name reference to a [Symbol],
 * and emits resolver diagnostics for things the parser can't see (unresolved
 * variables, etc.).
 *
 * **Why a separate phase from the parser.** Parsing answers "is this code
 * structurally valid?". Resolution answers "does each name refer to something
 * that exists?". Splitting them keeps each phase small, single-responsibility,
 * and individually testable — and the resolver's output ([ResolvedProgram])
 * becomes the single read-only knowledge surface that every LSP feature
 * consults.
 *
 * **Scope rules** (a deliberate simplification of LOGO's traditional dynamic
 * scoping, documented in the README):
 *  - Procedure parameters are visible only within their owning procedure body.
 *  - Names introduced by `MAKE "x …` are treated as globals: visible
 *    everywhere from their declaration onward. (We forgo dynamic scoping
 *    because for an IDE, a name resolving to *one* declaration is much more
 *    useful than to "whichever caller had it on the stack".)
 *  - Inside a procedure body, parameters shadow same-named globals.
 *
 * **Determinism.** The resolver is a pure function: same `Program` in →
 * same `ResolvedProgram` out, every time. No I/O, no clock, no globals.
 */
class Resolver private constructor(private val program: Program) {

    private val procedures = LinkedHashMap<String, Symbol.Procedure>()
    private val globals = LinkedHashMap<String, Symbol.Variable.Global>()
    private val params = LinkedHashMap<ProcedureDecl, List<Symbol.Variable.ParameterSym>>()
    private val bindings = mutableListOf<Binding>()
    private val diagnostics = mutableListOf<Diagnostic>()

    private fun resolve(): ResolvedProgram {
        // ---- Phase 1: register all declarations ---------------------------
        // Built-ins first; user-defined override built-ins of the same name.
        BuiltIns.all.values.forEach { procedures[it.name] = it }

        for (item in program.items) {
            if (item is ProcedureDecl) registerProcedure(item)
        }

        // ---- Phase 2: walk every reference --------------------------------
        for (item in program.items) {
            when (item) {
                is ProcedureDecl -> visitProcedure(item)
                is Stmt -> visitStmt(item, owner = null)
            }
        }

        // ---- Compose immutable result -------------------------------------
        return ResolvedProgram(
            program = program,
            procedures = procedures.values.toList(),
            globalVariables = globals.values.toList(),
            parametersByOwner = params.toMap(),
            bindings = bindings.toList(),
            diagnostics = diagnostics.toList(),
        )
    }

    // ---- registration -----------------------------------------------------

    private fun registerProcedure(decl: ProcedureDecl) {
        val canonical = decl.name.canonicalName()
        val sym = Symbol.Procedure.UserDefined(
            name = canonical,
            displayName = decl.name,
            arity = decl.parameters.size,
            declaration = decl,
        )
        procedures[canonical] = sym

        // Register the procedure name itself as a binding (so go-to-decl on
        // the name in `TO SQUARE …` lights up too).
        bindings.add(Binding(decl.nameRange, sym))

        // Build parameter symbols and register both their declaration sites
        // (the `:NAME` token in the TO line) as bindings.
        val ps = decl.parameters.map { p ->
            Symbol.Variable.ParameterSym(
                name = p.name.canonicalName(),
                displayName = p.name,
                declarationRange = p.range,
                parameter = p,
                owner = decl,
            )
        }
        params[decl] = ps
        ps.forEach { bindings.add(Binding(it.declarationRange, it)) }
    }

    // ---- visiting ---------------------------------------------------------

    private fun visitProcedure(decl: ProcedureDecl) {
        for (s in decl.body) visitStmt(s, owner = decl)
    }

    private fun visitStmt(stmt: Stmt, owner: ProcedureDecl?) {
        when (stmt) {
            is Stmt.Repeat -> {
                visitExpr(stmt.count, owner)
                stmt.body.forEach { visitStmt(it, owner) }
            }
            is Stmt.If -> {
                visitExpr(stmt.condition, owner)
                stmt.body.forEach { visitStmt(it, owner) }
            }
            is Stmt.ProcedureCall -> visitProcedureCall(stmt.name, stmt.args, owner)
        }
    }

    private fun visitProcedureCall(
        name: io.github.lucasvallejoo.lsp4logo.ast.NameRef,
        args: List<Expr>,
        owner: ProcedureDecl?,
    ) {
        val canonical = name.name.canonicalName()
        val target = procedures[canonical]
        if (target != null) {
            bindings.add(Binding(name.range, target))
        }
        // No diagnostic for unresolved procedure: the parser already complained
        // ("unknown procedure 'X'"). The resolver only emits diagnostics for
        // things the parser cannot see.

        // Special handling for MAKE: the first argument is a WordLit that
        // *introduces* a global the first time it's seen.
        if (canonical == "MAKE" && args.isNotEmpty()) {
            val target0 = args[0]
            if (target0 is Expr.WordLit) {
                registerGlobalIfNew(target0, declaringStmt = currentStmtCarrier(args, name))
            }
        }

        for (a in args) visitExpr(a, owner)
    }

    /** Helper to recover the originating Stmt.ProcedureCall for a MAKE we're walking. */
    private fun currentStmtCarrier(
        args: List<Expr>,
        name: io.github.lucasvallejoo.lsp4logo.ast.NameRef,
    ): Stmt.ProcedureCall {
        // The resolver doesn't carry the parent statement node, so we build a
        // synthetic carrier with the right range: from the call's name to the
        // last argument. This is only used as a back-pointer for the global's
        // declaration site, never compared structurally.
        val end = args.lastOrNull()?.range?.end ?: name.range.end
        return Stmt.ProcedureCall(
            name = name,
            args = args,
            range = io.github.lucasvallejoo.lsp4logo.util.Range(name.range.start, end),
        )
    }

    private fun registerGlobalIfNew(target: Expr.WordLit, declaringStmt: Stmt.ProcedureCall) {
        val canonical = target.name.canonicalName()
        if (globals.containsKey(canonical)) return
        globals[canonical] = Symbol.Variable.Global(
            name = canonical,
            displayName = target.name,
            declarationRange = target.range,
            declarationSite = declaringStmt,
        )
    }

    private fun visitExpr(expr: Expr, owner: ProcedureDecl?) {
        when (expr) {
            is Expr.NumberLit -> Unit
            is Expr.WordLit -> Unit          // word literals don't reference anything
            is Expr.VariableRef -> resolveVariableRef(expr, owner)
            is Expr.BinaryOp -> {
                visitExpr(expr.left, owner); visitExpr(expr.right, owner)
            }
            is Expr.UnaryMinus -> visitExpr(expr.operand, owner)
            is Expr.Grouping -> visitExpr(expr.inner, owner)
            is Expr.ProcedureCall -> visitProcedureCall(expr.name, expr.args, owner)
        }
    }

    private fun resolveVariableRef(ref: Expr.VariableRef, owner: ProcedureDecl?) {
        val canonical = ref.name.canonicalName()

        // 1. Parameter of the enclosing procedure?
        if (owner != null) {
            val match = params[owner]?.firstOrNull { it.name == canonical }
            if (match != null) {
                bindings.add(Binding(ref.range, match))
                return
            }
        }

        // 2. Previously declared global?
        val global = globals[canonical]
        if (global != null) {
            bindings.add(Binding(ref.range, global))
            return
        }

        // 3. Unresolved.
        diagnostics.add(
            Diagnostic(
                message = "unresolved variable ':${ref.name}' — declare it with MAKE \"${ref.name} value, or add it as a parameter",
                range = ref.range,
                severity = Diagnostic.Severity.Warning,
                source = "resolver",
            ),
        )
    }

    companion object {
        /** Resolve a parsed program. Pure, side-effect free, deterministic. */
        fun resolve(program: Program): ResolvedProgram = Resolver(program).resolve()
    }
}
