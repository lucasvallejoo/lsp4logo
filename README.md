# lsp4logo

A Language Server Protocol implementation for **LOGO**, written in Kotlin.

> Test assignment for the JetBrains internship *Change signature refactoring support in LSP* (IDEA-385867).
> This file is the **story of the task**. Every architectural decision below has a *Why* — because for an internship that is half about engineering judgement, what was chosen matters as much as what was built.

---

## What this is

`lsp4logo` is an LSP server that turns plain `.logo` files into a modern editing experience: real-time syntax highlighting, go-to-declaration, parser/resolver diagnostics, completion, and **turtle-state inlay hints** that let a learner see where the turtle ends up after every statement — including a `home` label when a `REPEAT` block provably closes its shape.

It is built on three principles that came directly from the mentor's feedback:

1. **Quality over quantity** — fewer features, deeper engineering.
2. **Independent concurrent request handling** — every LSP request runs on a frozen snapshot of the document; readers and writers do not share mutable state.
3. **Test coverage that proves the contract** — including a stress test that hammers the document store from multiple threads.

| LSP capability | Status |
|---|---|
| `textDocument/semanticTokens/full` (resolver-aware syntax highlighting) | ✅ |
| `textDocument/definition` (go-to-declaration: procedures, parameters, globals) | ✅ |
| `textDocument/publishDiagnostics` (parser + resolver, push on every change) | ✅ |
| `textDocument/inlayHint` (concrete + symbolic turtle state) | ✅ |
| `textDocument/completion` (built-ins, user procedures, in-scope variables) | ✅ |
| `$/cancelRequest` (cooperative cancellation in every handler) | ✅ |

**173 / 173 tests passing**, including 3 dedicated concurrency tests and 18 abstract-interpreter tests.

---

## Quick start

Requires **JDK 21+**. Gradle's toolchain support will provision it automatically if it is not already on the host.

```bash
./gradlew build       # compile + run the test suite (173 tests)
./gradlew test        # tests only
./gradlew run         # start the LSP server on stdio (interactive)
./gradlew shadowJar   # produce the deliverable: build/libs/lsp4logo-0.1.0.jar
```

The fat-jar is the artefact a generic LSP client launches via `java -jar`.

### Try it without an editor

`scripts/demo.py` drives the server through one full LSP session against `samples/square.logo` and pretty-prints every response and notification:

```bash
./gradlew shadowJar
python3 scripts/demo.py
```

You will see the announced capabilities, the diagnostics push, the go-to-declaration target, the semantic-tokens int stream, and the symbolic turtle-state inlay hints — including the `home` label that the abstract interpreter emits when `REPEAT 4 [FD :SIDE RT 90]` closes the shape.

---

## How to connect the server to an LSP client

The server speaks vanilla LSP over stdio, so any generic LSP client works. We've targeted **[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)** as the primary client because it is what JetBrains' own tooling uses.

### LSP4IJ in IntelliJ IDEA

1. Build the fat-jar once: `./gradlew shadowJar`. It lands in `build/libs/lsp4logo-0.1.0.jar`.
2. In IntelliJ IDEA: *Settings → Plugins → Marketplace*, install **LSP4IJ**.
3. Open any folder that contains `.logo` files (use the bundled `samples/` directory).
4. *Settings → Languages & Frameworks → Language Servers → + (New)*:
   - **Name**: `lsp4logo`
   - **Command**: `java -jar /absolute/path/to/lsp4logo-0.1.0.jar`
   - **Mappings → File name patterns**: `*.logo`
5. Open `samples/square.logo`, `samples/tree.logo`, `samples/polygon.logo`, or `samples/diagnostics-demo.logo`. Highlighting, ⌘-click navigation, completion, and inlay hints all become live.

### Other generic clients

The same fat-jar drives any LSP client that supports a stdio command. The `demo.py` script in `scripts/` is a self-contained reference implementation of the protocol exchanges.

---

## Architecture

The codebase is organised by **layer of meaning**, not by LSP endpoint. Each layer has a single responsibility and is testable in isolation.

```
io.github.lucasvallejoo.lsp4logo
├── util/         Source positions, ranges, LSP <-> internal conversions
├── lexer/        Token stream with trivia (whitespace + comments) preserved
├── parser/       Two-pass recursive descent → AST + collected ParseErrors
├── ast/          Sealed Node hierarchy; every node carries a Range
├── analysis/     Resolver, symbol tables, abstract turtle interpreter
├── server/       LSP plumbing — DocumentStore, services, dispatch
└── features/     One file per LSP endpoint
                  (definition, semanticTokens, diagnostics, inlayHint, completion)
```

The data flow is one-way:

```
source text ──► Lexer ──► Parser ──► Resolver ──► ResolvedProgram (immutable)
                                                       │
                                ┌──────────────────────┼──────────────────────┐
                                ▼                      ▼                      ▼
                    Definition.locate(…)     SemanticTokens.encode(…)  InlayHints.compute(…)
                                ▲                      ▲                      ▲
                                └─────── all readers, every snapshot, in parallel ────────
```

### Why hand-written, not ANTLR

LOGO's grammar is small and we want absolute control over source ranges, error tokens, and trivia. ANTLR would add a runtime dependency, hide the very thing the mentors want to see, and make trivia preservation harder. The reviewer can read `Lexer.kt` and `Parser.kt` end to end in five minutes — that *is* the point.

### Why two-pass parsing

LOGO calls have **no parentheses around arguments**: `TREE :n - 1 :s * 0.7` is the recursive call to `TREE`, but the parser needs to know that `TREE` takes two arguments to know where the first one ends and the second one begins.

The parser solves this in two passes:

1. **`ArityTable.scan`** — sweeps the token stream once looking for `TO name :p1 :p2 … END` declarations. Adds each user procedure to a name → arity map (case-insensitive). Built-in arities (`FORWARD`=1, `MAKE`=2, `HOME`=0, …) are seeded.
2. **`Parser.parse`** — recursive descent that consults the arity table whenever it sees an identifier in statement or expression position, and consumes exactly that many argument expressions.

Forward references — calling a procedure before it is declared — work transparently because of pass 1.

### Why uniform AST

`MAKE`, `STOP`, `FORWARD`, and any user-defined procedure all collapse to a single AST shape: `Stmt.ProcedureCall(name, args)`. The only statement shapes that get their own type are `REPEAT` and `IF`, because their `[…]` block is not an expression. This uniformity buys one resolution rule, one inlay-hint visitor, one refactoring target — exactly what *Change Signature* will need.

### Why trivia preservation from day one

Every `Token` carries `leadingTrivia: List<Trivia>` — the whitespace and comments that appeared before it. This is overkill for syntax highlighting, but it is **exactly** what *Change Signature* (the actual internship topic) needs: a refactoring that adds, removes, or reorders parameters must edit code without disturbing the user's formatting. Building this in now costs almost nothing; retrofitting it later would touch every node.

### Why error recovery, not throw-on-first-error

LSP clients send the server *every keystroke*. The user spends most of their typing time in syntactically broken states. A parser that gives up on the first error blanks out the entire file's editor support. Our parser collects errors into `Program.errors` and synchronises on `TO` / `END` / `REPEAT` / `IF` / `]` / identifier — the rest of the file still produces a usable AST. Open `samples/diagnostics-demo.logo` to see all four kinds of error coexisting in the same file with the rest of the document still highlighted.

### Concurrent independence — the document store

The mentor's question — *"what would your server do if a highlighting request and a completion request arrived at the same time?"* — is answered structurally, not promissorily.

```
LSP client ─┬─► didChange (writer)            ──┐
            ├─► semanticTokens (reader)         │
            ├─► completion (reader)             │  ConcurrentHashMap<URI,
            ├─► inlayHint (reader)              │    AtomicReference<DocumentSnapshot>>
            └─► definition (reader)           ──┘
                          │
                          │  every reader: snapshotOf(uri).get()  // lock-free
                          │  every writer: ref.set(newSnapshot)   // atomic swap
                          ▼
                  ┌──────────────────────────────────────────┐
                  │ DocumentSnapshot (immutable)             │
                  │   uri, version, source                   │
                  │   tokens (List<Token>)                   │
                  │   resolved: ResolvedProgram              │
                  │     procedures, globals, parameters      │
                  │     bindings (one per name reference)    │
                  │     diagnostics (parser + resolver)      │
                  └──────────────────────────────────────────┘
```

Three properties hold by construction:

1. **A held snapshot is never mutated.** Every field is `val`; every container is read-only. A request handler that captures the snapshot on its first line can pass it across thread boundaries with no further synchronisation.
2. **Reads and writes do not contend.** `ConcurrentHashMap.get` and `AtomicReference.get/set` are lock-free. Two readers, or a reader plus a writer, do not block each other.
3. **Each result is tagged with the version it computed against.** Two simultaneous readers may legally see different versions if a `didChange` interleaves between their first-line reads — that is correct under LSP semantics; the client reconciles via the version numbers.

`DocumentStoreConcurrencyTest` demonstrates (1) and (2) under load: 6 reader threads and 2 writer threads cycling 500 iterations each, with structural assertions that every observed snapshot is internally consistent (source ↔ version ↔ binding ranges).

