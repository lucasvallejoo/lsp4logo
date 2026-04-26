package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.lexer.Token
import io.github.lucasvallejoo.lsp4logo.lexer.TokenType

/**
 * Map from procedure name (case-insensitive) to its arity.
 *
 * LOGO calls have no parentheses around arguments, so to parse `TREE :n - 1 :s`
 * the parser must already know that `TREE` takes two arguments. We solve this
 * with a **two-pass** strategy:
 *
 *  1. [scan] sweeps the token stream once looking for `TO name :p1 :p2 … END`
 *     declarations and adds each user-defined procedure to the table.
 *  2. The full parser then runs with this table in hand.
 *
 * The set of *built-in* arities is hard-coded in [BUILT_INS] — these are the
 * canonical LOGO commands we support. Names are folded to upper-case so that
 * `forward`, `Forward`, and `FORWARD` are equivalent (LOGO is case-insensitive).
 */
class ArityTable private constructor(
    private val arities: Map<String, Int>,
) {

    /** @return the declared arity, or `null` if the name is unknown. */
    fun arityOf(name: String): Int? = arities[name.uppercase()]

    /** Whether the table knows about [name] at all. */
    fun knows(name: String): Boolean = arities.containsKey(name.uppercase())

    companion object {
        /**
         * Built-in commands recognised by the parser. Anything outside this
         * set must be a user-defined procedure (or an undefined-name error).
         */
        val BUILT_INS: Map<String, Int> = mapOf(
            // Movement (1 arg: distance / degrees)
            "FORWARD" to 1, "FD" to 1,
            "BACK" to 1, "BK" to 1,
            "RIGHT" to 1, "RT" to 1,
            "LEFT" to 1, "LT" to 1,
            "SETHEADING" to 1, "SETH" to 1,
            // Pen state (0 args)
            "PENUP" to 0, "PU" to 0,
            "PENDOWN" to 0, "PD" to 0,
            "HIDETURTLE" to 0, "HT" to 0,
            "SHOWTURTLE" to 0, "ST" to 0,
            "HOME" to 0,
            "CLEARSCREEN" to 0, "CS" to 0,
            // Variables / control (special-shape but uniform arity)
            "MAKE" to 2,        // MAKE "name value
            "STOP" to 0,
            "OUTPUT" to 1, "OP" to 1,
            // I/O
            "PRINT" to 1, "PR" to 1,
        )

        /**
         * Build an arity table from a token stream.
         *
         * The scan is deliberately lenient: it does not validate the body of
         * each procedure (that is the parser's job). It only needs to recognise
         * the *shape* of `TO <ident> [:param]* … END` to learn the arity.
         */
        fun scan(tokens: List<Token>): ArityTable {
            val merged = HashMap<String, Int>(BUILT_INS)
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                if (tok.type == TokenType.TO) {
                    val nameTok = tokens.getOrNull(i + 1)
                    if (nameTok?.type == TokenType.IDENTIFIER) {
                        var arity = 0
                        var j = i + 2
                        while (j < tokens.size && tokens[j].type == TokenType.VARIABLE) {
                            arity++; j++
                        }
                        merged[nameTok.lexeme.uppercase()] = arity
                        i = j
                        continue
                    }
                }
                i++
            }
            return ArityTable(merged)
        }
    }
}
