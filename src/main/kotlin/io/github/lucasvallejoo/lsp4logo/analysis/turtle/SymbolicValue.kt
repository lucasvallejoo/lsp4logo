package io.github.lucasvallejoo.lsp4logo.analysis.turtle

import kotlin.math.cos
import kotlin.math.sin

/**
 * A scalar value the abstract interpreter knows about. Either:
 *
 *  - [Concrete] — a fully-known numeric value (`50`, `0.7`, `3.14`).
 *  - [Linear] — an affine expression in canonically-named parameters
 *    (`:SIDE`, `2 * :DEPTH`, `:N - 1`). Stored as `constant + Σ coefficient_i · param_i`.
 *  - [Unknown] — gave-up. Anything that touches an [Unknown] becomes [Unknown].
 *
 * Why affine, and not arbitrary? Because LOGO's typical procedure body —
 * `FORWARD :SIZE`, `RIGHT 90`, `TREE :DEPTH - 1 :SIZE * 0.7` — is *almost
 * always* affine in its parameters. Affine arithmetic captures the 99%, stays
 * cheap, and degrades gracefully to [Unknown] the moment we hit something
 * non-linear (variable × variable, sin of a variable, etc.).
 */
sealed interface SymbolicValue {

    data class Concrete(val value: Double) : SymbolicValue

    /**
     * `constant + Σ coefficient_k · :param_k`, with at least one non-zero term.
     * Keys in [terms] are canonical (upper-cased) parameter names.
     */
    data class Linear(val constant: Double, val terms: Map<String, Double>) : SymbolicValue {
        init {
            require(terms.isNotEmpty()) { "Linear with no terms is a Concrete; normalise upstream" }
        }
    }

    object Unknown : SymbolicValue {
        override fun toString(): String = "Unknown"
    }

    companion object {
        /**
         * Tolerance for treating a value as exactly zero. Required to absorb
         * the floating-point fuzz that creeps in through `cos(90°)` and
         * friends — without this, `REPEAT 4 [FD :SIDE RT 90]` would produce
         * coefficients like `±2e-16 · :SIDE` instead of the geometrically
         * correct zero, and the "shape closes" detection would fail.
         */
        private const val EPSILON: Double = 1e-9

        private fun snap(d: Double): Double = if (kotlin.math.abs(d) < EPSILON) 0.0 else d

        /** Build the simplest [SymbolicValue] for `constant + Σ coeff · :name`. */
        fun affine(constant: Double, terms: Map<String, Double>): SymbolicValue {
            val cleaned = terms.mapValues { snap(it.value) }.filterValues { it != 0.0 }
            val c = snap(constant)
            return if (cleaned.isEmpty()) Concrete(c) else Linear(c, cleaned)
        }

        /** A pure parameter reference: `1 · :name + 0`. */
        fun param(name: String): SymbolicValue = affine(0.0, mapOf(name to 1.0))
    }
}

// ---- arithmetic ---------------------------------------------------------

operator fun SymbolicValue.plus(other: SymbolicValue): SymbolicValue = when {
    this is SymbolicValue.Unknown || other is SymbolicValue.Unknown -> SymbolicValue.Unknown
    this is SymbolicValue.Concrete && other is SymbolicValue.Concrete -> SymbolicValue.Concrete(this.value + other.value)
    this is SymbolicValue.Concrete && other is SymbolicValue.Linear ->
        SymbolicValue.affine(this.value + other.constant, other.terms)
    this is SymbolicValue.Linear && other is SymbolicValue.Concrete ->
        SymbolicValue.affine(this.constant + other.value, this.terms)
    this is SymbolicValue.Linear && other is SymbolicValue.Linear -> {
        val combined = (this.terms.keys + other.terms.keys).associateWith { k ->
            (this.terms[k] ?: 0.0) + (other.terms[k] ?: 0.0)
        }
        SymbolicValue.affine(this.constant + other.constant, combined)
    }
    else -> error("unreachable")
}

operator fun SymbolicValue.unaryMinus(): SymbolicValue = when (this) {
    is SymbolicValue.Concrete -> SymbolicValue.Concrete(-this.value)
    is SymbolicValue.Linear -> SymbolicValue.affine(-this.constant, this.terms.mapValues { -it.value })
    SymbolicValue.Unknown -> SymbolicValue.Unknown
}

operator fun SymbolicValue.minus(other: SymbolicValue): SymbolicValue = this + (-other)

