package io.github.lucasvallejoo.lsp4logo.analysis.turtle

import io.github.lucasvallejoo.lsp4logo.analysis.Resolver
import io.github.lucasvallejoo.lsp4logo.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbstractInterpreterTest {

    private fun trace(src: String): List<AbstractInterpreter.HintEntry> {
        val program = Parser.parse(src)
        val resolved = Resolver.resolve(program)
        val interp = AbstractInterpreter(resolved)
        // Concatenate top-level hints with each procedure body's hints, mirroring
        // what InlayHints.compute does in the real feature.
        val out = mutableListOf<AbstractInterpreter.HintEntry>()
        out += interp.traceTopLevel()
        for (item in resolved.program.items) {
            if (item is io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl) {
                out += interp.traceProcedure(item)
            }
        }
        return out
    }

    private fun lastConcrete(entries: List<AbstractInterpreter.HintEntry>): TurtleState =
        entries.last().stateAfter

    // ---- concrete movement ------------------------------------------------

    @Test fun `single FORWARD moves up the Y axis`() {
        val entries = trace("FD 50")
        val s = lastConcrete(entries)
        assertEquals(SymbolicValue.Concrete(0.0), s.x)
        assertEquals(SymbolicValue.Concrete(50.0), s.y)
        assertEquals(SymbolicValue.Concrete(0.0), s.heading)
    }

    @Test fun `RIGHT then FORWARD moves along the X axis`() {
        val entries = trace("RT 90 FD 50")
        val s = lastConcrete(entries)
        // After RT 90: heading 90°, position unchanged.
        // After FD 50 with heading 90°: x=50, y=0.
        assertEquals(50.0, (s.x as SymbolicValue.Concrete).value, 1e-9)
        assertEquals(0.0, (s.y as SymbolicValue.Concrete).value, 1e-9)
        assertEquals(90.0, (s.heading as SymbolicValue.Concrete).value, 1e-9)
    }

    @Test fun `BACK is the negation of FORWARD`() {
        val entries = trace("FD 50 BK 50")
        val s = lastConcrete(entries)
        assertEquals(0.0, (s.x as SymbolicValue.Concrete).value, 1e-9)
        assertEquals(0.0, (s.y as SymbolicValue.Concrete).value, 1e-9)
    }

    @Test fun `LEFT subtracts from heading`() {
        val entries = trace("LT 30")
        val h = lastConcrete(entries).heading
        // -30° normalises to 330°.
        assertEquals(330.0, (h as SymbolicValue.Concrete).value, 1e-9)
    }

    @Test fun `HOME resets x y and heading`() {
        val entries = trace("FD 50 RT 90 FD 50 HOME")
        assertEquals(TurtleState.initial(), lastConcrete(entries))
    }

    @Test fun `PENUP and PENDOWN toggle the pen`() {
        val entries = trace("PENUP FD 10 PENDOWN")
        // After PENUP: penDown=false; FD 10 keeps that; PENDOWN flips back.
        assertTrue(lastConcrete(entries).penDown)
        // Middle entry — after FD 10 — should have pen up.
        assertTrue(!entries[1].stateAfter.penDown)
    }

    // ---- symbolic ---------------------------------------------------------

    @Test fun `FORWARD with a parameter yields a Linear y coordinate`() {
        val src = """
            TO F :SIDE
              FD :SIDE
            END
        """.trimIndent()
        val entries = trace(src)
        val procEntry = entries.last() // the FD :SIDE inside F
        assertEquals(SymbolicValue.Concrete(0.0), procEntry.stateAfter.x)
        val y = assertIs<SymbolicValue.Linear>(procEntry.stateAfter.y)
        assertEquals(0.0, y.constant)
        assertEquals(mapOf("SIDE" to 1.0), y.terms)
    }

    @Test fun `multiplied parameter scales the coefficient`() {
        val src = """
            TO F :S
              FD :S * 2
            END
        """.trimIndent()
        val entries = trace(src)
        val y = assertIs<SymbolicValue.Linear>(entries.last().stateAfter.y)
        assertEquals(mapOf("S" to 2.0), y.terms)
    }

    @Test fun `parameter times parameter degrades to Unknown`() {
        val src = """
            TO F :A :B
              FD :A * :B
            END
        """.trimIndent()
        val entries = trace(src)
        assertEquals(SymbolicValue.Unknown, entries.last().stateAfter.y)
    }

    // ---- REPEAT closure detection ----------------------------------------

    @Test fun `REPEAT 4 with FD then RT 90 closes the shape`() {
        val src = """
            TO SQUARE :SIDE
              REPEAT 4 [ FD :SIDE RT 90 ]
            END
        """.trimIndent()
        val entries = trace(src)
        // The very last hint inside the procedure body should be the one for
        // the REPEAT statement itself, with state == initial (closed shape).
        val last = entries.last { it.statement is io.github.lucasvallejoo.lsp4logo.ast.Stmt.Repeat }
        assertEquals(TurtleState.initial(), last.stateAfter)
    }

    @Test fun `REPEAT 3 with FD then RT 100 does NOT close (intentional state-leak)`() {
        // Mirrors samples/state-leak.logo: 3 × 100° = 300°, not a full turn.
        val src = """
            TO TRI :SIDE
              REPEAT 3 [ FD :SIDE RT 100 ]
            END
        """.trimIndent()
        val entries = trace(src)
        val last = entries.last { it.statement is io.github.lucasvallejoo.lsp4logo.ast.Stmt.Repeat }
        // State must NOT equal the initial — that's the whole point of the sample.
        assertTrue(last.stateAfter != TurtleState.initial())
    }

    @Test fun `REPEAT 0 returns the state untouched`() {
        val entries = trace("REPEAT 0 [ FD 50 ]")
        // First (and only) hint is the REPEAT itself with state = initial.
        assertEquals(TurtleState.initial(), entries.first().stateAfter)
    }

    @Test fun `REPEAT with non-concrete count produces Unknown post-state`() {
        val src = """
            TO F :N
              REPEAT :N [ FD 10 ]
            END
        """.trimIndent()
        val entries = trace(src)
        val repeatEntry = entries.last { it.statement is io.github.lucasvallejoo.lsp4logo.ast.Stmt.Repeat }
        assertEquals(SymbolicValue.Unknown, repeatEntry.stateAfter.x)
    }

    // ---- IF: STOP guard pattern -------------------------------------------

    @Test fun `IF with STOP body keeps state equal to before the IF`() {
        val src = """
            TO F :D
              IF :D = 0 [ STOP ]
              FD 10
            END
        """.trimIndent()
        val entries = trace(src)
        // After IF: state should still be the initial state (since IF body
        // either STOPs the proc or does nothing visible).
        val ifEntry = entries.first { it.statement is io.github.lucasvallejoo.lsp4logo.ast.Stmt.If }
        assertEquals(TurtleState.initial(), ifEntry.stateAfter)
    }

    @Test fun `IF without STOP makes post-state Unknown`() {
        val src = """
            TO F :D
              IF :D = 0 [ FD 10 ]
            END
        """.trimIndent()
        val entries = trace(src)
        val ifEntry = entries.first { it.statement is io.github.lucasvallejoo.lsp4logo.ast.Stmt.If }
        assertEquals(SymbolicValue.Unknown, ifEntry.stateAfter.x)
    }

    // ---- user procedure call → Unknown -----------------------------------

    @Test fun `calling a user procedure makes state Unknown after the call`() {
        val src = """
            TO HELP END
            FD 10
            HELP
            FD 10
        """.trimIndent()
        val entries = trace(src)
        // After HELP: x=Unknown.
        val afterHelp = entries[1] // FD 10, HELP, FD 10 → indices 0..2
        assertEquals(SymbolicValue.Unknown, afterHelp.stateAfter.x)
    }

    // ---- known globals ----------------------------------------------------

    @Test fun `MAKE with a concrete expr seeds a known global value`() {
        val src = """
            MAKE "depth 6
            FD :depth
        """.trimIndent()
        val entries = trace(src)
        // After FD :depth, y = 6.
        val s = entries.last().stateAfter
        assertEquals(6.0, (s.y as SymbolicValue.Concrete).value, 1e-9)
    }

    // ---- determinism ------------------------------------------------------

    @Test fun `tracing the same source twice yields identical hints`() {
        val src = """
            TO SQUARE :SIDE
              REPEAT 4 [ FD :SIDE RT 90 ]
            END
            SQUARE 50
            HOME
        """.trimIndent()
        val a = trace(src).map { it.stateAfter }
        val b = trace(src).map { it.stateAfter }
        assertEquals(a, b)
    }
}
