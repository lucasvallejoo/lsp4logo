package io.github.lucasvallejoo.lsp4logo.lexer

/**
 * High-level grouping of token types.
 *
 * Mirrors the spirit of LSP's standard `SemanticTokenTypes` so that wiring up
 * syntax highlighting later is a one-step mapping. The lexer never produces
 * "function" or "parameter" because those distinctions are *semantic* and
 * belong to the resolver — not the lexer.
 */
enum class TokenCategory {
    Keyword,
    Identifier,
    Variable,
    Word,
    Number,
    Operator,
    Bracket,
    Paren,
    EndOfFile,
    Error,
}

/**
 * Lexical token kinds for LOGO.
 *
 * Only the four control-flow words ([TO], [END], [REPEAT], [IF]) are treated
 * as keywords because they alter the *parser's structural decisions*. Other
 * canonical commands (`FORWARD`, `MAKE`, `STOP`, …) are emitted as plain
 * [IDENTIFIER]s — LOGO allows redefining them, so reserving them at the lexer
 * layer would be a lie.
 */
enum class TokenType(val category: TokenCategory) {
    // Structural keywords ---------------------------------------------------
    TO(TokenCategory.Keyword),
    END(TokenCategory.Keyword),
    REPEAT(TokenCategory.Keyword),
    IF(TokenCategory.Keyword),

    // Identifiers & references ---------------------------------------------
    IDENTIFIER(TokenCategory.Identifier),
    VARIABLE(TokenCategory.Variable),     // ":name"  — variable reference
    WORD(TokenCategory.Word),             // "\"name" — name literal (e.g. MAKE "x 10)

    // Literals --------------------------------------------------------------
    NUMBER(TokenCategory.Number),

    // Operators -------------------------------------------------------------
    PLUS(TokenCategory.Operator),
    MINUS(TokenCategory.Operator),
    STAR(TokenCategory.Operator),
    SLASH(TokenCategory.Operator),
    EQUALS(TokenCategory.Operator),
    LESS(TokenCategory.Operator),
    GREATER(TokenCategory.Operator),
    LESS_EQUALS(TokenCategory.Operator),
    GREATER_EQUALS(TokenCategory.Operator),
    NOT_EQUALS(TokenCategory.Operator),

    // Punctuation -----------------------------------------------------------
    LBRACKET(TokenCategory.Bracket),
    RBRACKET(TokenCategory.Bracket),
    LPAREN(TokenCategory.Paren),
    RPAREN(TokenCategory.Paren),

    // Sentinels -------------------------------------------------------------
    EOF(TokenCategory.EndOfFile),
    ERROR(TokenCategory.Error),
}