Each handler follows the same shape:

```kotlin
override fun definition(params): CompletableFuture<…> =
    CompletableFutures.computeAsync(executor) { cancel ->
        cancel.checkCanceled()
        val snapshot = store.snapshotOf(uri) ?: return@computeAsync …
        cancel.checkCanceled()
        Definition.locate(snapshot, params.position)
    }
```

The first line captures an immutable snapshot. Everything after that is pure computation against a frozen value.

### Resolver — the queryable knowledge surface

The Resolver runs immediately after parsing and produces a `ResolvedProgram` that every LSP feature reads from:

- `procedures` — built-ins followed by user-defined declarations (case-insensitive, user overrides built-in).
- `globalVariables` — names introduced by `MAKE "name …`.
- `parametersByOwner` — formal parameters keyed by their owning `ProcedureDecl`.
- `bindings` — one entry per name *reference* in the source: `(referenceRange, symbol)`.
- `diagnostics` — unresolved variables and other resolver-layer findings.

LOGO is traditionally dynamically scoped, but for an IDE we deliberately resolve names statically: parameters are visible only inside their owning procedure body, globals are visible everywhere from their declaration onward, and parameters shadow same-name globals. This is documented as an interpretation choice — the assignment notes say *"since LOGO lacks a strictly defined semantics, you are encouraged to apply your own interpretation in cases of ambiguity"*.

### Standout feature — turtle-state inlay hints

The headline feature. The mentor's email named "inlay hints" as one of the LSP requests they wanted to see; this is our answer.

After every statement in the source, the editor shows a ghosted hint summarising the turtle's state:

```
TO SQUARE :SIDE
  REPEAT 4 [
    FORWARD :SIDE                    ⟶ x=0  y=:SIDE  h=0°
    RIGHT 90                         ⟶ x=0  y=:SIDE  h=90°
  ]                                  ⟶ home
END

SQUARE 50                            ⟶ home
HOME                                 ⟶ home
```

The interesting bits:

- **Symbolic interpretation inside procedures.** Parameters are first-class symbolic values, so `FORWARD :SIDE` produces `y = :SIDE` rather than refusing to compute. The arithmetic is *affine* — `constant + Σ coeff·param` — which captures the overwhelming majority of LOGO patterns (`:SIZE * 0.7`, `:DEPTH - 1`, `:SIDE + 30`) while degrading gracefully to `?` for nonlinear cases (`:A * :B`, `sin(:angle)`).
- **REPEAT closure detection.** Concrete-count `REPEAT n [body]` is unrolled literally (cap `n ≤ 100`). When the unrolled iterations bring the turtle back to `(0, 0, 0°)` the formatter prints `home` — and that is exactly the property that distinguishes a square from a state-leaking 100°-turn-triangle. No special-case math; the structure of the abstract interpretation tells the truth.
- **IF + STOP guards are recognised.** `IF :depth = 0 [STOP]` keeps the post-IF state equal to the pre-IF state, modelling the canonical recursive-base-case pattern. IFs without an unconditional exit conservatively become Unknown.
- **Top-level `MAKE "name <concrete>` seeds known globals**, so a later `:name` reference resolves to a number rather than `?`.
- **Floating-point fuzz is snapped at the trig boundary.** `cos(90°)` returns `≈ 6e-17` on the JVM; without snapping, that fuzz propagates as a ghost `2e-16 · :SIDE` coefficient and breaks the closure-detection test. Snapping at the source keeps the rest of the math honest.

Everything above lives in `analysis/turtle/{SymbolicValue, TurtleState, AbstractInterpreter}.kt` and is fully testable without an LSP client. The `features/InlayHints.kt` adapter is twenty lines: walk the trace, format each entry, place a hint at the statement's end position. That clean separation between *understanding* and *presentation* is the architectural pattern that will make Change Signature plausible later.

### Completion

The other LSP request the mentor named. Three classes of suggestions, picked by looking at the character immediately before the cursor:

- After `:` — only **variables visible at this position** (parameters of the enclosing procedure plus any globals introduced by `MAKE`). The `insertText` strips the leading `:` so the trigger character is not duplicated.
- After `"` — **nothing**. The user is naming a fresh global with `MAKE "name`; suggesting existing names would be the wrong default.
- Anywhere else — **procedures** (built-in and user-defined) plus variables-with-leading-colon.

`detail` carries the arity (`(no args)`, `(1 arg)`, `(:DEPTH :SIZE)` for parametric procedures), and `documentation` reuses the prose from the same `BuiltIns` catalog the resolver consults — one source of truth for "what built-ins exist and what they do".

---

## Design drivers

