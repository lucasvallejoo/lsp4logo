package io.github.lucasvallejoo.lsp4logo.analysis.turtle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SymbolicValueTest {

    private val zero = SymbolicValue.Concrete(0.0)
    private val ten = SymbolicValue.Concrete(10.0)
    private val side = SymbolicValue.param("SIDE")  // 1 · :SIDE
    private val depth = SymbolicValue.param("DEPTH")
    private val unknown = SymbolicValue.Unknown

    // ---- factory normalisation -------------------------------------------

    @Test fun `affine collapses to Concrete when no terms`() {
        val v = SymbolicValue.affine(constant = 7.0, terms = mapOf("X" to 0.0))
        assertEquals(SymbolicValue.Concrete(7.0), v)
    }

    @Test fun `affine drops zero-coefficient terms`() {
        val v = SymbolicValue.affine(0.0, mapOf("A" to 1.0, "B" to 0.0))
        val l = assertIs<SymbolicValue.Linear>(v)
        assertEquals(setOf("A"), l.terms.keys)
    }

    // ---- arithmetic ------------------------------------------------------

    @Test fun `concrete + concrete is concrete`() {
        assertEquals(SymbolicValue.Concrete(15.0), SymbolicValue.Concrete(5.0) + ten)
    }

    @Test fun `concrete + linear keeps linear with new constant`() {
        val sum = ten + side
        val l = assertIs<SymbolicValue.Linear>(sum)
        assertEquals(10.0, l.constant)
        assertEquals(mapOf("SIDE" to 1.0), l.terms)
    }

    @Test fun `linear + linear sums coefficients per term`() {
        val sum = side + depth + side
        val l = assertIs<SymbolicValue.Linear>(sum)
        assertEquals(0.0, l.constant)
        assertEquals(mapOf("SIDE" to 2.0, "DEPTH" to 1.0), l.terms)
    }

    @Test fun `linear minus self collapses to zero`() {
        assertEquals(SymbolicValue.Concrete(0.0), side - side)
    }

    @Test fun `concrete times linear scales the linear`() {
        val v = SymbolicValue.Concrete(3.0) * side
        val l = assertIs<SymbolicValue.Linear>(v)
        assertEquals(mapOf("SIDE" to 3.0), l.terms)
    }

    @Test fun `linear times linear is Unknown`() {
        assertEquals(SymbolicValue.Unknown, side * depth)
    }

    @Test fun `linear divided by concrete scales the linear`() {
        val v = side / SymbolicValue.Concrete(2.0)
        val l = assertIs<SymbolicValue.Linear>(v)
        assertEquals(mapOf("SIDE" to 0.5), l.terms)
    }

    @Test fun `division by zero is Unknown`() {
        assertEquals(SymbolicValue.Unknown, ten / zero)
    }

    @Test fun `multiplying by zero is Concrete zero`() {
        assertEquals(SymbolicValue.Concrete(0.0), zero * side)
    }

    @Test fun `unary minus negates concrete`() {
        assertEquals(SymbolicValue.Concrete(-10.0), -ten)
    }

    @Test fun `unary minus negates linear`() {
        val v = -(SymbolicValue.affine(3.0, mapOf("SIDE" to 2.0)))
        val l = assertIs<SymbolicValue.Linear>(v)
        assertEquals(-3.0, l.constant)
        assertEquals(mapOf("SIDE" to -2.0), l.terms)
    }

    @Test fun `Unknown is contagious through every operator`() {
        assertEquals(SymbolicValue.Unknown, unknown + ten)
        assertEquals(SymbolicValue.Unknown, ten + unknown)
        assertEquals(SymbolicValue.Unknown, unknown * side)
        assertEquals(SymbolicValue.Unknown, -unknown)
    }

    // ---- transcendentals -------------------------------------------------

    @Test fun `cos and sin of zero are 1 and 0`() {
        assertEquals(1.0, (cosDeg(SymbolicValue.Concrete(0.0)) as SymbolicValue.Concrete).value, 1e-9)
        assertEquals(0.0, (sinDeg(SymbolicValue.Concrete(0.0)) as SymbolicValue.Concrete).value, 1e-9)
    }

    @Test fun `cos and sin of 90 degrees are 0 and 1`() {
        assertEquals(0.0, (cosDeg(SymbolicValue.Concrete(90.0)) as SymbolicValue.Concrete).value, 1e-9)
        assertEquals(1.0, (sinDeg(SymbolicValue.Concrete(90.0)) as SymbolicValue.Concrete).value, 1e-9)
    }

    @Test fun `cos and sin of a symbolic angle are Unknown`() {
        assertEquals(SymbolicValue.Unknown, cosDeg(side))
        assertEquals(SymbolicValue.Unknown, sinDeg(side))
    }

    // ---- formatting ------------------------------------------------------

    @Test fun `whole numbers format without decimals`() {
        assertEquals("10", SymbolicValue.Concrete(10.0).format())
    }

    @Test fun `tiny floating-point fuzz snaps to zero`() {
        assertEquals("0", SymbolicValue.Concrete(6.123e-17).format())
    }

    @Test fun `linear formats with operator-friendly spacing`() {
        // 3 + 2·:SIDE - :DEPTH
        val v = SymbolicValue.affine(3.0, mapOf("SIDE" to 2.0, "DEPTH" to -1.0))
        // Terms are emitted in alphabetical order: DEPTH then SIDE
        assertEquals("3-:DEPTH+2·:SIDE", v.format())
    }

    @Test fun `pure parameter prints as colon name`() {
        assertEquals(":SIDE", side.format())
    }

    @Test fun `Unknown prints as question mark`() {
        assertEquals("?", SymbolicValue.Unknown.format())
    }
}
