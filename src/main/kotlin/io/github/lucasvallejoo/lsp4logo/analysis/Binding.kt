package io.github.lucasvallejoo.lsp4logo.analysis

import io.github.lucasvallejoo.lsp4logo.util.Range

/**
 * The resolver's primary output unit: "this source range refers to that symbol".
 *
 * One [Binding] per *reference* in the source. A symbol that is referenced ten
 * times produces ten bindings — each with a distinct [referenceRange] but the
 * same [symbol].
 *
 * This shape is what go-to-declaration, find-references, hover, and rename all
 * read from. Keeping bindings as a flat list (rather than a tree) makes
 * position-based queries trivial and lets a position cursor scan the list with
 * a simple `firstOrNull` that the JIT will inline.
 */
data class Binding(
    /** Where the user can ⌘-click to invoke go-to-declaration. */
    val referenceRange: Range,
    /** What the reference resolves to. */
    val symbol: Symbol,
)
