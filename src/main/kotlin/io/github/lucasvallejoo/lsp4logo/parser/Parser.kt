package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.ast.BinOpKind
import io.github.lucasvallejoo.lsp4logo.ast.Expr
import io.github.lucasvallejoo.lsp4logo.ast.NameRef
import io.github.lucasvallejoo.lsp4logo.ast.Parameter
import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Program
import io.github.lucasvallejoo.lsp4logo.ast.Stmt
import io.github.lucasvallejoo.lsp4logo.ast.TopLevel
import io.github.lucasvallejoo.lsp4logo.lexer.Lexer
import io.github.lucasvallejoo.lsp4logo.lexer.Token
import io.github.lucasvallejoo.lsp4logo.lexer.TokenType
import io.github.lucasvallejoo.lsp4logo.util.Position
import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * Recursive-descent LOGO parser, second pass of the two-pass design.
 *
 * Pass 1 (the [ArityTable.scan] sweep) has already learned the arity of every
 * user-defined procedure, so when we see an [TokenType.IDENTIFIER] in either
 * statement or expression position we can immediately consume the right number
 * of arguments — no parentheses required.
 *
 * The parser is **error-recovering**: every diagnostic is appended to a list
 * and the parser synchronises on the next plausible boundary
 * ([sync]). This is what lets the LSP keep highlighting and navigating
 * the rest of the file while the user is mid-edit.
 */
