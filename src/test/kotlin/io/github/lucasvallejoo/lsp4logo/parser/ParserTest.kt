package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.ast.BinOpKind
import io.github.lucasvallejoo.lsp4logo.ast.Expr
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Stmt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserTest {

    private fun parse(src: String) = Parser.parse(src)

    // ---------- golden paths ------------------------------------------------

    @Test fun `empty program is valid and error-free`() {
        val p = parse("")
        assertTrue(p.items.isEmpty())
        assertTrue(p.errors.isEmpty())
    }

    @Test fun `only whitespace and comments is valid`() {
        val p = parse("  \n; just a comment\n  ")
        assertTrue(p.items.isEmpty())
        assertTrue(p.errors.isEmpty())
    }

    @Test fun `single built-in command`() {
        val p = parse("FD 50")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val call = assertIs<Stmt.ProcedureCall>(p.items.single())
        assertEquals("FD", call.name.name)
        assertEquals(1, call.args.size)
        assertEquals(50.0, assertIs<Expr.NumberLit>(call.args[0]).value)
    }

    @Test fun `procedure declaration with one parameter`() {
        val p = parse("TO SQUARE :SIDE FD :SIDE END")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val proc = assertIs<ProcedureDecl>(p.items.single())
        assertEquals("SQUARE", proc.name)
        assertEquals(listOf("SIDE"), proc.parameters.map { it.name })
        assertEquals(1, proc.body.size)
    }

    @Test fun `procedure with no parameters`() {
        val p = parse("TO GREET END")
        val proc = assertIs<ProcedureDecl>(p.items.single())
        assertTrue(proc.parameters.isEmpty())
        assertTrue(proc.body.isEmpty())
    }

    @Test fun `REPEAT block`() {
        val p = parse("REPEAT 4 [ FD 50 RT 90 ]")
        assertTrue(p.errors.isEmpty())
        val rep = assertIs<Stmt.Repeat>(p.items.single())
        assertEquals(4.0, assertIs<Expr.NumberLit>(rep.count).value)
        assertEquals(2, rep.body.size)
    }

    @Test fun `IF block`() {
        val p = parse("IF :x = 0 [ STOP ]")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val ifs = assertIs<Stmt.If>(p.items.single())
        val cond = assertIs<Expr.BinaryOp>(ifs.condition)
        assertEquals(BinOpKind.Eq, cond.op)
        assertEquals(1, ifs.body.size)
    }

    @Test fun `MAKE with word literal target`() {
        val p = parse("MAKE \"x 10")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val call = assertIs<Stmt.ProcedureCall>(p.items.single())
        assertEquals("MAKE", call.name.name)
        assertEquals("x", assertIs<Expr.WordLit>(call.args[0]).name)
        assertEquals(10.0, assertIs<Expr.NumberLit>(call.args[1]).value)
    }

    // ---------- two-pass arity resolution ----------------------------------

    @Test fun `two-pass — caller before callee resolves correctly`() {
        // Pass 1 must learn TREE's arity before Pass 2 sees the call.
        val src = """
            TREE 3 80
            TO TREE :DEPTH :SIZE END
        """.trimIndent()
        val p = parse(src)
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val call = assertIs<Stmt.ProcedureCall>(p.items.first())
        assertEquals("TREE", call.name.name)
        assertEquals(2, call.args.size)
    }

    @Test fun `user procedure used inside another procedure body`() {
        val src = """
            TO HOUSE
              SQUARE 50
              SQUARE 60
            END
            TO SQUARE :SIDE
              REPEAT 4 [ FD :SIDE RT 90 ]
            END
        """.trimIndent()
        val p = parse(src)
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        assertEquals(2, p.items.size)
        val house = assertIs<ProcedureDecl>(p.items[0])
        assertEquals(2, house.body.size)
    }

    // ---------- expression precedence --------------------------------------

    @Test fun `multiplication binds tighter than addition`() {
        // FD :a + :b * :c  ==>  FD (a + (b * c))
        val p = parse("FD :a + :b * :c")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val arg = assertIs<Stmt.ProcedureCall>(p.items.single()).args.single()
        val add = assertIs<Expr.BinaryOp>(arg)
        assertEquals(BinOpKind.Add, add.op)
        assertEquals(BinOpKind.Mul, assertIs<Expr.BinaryOp>(add.right).op)
    }

    @Test fun `parens override precedence`() {
        val p = parse("FD ( :a + :b ) * :c")
        val arg = assertIs<Stmt.ProcedureCall>(p.items.single()).args.single()
        val mul = assertIs<Expr.BinaryOp>(arg)
        assertEquals(BinOpKind.Mul, mul.op)
        assertIs<Expr.Grouping>(mul.left)
    }

    @Test fun `unary minus`() {
        val p = parse("FD -5")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val arg = assertIs<Stmt.ProcedureCall>(p.items.single()).args.single()
        assertIs<Expr.UnaryMinus>(arg)
    }

    @Test fun `comparison operators`() {
        val p = parse("IF :x <= 0 [ STOP ]")
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val ifs = assertIs<Stmt.If>(p.items.single())
        assertEquals(BinOpKind.Le, assertIs<Expr.BinaryOp>(ifs.condition).op)
    }

    // ---------- recursion-friendly: `TREE :n - 1 :s * 0.7` ------------------

    @Test fun `recursive call with arithmetic args parses with correct arity`() {
        val src = """
            TO TREE :DEPTH :SIZE
              IF :DEPTH = 0 [ STOP ]
              FD :SIZE
              TREE :DEPTH - 1 :SIZE * 0.7
            END
        """.trimIndent()
        val p = parse(src)
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        val proc = assertIs<ProcedureDecl>(p.items.single())
        // Body: IF, FD, TREE
        val recursive = assertIs<Stmt.ProcedureCall>(proc.body.last())
        assertEquals("TREE", recursive.name.name)
        assertEquals(2, recursive.args.size)
        assertIs<Expr.BinaryOp>(recursive.args[0])
        assertIs<Expr.BinaryOp>(recursive.args[1])
    }

    // ---------- ranges -----------------------------------------------------

    @Test fun `procedure name range points only at the name`() {
        val p = parse("TO HELLO END")
        val proc = assertIs<ProcedureDecl>(p.items.single())
        assertEquals(3, proc.nameRange.start.offset)   // after "TO "
        assertEquals(8, proc.nameRange.end.offset)     // end of "HELLO"
    }

    // ---------- error recovery ---------------------------------------------

    @Test fun `unclosed bracket reports an error and does not crash`() {
        val p = parse("REPEAT 4 [ FD 50")
        assertTrue(p.errors.isNotEmpty())
        assertTrue(
            p.errors.any { "']'" in it.message },
            "expected ']' diagnostic, got ${p.errors}",
        )
    }

    @Test fun `missing END reports an error`() {
        val p = parse("TO SQUARE :SIDE FD :SIDE")
        assertTrue(p.errors.any { "END" in it.message }, "errors: ${p.errors}")
    }

    @Test fun `unknown procedure produces diagnostic but program continues`() {
        val p = parse(
            """
            UNKNOWNPROC 1 2
            FD 50
            """.trimIndent(),
        )
        // Two top-level stmts produced, plus one diagnostic for the unknown name.
        assertEquals(2, p.items.size)
        assertTrue(p.errors.any { "unknown procedure" in it.message })
    }

    @Test fun `error in one procedure does not poison the next`() {
        val src = """
            TO BAD :x
              REPEAT 4 [ FD :x
            END
            TO GOOD :y
              FD :y
            END
        """.trimIndent()
        val p = parse(src)
        // GOOD must still parse cleanly into a ProcedureDecl.
        val good = p.items.filterIsInstance<ProcedureDecl>().firstOrNull { it.name == "GOOD" }
        assertTrue(good != null, "GOOD should still parse despite earlier error")
        assertEquals(listOf("y"), good!!.parameters.map { it.name })
    }

    @Test fun `END outside a procedure is an error not a crash`() {
        val p = parse("END")
        assertTrue(p.errors.isNotEmpty())
        assertTrue(p.items.isEmpty())
    }

    // ---------- end-to-end --------------------------------------------------

    @Test fun `full SQUARE-and-call program`() {
        val src = """
            ; Draws a square of the requested side length.
            TO SQUARE :SIDE
              REPEAT 4 [
                FORWARD :SIDE
                RIGHT 90
              ]
            END

            SQUARE 100
            HOME
        """.trimIndent()
        val p = parse(src)
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        assertEquals(3, p.items.size)
        assertIs<ProcedureDecl>(p.items[0])
        assertIs<Stmt.ProcedureCall>(p.items[1])
        assertIs<Stmt.ProcedureCall>(p.items[2])
    }

    @Test fun `full fractal tree program`() {
        val src = """
            TO TREE :DEPTH :SIZE
              IF :DEPTH = 0 [ STOP ]
              FORWARD :SIZE
              LEFT 30
              TREE :DEPTH - 1 :SIZE * 0.7
              RIGHT 60
              TREE :DEPTH - 1 :SIZE * 0.7
              LEFT 30
              BACK :SIZE
            END
            MAKE "depth 6
            TREE :depth 80
        """.trimIndent()
        val p = parse(src)
        assertTrue(p.errors.isEmpty(), "errors: ${p.errors}")
        assertEquals(3, p.items.size)
    }
}
