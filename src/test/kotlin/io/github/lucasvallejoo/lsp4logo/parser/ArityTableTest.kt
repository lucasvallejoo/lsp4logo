package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.lexer.Lexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArityTableTest {

    private fun scan(src: String): ArityTable = ArityTable.scan(Lexer.tokenize(src))

    @Test fun `built-ins are present`() {
        val t = scan("")
        assertEquals(1, t.arityOf("FORWARD"))
        assertEquals(1, t.arityOf("fd"))               // case-insensitive
        assertEquals(0, t.arityOf("HOME"))
        assertEquals(2, t.arityOf("MAKE"))
        assertEquals(1, t.arityOf("OUTPUT"))
    }

    @Test fun `scans a user-defined procedure with parameters`() {
        val t = scan("TO SQUARE :SIDE END")
        assertEquals(1, t.arityOf("SQUARE"))
    }

    @Test fun `arity matches the number of parameters`() {
        val t = scan("TO TREE :DEPTH :SIZE END")
        assertEquals(2, t.arityOf("TREE"))
    }

    @Test fun `zero-arg procedures are recognised`() {
        val t = scan("TO GREET END")
        assertEquals(0, t.arityOf("GREET"))
    }

    @Test fun `multiple procedures coexist`() {
        val t = scan(
            """
            TO SQUARE :SIDE END
            TO TRI :SIDE END
            TO TREE :DEPTH :SIZE END
            """.trimIndent(),
        )
        assertEquals(1, t.arityOf("SQUARE"))
        assertEquals(1, t.arityOf("TRI"))
        assertEquals(2, t.arityOf("TREE"))
    }

    @Test fun `unknown name returns null`() {
        val t = scan("")
        assertNull(t.arityOf("WHATEVER"))
    }

    @Test fun `user definitions override built-ins`() {
        val t = scan("TO FORWARD :a :b END")
        assertEquals(2, t.arityOf("FORWARD"))
    }
}