operator fun SymbolicValue.times(other: SymbolicValue): SymbolicValue = when {
    this is SymbolicValue.Unknown || other is SymbolicValue.Unknown -> SymbolicValue.Unknown
    this is SymbolicValue.Concrete && other is SymbolicValue.Concrete -> SymbolicValue.Concrete(this.value * other.value)
    this is SymbolicValue.Concrete && other is SymbolicValue.Linear -> scaleLinear(other, this.value)
    this is SymbolicValue.Linear && other is SymbolicValue.Concrete -> scaleLinear(this, other.value)
    this is SymbolicValue.Linear && other is SymbolicValue.Linear -> SymbolicValue.Unknown // would be quadratic
    else -> error("unreachable")
}

operator fun SymbolicValue.div(other: SymbolicValue): SymbolicValue = when {
    this is SymbolicValue.Unknown || other is SymbolicValue.Unknown -> SymbolicValue.Unknown
    other is SymbolicValue.Concrete && other.value == 0.0 -> SymbolicValue.Unknown
    this is SymbolicValue.Concrete && other is SymbolicValue.Concrete -> SymbolicValue.Concrete(this.value / other.value)
    this is SymbolicValue.Linear && other is SymbolicValue.Concrete -> scaleLinear(this, 1.0 / other.value)
    else -> SymbolicValue.Unknown
}

private fun scaleLinear(l: SymbolicValue.Linear, s: Double): SymbolicValue =
    if (s == 0.0) SymbolicValue.Concrete(0.0)
    else SymbolicValue.affine(l.constant * s, l.terms.mapValues { it.value * s })

// ---- transcendentals (degrees in, value out) -----------------------------
//
// `cos(90°)` and friends return tiny-but-non-zero floating-point fuzz on the
// JVM (≈ 6e-17). Without snapping, that fuzz propagates as ghost coefficients
// in subsequent Linear arithmetic — and the "REPEAT 4 returns home" test
// fails by `≈ 2e-16 · :SIDE`. Snapping to zero at the trig boundary keeps the
// rest of the math honest.

private const val TRIG_EPSILON: Double = 1e-9

private fun snapTrig(d: Double): Double = if (kotlin.math.abs(d) < TRIG_EPSILON) 0.0 else d

fun cosDeg(angle: SymbolicValue): SymbolicValue = when (angle) {
    is SymbolicValue.Concrete -> SymbolicValue.Concrete(snapTrig(cos(Math.toRadians(angle.value))))
    else -> SymbolicValue.Unknown
}

fun sinDeg(angle: SymbolicValue): SymbolicValue = when (angle) {
    is SymbolicValue.Concrete -> SymbolicValue.Concrete(snapTrig(sin(Math.toRadians(angle.value))))
    else -> SymbolicValue.Unknown
}

// ---- formatting ---------------------------------------------------------

/**
 * Render a value the way an editor inlay-hint should show it. Whole numbers
 * stay whole, fractions get two decimals, and tiny floating-point fuzz is
 * snapped to zero so that `cos(90°) = 6e-17` displays as `0`.
 */
fun SymbolicValue.format(): String = when (this) {
    is SymbolicValue.Concrete -> formatNumber(value)
    is SymbolicValue.Linear -> formatLinear(this)
    SymbolicValue.Unknown -> "?"
}

internal fun formatNumber(d: Double): String {
    val snapped = if (kotlin.math.abs(d) < 1e-9) 0.0 else d
    val asInt = snapped.toLong()
    return if (snapped == asInt.toDouble()) asInt.toString() else "%.2f".format(snapped)
}

private fun formatLinear(l: SymbolicValue.Linear): String {
    val sb = StringBuilder()
    if (l.constant != 0.0) sb.append(formatNumber(l.constant))
    for ((name, coef) in l.terms.entries.sortedBy { it.key }) {
        when {
            coef == 1.0 -> {
                if (sb.isNotEmpty()) sb.append("+")
                sb.append(":").append(name)
            }
            coef == -1.0 -> sb.append("-:").append(name)
            coef > 0 -> {
                if (sb.isNotEmpty()) sb.append("+")
                sb.append(formatNumber(coef)).append("·:").append(name)
            }
            else -> sb.append(formatNumber(coef)).append("·:").append(name)
        }
    }
    return sb.toString().ifEmpty { "0" }
}
