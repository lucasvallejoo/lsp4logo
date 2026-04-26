package io.github.lucasvallejoo.lsp4logo.lexer

import io.github.lucasvallejoo.lsp4logo.util.Range

/** What kind of "non-meaningful" source span this trivia represents. */
enum class TriviaKind { Whitespace, Comment }

/**
 * A span of source text that does not carry semantic meaning — whitespace or
 * a comment — but that we still want to preserve verbatim.
 *
 * Why we keep these: refactorings like *Change Signature* must edit code
 * without disturbing the user's formatting. Storing trivia attached to tokens
 * lets us round-trip the document.
 */
data class Trivia(
    val kind: TriviaKind,
    val text: String,
    val range: Range,
)
