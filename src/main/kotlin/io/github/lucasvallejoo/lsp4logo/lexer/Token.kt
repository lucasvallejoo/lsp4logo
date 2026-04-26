package io.github.lucasvallejoo.lsp4logo.lexer

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * A single lexical token.
 *
 * @property type      the token's classification
 * @property lexeme    the raw source text (for [TokenType.VARIABLE] this includes
 *                     the leading `':'`; for [TokenType.WORD] it includes the leading `"`)
 * @property range     half-open source range covering only the meaningful chars
 * @property leadingTrivia whitespace/comments that appeared *before* this token
 *                     and have not yet been attached to anything else
 */
data class Token(
    val type: TokenType,
    val lexeme: String,
    val range: Range,
    val leadingTrivia: List<Trivia> = emptyList(),
)