class Parser private constructor(
    private val tokens: List<Token>,
    private val arities: ArityTable,
) {

    private var pos = 0
    private val errors = mutableListOf<ParseError>()

    // ---------- token cursor primitives ------------------------------------

    private fun peek(distance: Int = 0): Token = tokens[(pos + distance).coerceAtMost(tokens.size - 1)]
    private fun current(): Token = peek()
    private fun atEnd(): Boolean = current().type == TokenType.EOF

    private fun advance(): Token = tokens[pos].also { pos++ }
    private fun check(type: TokenType): Boolean = !atEnd() && current().type == type

    private fun expect(type: TokenType, what: String): Token? {
        if (check(type)) return advance()
        report(current().range, "expected $what but found '${describe(current())}'")
        return null
    }

    private fun report(range: Range, message: String) {
        errors.add(ParseError(message, range))
    }

    private fun describe(tok: Token): String =
        if (tok.type == TokenType.EOF) "end of file"
        else tok.lexeme.ifEmpty { tok.type.name }

    // ---------- top level --------------------------------------------------

    private fun parseProgram(): Program {
        val items = mutableListOf<TopLevel>()
        val start = if (tokens.isNotEmpty()) tokens.first().range.start else Position.START
        while (!atEnd()) {
            val item = parseTopLevel() ?: continue
            items.add(item)
        }
        val end = current().range.end
        return Program(items, errors.toList(), Range(start, end))
    }

    private fun parseTopLevel(): TopLevel? = when (current().type) {
        TokenType.TO -> parseProcedureDecl()
        TokenType.END -> {
            report(current().range, "unexpected 'END' outside of a procedure")
            advance()
            null
        }
        else -> parseStmt()
    }

    private fun parseProcedureDecl(): ProcedureDecl? {
        val toTok = advance() // consume 'TO'
        val nameTok = expect(TokenType.IDENTIFIER, "procedure name") ?: run { sync(); return null }

        val params = mutableListOf<Parameter>()
        while (check(TokenType.VARIABLE)) {
            val v = advance()
            // strip the leading ':'
            params.add(Parameter(name = v.lexeme.removePrefix(":"), range = v.range))
        }

        val body = mutableListOf<Stmt>()
        while (!atEnd() && !check(TokenType.END) && !check(TokenType.TO)) {
            val stmt = parseStmt() ?: continue
            body.add(stmt)
        }

        val endTok = expect(TokenType.END, "'END' to close procedure '${nameTok.lexeme}'")
        val endPos = endTok?.range?.end ?: current().range.end
        return ProcedureDecl(
            name = nameTok.lexeme,
            nameRange = nameTok.range,
            parameters = params.toList(),
            body = body.toList(),
            range = Range(toTok.range.start, endPos),
        )
    }

    // ---------- statements -------------------------------------------------

    private fun parseStmt(): Stmt? = when (current().type) {
        TokenType.REPEAT -> parseRepeat()
        TokenType.IF -> parseIf()
        TokenType.IDENTIFIER -> parseProcedureCallStmt()
        else -> {
            report(current().range, "expected a statement but found '${describe(current())}'")
            sync()
            null
        }
    }

    private fun parseRepeat(): Stmt.Repeat? {
        val kw = advance() // REPEAT
        val count = parseExpr() ?: run { sync(); return null }
        val lbr = expect(TokenType.LBRACKET, "'[' after REPEAT count") ?: run { sync(); return null }
        val body = parseBlockBody()
        val rbr = expect(TokenType.RBRACKET, "']' to close REPEAT block")
        val end = rbr?.range?.end ?: lbr.range.end
        return Stmt.Repeat(count, body, Range(kw.range.start, end))
    }

    private fun parseIf(): Stmt.If? {
        val kw = advance() // IF
        val cond = parseExpr() ?: run { sync(); return null }
        val lbr = expect(TokenType.LBRACKET, "'[' after IF condition") ?: run { sync(); return null }
        val body = parseBlockBody()
        val rbr = expect(TokenType.RBRACKET, "']' to close IF block")
        val end = rbr?.range?.end ?: lbr.range.end
        return Stmt.If(cond, body, Range(kw.range.start, end))
    }

    private fun parseBlockBody(): List<Stmt> {
        val body = mutableListOf<Stmt>()
        while (!atEnd() && !check(TokenType.RBRACKET) && !check(TokenType.END) && !check(TokenType.TO)) {
            val s = parseStmt() ?: continue
            body.add(s)
        }
        return body.toList()
    }

    private fun parseProcedureCallStmt(): Stmt.ProcedureCall {
        val nameTok = advance()
        val name = NameRef(nameTok.lexeme, nameTok.range)
        val arity = arities.arityOf(nameTok.lexeme) // null = unknown procedure
        val args = collectArgs(arity, nameTok.lexeme)
        val end = args.lastOrNull()?.range?.end ?: nameTok.range.end
        return Stmt.ProcedureCall(name, args, Range(nameTok.range.start, end))
    }

    /**
     * Consume [arity] argument expressions. If the arity is `null` (unknown
     * procedure), report an error and consume *zero* args — this gives the
     * resolver something to flag, while keeping the rest of the file parseable.
     */
    private fun collectArgs(arity: Int?, name: String): List<Expr> {
        if (arity == null) {
            report(current().range, "unknown procedure '$name' — declare it with TO/END or use a built-in")
            return emptyList()
        }
        val args = mutableListOf<Expr>()
        repeat(arity) {
            val arg = parseExpr()
            if (arg == null) {
                report(current().range, "missing argument ${args.size + 1} for '$name' (expected $arity)")
                return args.toList()
            }
            args.add(arg)
        }
        return args.toList()
    }

    // ---------- expressions (Pratt-ish recursive descent) ------------------
    //
    // Precedence, lowest → highest:
    //   1. comparison:    = < > <= >= <>     (left-assoc)
    //   2. additive:      + -                (left-assoc)
    //   3. multiplicative * /                (left-assoc)
    //   4. unary:         -<expr>
    //   5. primary:       number | :var | "word | proc-call | ( expr )

    private fun parseExpr(): Expr? = parseComparison()

    private fun parseComparison(): Expr? {
        var left = parseAdditive() ?: return null
        while (true) {
            val op = comparisonOp(current().type) ?: break
            val opTok = advance()
            val right = parseAdditive() ?: run {
                report(opTok.range, "missing right-hand side of '${opTok.lexeme}'")
                return left
            }
            left = Expr.BinaryOp(left, op, opTok.range, right, Range(left.range.start, right.range.end))
        }
        return left
    }

    private fun parseAdditive(): Expr? {
        var left = parseMultiplicative() ?: return null
        while (true) {
            val op = when (current().type) {
                TokenType.PLUS -> BinOpKind.Add
                TokenType.MINUS -> BinOpKind.Sub
                else -> break
            }
            val opTok = advance()
            val right = parseMultiplicative() ?: run {
                report(opTok.range, "missing right-hand side of '${opTok.lexeme}'")
                return left
            }
            left = Expr.BinaryOp(left, op, opTok.range, right, Range(left.range.start, right.range.end))
        }
        return left
    }

    private fun parseMultiplicative(): Expr? {
        var left = parseUnary() ?: return null
        while (true) {
            val op = when (current().type) {
                TokenType.STAR -> BinOpKind.Mul
                TokenType.SLASH -> BinOpKind.Div
                else -> break
            }
            val opTok = advance()
            val right = parseUnary() ?: run {
                report(opTok.range, "missing right-hand side of '${opTok.lexeme}'")
                return left
            }
            left = Expr.BinaryOp(left, op, opTok.range, right, Range(left.range.start, right.range.end))
        }
        return left
    }

    private fun parseUnary(): Expr? {
        if (check(TokenType.MINUS)) {
            val minus = advance()
            val operand = parseUnary() ?: run {
                report(minus.range, "missing operand for unary '-'")
                return null
            }
            return Expr.UnaryMinus(operand, Range(minus.range.start, operand.range.end))
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr? = when (current().type) {
        TokenType.NUMBER -> {
            val t = advance()
            Expr.NumberLit(t.lexeme.toDouble(), t.range)
        }
        TokenType.VARIABLE -> {
            val t = advance()
            Expr.VariableRef(t.lexeme.removePrefix(":"), t.range)
        }
        TokenType.WORD -> {
            val t = advance()
            Expr.WordLit(t.lexeme.removePrefix("\""), t.range)
        }
        TokenType.LPAREN -> {
            val open = advance()
            val inner = parseExpr() ?: run {
                report(open.range, "expected expression inside '(' … ')'"); return null
            }
            val close = expect(TokenType.RPAREN, "')' to close grouping")
            val end = close?.range?.end ?: inner.range.end
            Expr.Grouping(inner, Range(open.range.start, end))
        }
        TokenType.IDENTIFIER -> parseProcedureCallExpr()
        else -> {
            report(current().range, "expected an expression but found '${describe(current())}'")
            null
        }
    }

    private fun parseProcedureCallExpr(): Expr.ProcedureCall {
        val nameTok = advance()
        val name = NameRef(nameTok.lexeme, nameTok.range)
        val arity = arities.arityOf(nameTok.lexeme)
        val args = collectArgs(arity, nameTok.lexeme)
        val end = args.lastOrNull()?.range?.end ?: nameTok.range.end
        return Expr.ProcedureCall(name, args, Range(nameTok.range.start, end))
    }

    private fun comparisonOp(t: TokenType): BinOpKind? = when (t) {
        TokenType.EQUALS -> BinOpKind.Eq
        TokenType.LESS -> BinOpKind.Lt
        TokenType.GREATER -> BinOpKind.Gt
        TokenType.LESS_EQUALS -> BinOpKind.Le
        TokenType.GREATER_EQUALS -> BinOpKind.Ge
        TokenType.NOT_EQUALS -> BinOpKind.Ne
        else -> null
    }

    // ---------- error recovery --------------------------------------------

    /**
     * Skip ahead to the next plausible synchronisation point: a procedure
     * boundary (`TO` / `END`), a closing bracket, or another statement-starter.
     * This lets us continue parsing after a local error instead of giving up
     * on the whole file.
     */
    private fun sync() {
        while (!atEnd()) {
            when (current().type) {
                TokenType.TO, TokenType.END,
                TokenType.REPEAT, TokenType.IF,
                TokenType.RBRACKET -> return
                TokenType.IDENTIFIER -> return
                else -> advance()
            }
        }
    }

    companion object {
        /** Parse a token stream into an AST, given a pre-computed arity table. */
        fun parse(tokens: List<Token>, arities: ArityTable): Program =
            Parser(tokens, arities).parseProgram()

        /** Convenience: lex + Pass-1 arity scan + Pass-2 parse, all in one. */
        fun parse(source: String): Program {
            val tokens = Lexer.tokenize(source)
            val arities = ArityTable.scan(tokens)
            return parse(tokens, arities)
        }
    }
}