Mid-bootstrap, the project mentor sent feedback that **reshaped the roadmap**. Three points stood out and now drive every choice in the code:

1. **Quality over quantity.** A small, polished feature set is preferred over a long list of half-finished features. Performance and correctness are the bar.
2. **Independent concurrent request handling.** *"What would your server do if a highlighting request and a completion request arrived at the same time?"* The answer must be: each request runs against an immutable snapshot of the document; there is no shared mutable state for them to fight over.
3. **Test coverage that proves the contract.** Not just unit tests for the parser — tests that *demonstrate* concurrent independence under load.

### What changed because of the feedback

- **Dropped:** geometry-aware "state-leak" diagnostic. The sample file `samples/state-leak.logo` is kept as future-work fodder — the *Why* of dropping it is here on purpose, because deciding what not to build is itself a design choice.
- **Added:** completion as a first-class feature (mentor-validated), and an explicit concurrency model with a stress test that proves it.
- **Re-prioritised:** the `DocumentStore` is no longer a passive bucket — it is the *centrepiece* of the architecture. Snapshots are immutable; readers and writers never block each other; every request handler operates on a frozen view of the world.

---

## Testing strategy

| Scope | Suite | Tests |
|---|---|---|
| Tokenisation, trivia, line/column tracking | `LexerTest` | 18 |
| Two-pass arity scanning | `ArityTableTest` | 7 |
| Parser, error recovery, AST shape, ranges | `ParserTest` | 23 |
| Real `samples/*.logo` end-to-end | `SamplesIntegrationTest` | 7 |
| Symbol model, scope rules, queries | `ResolverTest` | 18 |
| Symbolic arithmetic + format | `SymbolicValueTest` | 22 |
| Abstract interpreter — concrete, symbolic, REPEAT closure, IF/STOP | `AbstractInterpreterTest` | 18 |
| Document store CRUD + diagnostics combination | `DocumentStoreTest` | 6 |
| **Concurrency stress: 6 readers + 2 writers × 500 iterations** | **`DocumentStoreConcurrencyTest`** | **3** |
| LSP `Position` / `Range` conversion | `LspConversionsTest` | 7 |
| `Definition` feature | `DefinitionTest` | 7 |
| Diagnostic conversion + push | `DiagnosticsTest` | 5 |
| Semantic-tokens encoder | `SemanticTokensTest` | 10 |
| Inlay-hints feature, including `home` detection | `InlayHintsTest` | 9 |
| Completion contexts, detail, documentation | `CompletionTest` | 13 |
| **Total** |  | **173** |

The concurrency test is the architecturally interesting one: it spawns multiple readers and writers against one `DocumentStore`, hammers them for 500 iterations each, and asserts that every snapshot a reader observes is internally consistent — `source.length` matches the binding offsets, the version round-trips through the source, and no exception escapes. That is the structural answer to the mentor's question.

---

## Limitations and future work

These are explicit choices, not oversights:

- **No support for LOGO lists as data** (`[a b c]` as a value). The lexer lexes `[` and `]`, but the AST treats `[…]` strictly as a statement block. Full list support would require a uniform value model and a different evaluation strategy.
- **No inlining of user-defined procedure calls** in the abstract interpreter. After `SQUARE 50` the turtle state is `?`. Inlining would require fixed-point analysis over recursion and is out of scope.
- **No incremental parsing.** Every `didChange` reparses the whole document. For LOGO files this is sub-millisecond, but for a real production server (tens of thousands of lines, tree-sitter style) we would want incremental edits.
- **The geometry-aware "state-leak" diagnostic is intentionally not implemented.** The sample `samples/state-leak.logo` and the abstract interpreter's `TurtleState` machinery are the substrate it would build on. Adding it is roughly a 50-line `Diagnostics` extension that compares the turtle state at procedure entry and exit.

The architecture was deliberately built to make *Change Signature* — the actual internship topic — straightforward to add on top: every reference is already a `Binding`, every parameter is a first-class `Symbol`, trivia is preserved for round-trip-safe edits, and the document store guarantees no concurrent edit can race a refactor.

---

## License

MIT — see [LICENSE](LICENSE).

---

## Further reading

- [`docs/magazine/`](docs/magazine/) — the **Docs Magazine**: a separate, more lyrical companion to this README, focused on the user experience, the pedagogy of LOGO, and the design philosophy of the inlay-hint feature.
- [`scripts/demo.py`](scripts/demo.py) — runnable end-to-end LSP exchange that prints every response.
- [`samples/`](samples/) — `square.logo`, `tree.logo`, `polygon.logo`, `spiral.logo`, `state-leak.logo`, and `diagnostics-demo.logo`.
