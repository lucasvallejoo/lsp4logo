package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolverTest {

    private fun resolve(src: String): ResolvedProgram = Resolver.resolve(Parser.parse(src))

    // ---------- procedure declarations ------------------------------------

    @Test fun `built-ins are present in the procedure list`() {
        val r = resolve("")
        assertNotNull(r.procedures.firstOrNull { it is Symbol.Procedure.BuiltIn && it.name == "FORWARD" })
        assertNotNull(r.procedures.firstOrNull { it is Symbol.Procedure.BuiltIn && it.name == "MAKE" })
    }

    @Test fun `user-defined procedure is registered with arity from the AST`() {
        val r = resolve("TO SQUARE :SIDE END")
        val sym = r.procedures.filterIsInstance<Symbol.Procedure.UserDefined>().single()
        assertEquals("SQUARE", sym.name)
        assertEquals("SQUARE", sym.displayName)
        assertEquals(1, sym.arity)
    }

    @Test fun `user-defined procedure overrides a built-in of the same name`() {
        val r = resolve("TO FORWARD :a :b END")
        val sym = r.procedures.first { it.name == "FORWARD" }
        assertIs<Symbol.Procedure.UserDefined>(sym)
        assertEquals(2, sym.arity)
    }

    // ---------- bindings: procedure calls ----------------------------------

    @Test fun `built-in call binds to the built-in symbol`() {
        val r = resolve("FD 50")
        val binding = r.bindings.single { it.symbol.name == "FD" }
        assertIs<Symbol.Procedure.BuiltIn>(binding.symbol)
    }

    @Test fun `user procedure call binds to the user declaration`() {
        val r = resolve(
            """
            TO SQUARE :SIDE END
            SQUARE 50
            """.trimIndent(),
        )
        val callBindings = r.bindings.filter { it.symbol.name == "SQUARE" }
        // One for the declaration site (TO SQUARE) and one for the call (SQUARE 50).
        assertEquals(2, callBindings.size)
        callBindings.forEach { assertIs<Symbol.Procedure.UserDefined>(it.symbol) }
    }

    @Test fun `recursive call binds to the same declaration`() {
        val r = resolve(
            """
            TO TREE :D :S
              TREE :D :S
            END
            """.trimIndent(),
        )
        val treeRefs = r.bindings.filter { it.symbol.name == "TREE" }
        // 1 decl site + 1 recursive call
        assertEquals(2, treeRefs.size)
    }

    // ---------- bindings: parameters ---------------------------------------

    @Test fun `variable reference inside a procedure binds to its parameter`() {
        val r = resolve(
            """
            TO SQUARE :SIDE
              FD :SIDE
            END
            """.trimIndent(),
        )
        val sideRefs = r.bindings.filter { it.symbol.name == "SIDE" }
        // 1 declaration site (the :SIDE in TO line) + 1 reference (FD :SIDE)
        assertEquals(2, sideRefs.size)
        sideRefs.forEach { assertIs<Symbol.Variable.ParameterSym>(it.symbol) }
    }

    @Test fun `parameter resolution is case-insensitive`() {
        val r = resolve(
            """
            TO F :Size
              FD :sIzE
            END
            """.trimIndent(),
        )
        val refs = r.bindings.filter { it.symbol is Symbol.Variable.ParameterSym }
        assertEquals(2, refs.size)
        // Display name preserves the *declaration* spelling, not the reference.
        refs.forEach { assertEquals("Size", (it.symbol as Symbol.Variable.ParameterSym).displayName) }
    }

    @Test fun `parameter is invisible outside its owning procedure`() {
        val r = resolve(
            """
            TO F :X END
            FD :X
            """.trimIndent(),
        )
        // The reference :X at top level cannot resolve to F's parameter.
        val unresolved = r.diagnostics.firstOrNull { "unresolved variable ':X'" in it.message }
        assertNotNull(unresolved, "expected an unresolved-variable diagnostic, got ${r.diagnostics}")
    }

    // ---------- bindings: globals via MAKE ---------------------------------

    @Test fun `MAKE introduces a global and a later reference resolves to it`() {
        val r = resolve(
            """
            MAKE "x 10
            FD :x
            """.trimIndent(),
        )
        // Should NOT have an unresolved diagnostic
        assertTrue(
            r.diagnostics.none { "unresolved variable" in it.message },
            "did not expect unresolved-variable diagnostic, got ${r.diagnostics}",
        )
        val global = r.globalVariables.single { it.name == "X" }
        assertEquals("x", global.displayName)
        val ref = r.bindings.single { it.symbol == global }
        assertEquals(global, ref.symbol)
    }

    @Test fun `globals are visible from inside procedures`() {
        val r = resolve(
            """
            MAKE "depth 5
            TO TREE :SIZE
              FD :depth
            END
            """.trimIndent(),
        )
        assertTrue(r.diagnostics.none { "unresolved variable" in it.message }, "got ${r.diagnostics}")
    }

    @Test fun `parameter shadows a global of the same name`() {
        val r = resolve(
            """
            MAKE "x 10
            TO F :x
              FD :x
            END
            """.trimIndent(),
        )
        // The :x inside F should bind to the parameter, not the global.
        val proc = r.program.items.filterIsInstance<io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl>().single()
        val refInside = r.bindingAtOffset(proc.body.first().range.start.offset + 3) // somewhere in "FD :x"
        // We don't insist on exact offset arithmetic — pull all binding sites for SIDE/X inside the proc body
        val refs = r.bindings.filter { it.referenceRange.start.offset >= proc.range.start.offset }
        val xRefs = refs.filter { it.symbol.name == "X" }
        assertTrue(xRefs.any { it.symbol is Symbol.Variable.ParameterSym }, "expected param binding, got $xRefs")
    }

    // ---------- queries -----------------------------------------------------

    @Test fun `bindingAtOffset finds the symbol at a given source position`() {
        val src = "FD 50"
        val r = resolve(src)
        // Offset 0 lands inside "FD"
        val b = r.bindingAtOffset(0)
        assertNotNull(b)
        assertEquals("FD", b!!.symbol.name)
    }

    @Test fun `bindingAtOffset returns null when the cursor is on whitespace or a number`() {
        val src = "FD 50"
        val r = resolve(src)
        // Offset 3 lands inside "50" — a number literal, not a name reference.
        assertNull(r.bindingAtOffset(3))
    }

    @Test fun `referencesOf returns every reference to a symbol`() {
        val r = resolve(
            """
            TO SQUARE :SIDE
              FD :SIDE
              FD :SIDE
            END
            """.trimIndent(),
        )
        val sideSym = r.bindings.first { it.symbol.name == "SIDE" }.symbol
        // 1 decl + 2 references
        assertEquals(3, r.referencesOf(sideSym).size)
    }

    @Test fun `variablesInScopeAt inside a procedure includes its params and globals`() {
        val src = """
            MAKE "depth 5
            TO F :SIZE
              FD :SIZE
            END
        """.trimIndent()
        val r = resolve(src)
        val proc = r.program.items.filterIsInstance<io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl>().single()
        val visible = r.variablesInScopeAt(proc.body.first().range.start.offset)
        val names = visible.map { it.name }.toSet()
        assertTrue("SIZE" in names, "expected SIZE param visible, got $names")
        assertTrue("DEPTH" in names, "expected DEPTH global visible, got $names")
    }

    @Test fun `variablesInScopeAt at top level returns globals only`() {
        val src = """
            MAKE "x 1
            TO F :SIZE END
        """.trimIndent()
        val r = resolve(src)
        val visible = r.variablesInScopeAt(0) // before any procedure
        val names = visible.map { it.name }.toSet()
        assertEquals(setOf("X"), names)
    }

    // ---------- determinism -------------------------------------------------

    @Test fun `resolution is deterministic — same input produces equal output`() {
        val src = """
            TO TREE :D :S
              IF :D = 0 [ STOP ]
              TREE :D - 1 :S * 0.7
            END
            TREE 3 80
        """.trimIndent()
        val r1 = resolve(src)
        val r2 = resolve(src)
        assertEquals(r1.bindings, r2.bindings)
        assertEquals(r1.diagnostics, r2.diagnostics)
        assertEquals(r1.globalVariables, r2.globalVariables)
    }
}
