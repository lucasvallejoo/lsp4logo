package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.analysis.Symbol
import io.github.lucasvallejoo.lsp4logo.lexer.Token
import io.github.lucasvallejoo.lsp4logo.lexer.TokenType
import io.github.lucasvallejoo.lsp4logo.lexer.Trivia
import io.github.lucasvallejoo.lsp4logo.lexer.TriviaKind
import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import io.github.lucasvallejoo.lsp4logo.util.Range
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend

/**
 * Encode a [DocumentSnapshot] as an LSP semantic-tokens payload.
 *
 * The output is the LSP-mandated delta-encoded `Int` quintuple stream:
 *
 * ```
 * [Δline, Δstart, length, tokenType, tokenModifiers, …]
 * ```
 *
 * Highlighting is **resolver-aware**: a `:x` reference is emitted as
 * `parameter` when it resolves to a procedure parameter, and as `variable`
 * otherwise. That is a small but visible signal to the reviewer that the
 * resolver and the highlighter share one source of truth.
 */
object SemanticTokens {

    /** The legend the server announces during `initialize`. */
    val legend: SemanticTokensLegend = SemanticTokensLegend(
        listOf(
            SemanticTokenTypes.Keyword,    // 0
            SemanticTokenTypes.Function,   // 1  procedure names (declarations + calls)
            SemanticTokenTypes.Parameter,  // 2  formal parameter declarations + references
            SemanticTokenTypes.Variable,   // 3  global variable references
            SemanticTokenTypes.Number,     // 4
            SemanticTokenTypes.Operator,   // 5
            SemanticTokenTypes.Comment,    // 6
            SemanticTokenTypes.String,     // 7  word literals ("name)
        ),
        emptyList(),
    )

    private const val TYPE_KEYWORD = 0
    private const val TYPE_FUNCTION = 1
    private const val TYPE_PARAMETER = 2
    private const val TYPE_VARIABLE = 3
    private const val TYPE_NUMBER = 4
    private const val TYPE_OPERATOR = 5
    private const val TYPE_COMMENT = 6
    private const val TYPE_STRING = 7

    fun encode(snapshot: DocumentSnapshot): SemanticTokens {
        val entries = collect(snapshot)
        val data = deltaEncode(entries)
        return SemanticTokens(data)
    }

    // --- internals -------------------------------------------------------

    /** A single, *absolute* (not yet delta-encoded) highlight entry. */
    internal data class Entry(
        val line: Int,
        val char: Int,
        val length: Int,
        val type: Int,
    )

    private fun collect(snapshot: DocumentSnapshot): List<Entry> {
        val out = mutableListOf<Entry>()
        for (tok in snapshot.tokens) {
            // Trivia (comments, mostly) precedes the token. Emit comments here so
            // they appear in source order with the token they precede.
            for (tri in tok.leadingTrivia) {
                if (tri.kind == TriviaKind.Comment) out += entryFor(tri.range, TYPE_COMMENT)
            }
            val type = semanticTypeFor(tok, snapshot) ?: continue
            out += entryFor(tok.range, type)
        }
        // Sort by (line, character) — required for delta encoding.
        out.sortWith(compareBy({ it.line }, { it.char }))
        return out
    }

    private fun entryFor(range: Range, type: Int): Entry = Entry(
        line = range.start.line,
        char = range.start.column,
        length = range.length,
        type = type,
    )

    private fun semanticTypeFor(tok: Token, snapshot: DocumentSnapshot): Int? = when (tok.type) {
        TokenType.TO, TokenType.END, TokenType.REPEAT, TokenType.IF -> TYPE_KEYWORD
        TokenType.NUMBER -> TYPE_NUMBER
        TokenType.WORD -> TYPE_STRING
        TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH,
        TokenType.EQUALS, TokenType.LESS, TokenType.GREATER,
        TokenType.LESS_EQUALS, TokenType.GREATER_EQUALS, TokenType.NOT_EQUALS -> TYPE_OPERATOR
        TokenType.IDENTIFIER -> TYPE_FUNCTION
        TokenType.VARIABLE -> classifyVariable(tok, snapshot)
        TokenType.LBRACKET, TokenType.RBRACKET, TokenType.LPAREN, TokenType.RPAREN,
        TokenType.EOF, TokenType.ERROR -> null
    }

    private fun classifyVariable(tok: Token, snapshot: DocumentSnapshot): Int {
        val binding = snapshot.resolved.bindingAtOffset(tok.range.start.offset)
        return when (binding?.symbol) {
            is Symbol.Variable.ParameterSym -> TYPE_PARAMETER
            is Symbol.Variable.Global -> TYPE_VARIABLE
            else -> TYPE_VARIABLE // unresolved still gets a `variable` colour for legibility
        }
    }

    /**
     * Convert a list of absolute [Entry]s into the LSP-mandated flat int array.
     * Five integers per entry: deltaLine, deltaStart, length, tokenType, modifiers (always 0 for now).
     */
    internal fun deltaEncode(entries: List<Entry>): List<Int> {
        val data = ArrayList<Int>(entries.size * 5)
        var prevLine = 0
        var prevChar = 0
        for (e in entries) {
            val deltaLine = e.line - prevLine
            val deltaChar = if (deltaLine == 0) e.char - prevChar else e.char
            data.add(deltaLine)
            data.add(deltaChar)
            data.add(e.length)
            data.add(e.type)
            data.add(0) // modifiers
            prevLine = e.line
            prevChar = e.char
        }
        return data
    }

    /** Token-type constants exposed for tests so they don't depend on string indices. */
    internal object TestSupport {
        const val keyword = TYPE_KEYWORD
        const val function = TYPE_FUNCTION
        const val parameter = TYPE_PARAMETER
        const val variable = TYPE_VARIABLE
        const val number = TYPE_NUMBER
        const val operator = TYPE_OPERATOR
        const val comment = TYPE_COMMENT
        const val string = TYPE_STRING
    }
}
