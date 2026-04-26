package io.github.lucasvallejoo.lsp4logo.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerTest {

    private fun typesOf(src: String): List<TokenType> = Lexer.tokenize(src).map { it.type }
    private fun nonEofTypes(src: String): List<TokenType> = typesOf(src).dropLast(1)
    private fun lexemes(src: String): List<String> = Lexer.tokenize(src).dropLast(1).map { it.lexeme }

    @Test fun `empty input yields only EOF`() {
        val tokens = Lexer.tokenize("")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.EOF, tokens.single().type)
        assertTrue(tokens.single().leadingTrivia.isEmpty())
    }

    @Test fun `whitespace becomes leading trivia of EOF`() {
        val eof = Lexer.tokenize("   \n\t").single()
        assertEquals(TokenType.EOF, eof.type)
        assertEquals(1, eof.leadingTrivia.size)
        assertEquals(TriviaKind.Whitespace, eof.leadingTrivia.single().kind)
    }

    @Test fun `recognizes the four structural keywords`() {
        assertEquals(
            listOf(TokenType.TO, TokenType.END, TokenType.REPEAT, TokenType.IF),
            nonEofTypes("TO END REPEAT IF"),
        )
    }

    @Test fun `keywords are case-insensitive`() {
        assertEquals(TokenType.TO, Lexer.tokenize("to").first().type)
        assertEquals(TokenType.END, Lexer.tokenize("End").first().type)
        assertEquals(TokenType.REPEAT, Lexer.tokenize("Repeat").first().type)
        assertEquals(TokenType.IF, Lexer.tokenize("if").first().type)
    }

    @Test fun `built-in commands are emitted as IDENTIFIER`() {
        // FORWARD, FD, RIGHT, MAKE, STOP, OUTPUT are NOT reserved at the lexer level.
        val types = nonEofTypes("FORWARD FD RIGHT MAKE STOP OUTPUT HOME CS")
        assertTrue(types.all { it == TokenType.IDENTIFIER }, "got $types")
    }

    @Test fun `variable references are colon-prefixed`() {
        val tokens = Lexer.tokenize(":SIZE :n :a.b")
        assertEquals(TokenType.VARIABLE, tokens[0].type)
        assertEquals(":SIZE", tokens[0].lexeme)
        assertEquals(":n", tokens[1].lexeme)
        assertEquals(":a.b", tokens[2].lexeme)
    }

    @Test fun `word literals are quote-prefixed`() {
        val tokens = Lexer.tokenize("\"x \"name")
        assertEquals(TokenType.WORD, tokens[0].type)
        assertEquals("\"x", tokens[0].lexeme)
        assertEquals("\"name", tokens[1].lexeme)
    }

    @Test fun `recognizes integer and decimal numbers including leading dot`() {
        assertEquals(listOf("100", "0.5", ".25"), lexemes("100 0.5 .25"))
        assertTrue(nonEofTypes("100 0.5 .25").all { it == TokenType.NUMBER })
    }

    @Test fun `does not glue minus to digits — unary minus is the parser's job`() {
        // Source: -5 must produce two tokens MINUS, NUMBER (5).
        // The parser decides whether MINUS is unary or binary.
        assertEquals(
            listOf(TokenType.MINUS, TokenType.NUMBER),
            nonEofTypes("-5"),
        )
    }

    @Test fun `recognizes all operators including multi-character ones`() {
        assertEquals(
            listOf(
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH,
                TokenType.EQUALS, TokenType.LESS, TokenType.GREATER,
                TokenType.LESS_EQUALS, TokenType.GREATER_EQUALS, TokenType.NOT_EQUALS,
            ),
            nonEofTypes("+ - * / = < > <= >= <>"),
        )
    }

    @Test fun `recognizes brackets and parens`() {
        assertEquals(
            listOf(TokenType.LBRACKET, TokenType.RBRACKET, TokenType.LPAREN, TokenType.RPAREN),
            nonEofTypes("[ ] ( )"),
        )
    }

    @Test fun `comments are captured as trivia of the next token`() {
        val tokens = Lexer.tokenize("; this is a comment\nFD 50")
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        assertEquals("FD", tokens[0].lexeme)
        // Trivia: comment + newline whitespace
        val kinds = tokens[0].leadingTrivia.map { it.kind }
        assertEquals(listOf(TriviaKind.Comment, TriviaKind.Whitespace), kinds)
        assertEquals("; this is a comment", tokens[0].leadingTrivia[0].text)
    }

    @Test fun `tracks line and column accurately across newlines`() {
        val tokens = Lexer.tokenize("FD 50\n  RT 90")
        assertEquals(0, tokens[0].range.start.line)   // FD
        assertEquals(0, tokens[0].range.start.column)
        assertEquals(1, tokens[2].range.start.line)   // RT  (after newline)
        assertEquals(2, tokens[2].range.start.column) // after 2 spaces of indent
    }

    @Test fun `unknown character yields ERROR token but lexing continues`() {
        val tokens = Lexer.tokenize("FD @ 50")
        val types = tokens.map { it.type }
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.ERROR, TokenType.NUMBER, TokenType.EOF),
            types,
        )
    }

    @Test fun `ranges are half-open and length-correct`() {
        val tokens = Lexer.tokenize("FORWARD")
        val tok = tokens.first()
        assertEquals(0, tok.range.start.offset)
        assertEquals(7, tok.range.end.offset)
        assertEquals(7, tok.range.length)
    }

    @Test fun `tokenizes a complete procedure end-to-end`() {
        val src = """
            TO SQUARE :SIDE
              REPEAT 4 [ FD :SIDE RT 90 ]
            END
        """.trimIndent()
        assertEquals(
            listOf(
                TokenType.TO, TokenType.IDENTIFIER, TokenType.VARIABLE,
                TokenType.REPEAT, TokenType.NUMBER, TokenType.LBRACKET,
                TokenType.IDENTIFIER, TokenType.VARIABLE,
                TokenType.IDENTIFIER, TokenType.NUMBER,
                TokenType.RBRACKET, TokenType.END,
            ),
            nonEofTypes(src),
        )
    }

    @Test fun `tokenizes MAKE with a word literal target`() {
        val tokens = Lexer.tokenize("MAKE \"x 10")
        assertEquals(TokenType.IDENTIFIER, tokens[0].type) // MAKE
        assertEquals(TokenType.WORD, tokens[1].type)       // "x
        assertEquals(TokenType.NUMBER, tokens[2].type)     // 10
    }

    @Test fun `tokenizes a comparison expression`() {
        assertEquals(
            listOf(TokenType.VARIABLE, TokenType.LESS_EQUALS, TokenType.NUMBER),
            nonEofTypes(":depth <= 0"),
        )
    }
}
