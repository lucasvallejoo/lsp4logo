package io.github.lucasvallejoo.lsp4logo.server

import io.github.lucasvallejoo.lsp4logo.analysis.Diagnostic
import io.github.lucasvallejoo.lsp4logo.analysis.ResolvedProgram
import io.github.lucasvallejoo.lsp4logo.analysis.Resolver
import io.github.lucasvallejoo.lsp4logo.lexer.Lexer
import io.github.lucasvallejoo.lsp4logo.lexer.Token
import io.github.lucasvallejoo.lsp4logo.parser.ArityTable
import io.github.lucasvallejoo.lsp4logo.parser.Parser
import java.net.URI

/**
 * An *immutable* analysed view of a single document at one point in time.
 *
 * The whole point of having this type is the architectural answer to the
 * mentor's question — *"what would your server do if highlighting and
 * completion arrived at the same time?"*. The answer is: each request handler
 * captures one of these instances and computes against it. Two handlers
 * holding the same snapshot can run on different threads with no
 * coordination because there is *literally nothing to coordinate over* —
 * every field is a `val` pointing to an immutable structure.
 *
 * Two handlers holding different snapshots (because a `didChange` happened
 * between them) also cannot interfere: each is reasoning about a fixed,
 * self-consistent view of one [version].
 */
data class DocumentSnapshot(
    val uri: URI,
    val version: Int,
    val source: String,
    val tokens: List<Token>,
    val resolved: ResolvedProgram,
) {
    /** Aggregated diagnostics — parser errors and resolver diagnostics combined. */
    val diagnostics: List<Diagnostic> = run {
        val fromParser = resolved.program.errors.map(Diagnostic::fromParseError)
        val fromResolver = resolved.diagnostics
        fromParser + fromResolver
    }

    companion object {
        /**
         * Build a fresh snapshot by running the full pipeline:
         * lex → arity-scan → parse → resolve. Pure, deterministic.
         */
        fun analyse(uri: URI, version: Int, source: String): DocumentSnapshot {
            val tokens = Lexer.tokenize(source)
            val arities = ArityTable.scan(tokens)
            val program = Parser.parse(tokens, arities)
            val resolved = Resolver.resolve(program)
            return DocumentSnapshot(
                uri = uri,
                version = version,
                source = source,
                tokens = tokens,
                resolved = resolved,
            )
        }
    }
}
