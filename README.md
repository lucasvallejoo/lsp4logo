# lsp4logo

A Language Server Protocol implementation for **LOGO**, written in Kotlin.

> Test assignment for the JetBrains internship "Change signature refactoring support in LSP" (IDEA-385867).
> This file is the *story of the task*: every architectural decision below has a **Why** because the deliverables matter as much as the choices behind them.

---

## Status

| Phase | Description | State |
|---|---|---|
| 1 | Bootstrap (Gradle + LSP4J skeleton, `initialize` handshake) | ✅ |
| 2.1 | Hand-written lexer with trivia preservation | ✅ 18 tests |
| 2.2 | AST + two-pass recursive-descent parser with error recovery | ✅ 30 tests |
| 2.3 | End-to-end samples integration tests | ✅ 5 tests |
| **2.4** | **Pivot — respond to mentor feedback (this commit)** | ✅ |
| 3 | Resolver / SymbolTable + thread-safe DocumentStore (immutable snapshots) | ⏭ next |
| 4 | LSP endpoints — `semanticTokens`, `definition`, `publishDiagnostics`, each independently dispatched | ⏭ |
| 5 | Standout feature — turtle-state **inlay hints** (concrete + symbolic abstract interpretation) | ⏭ |
| 6 | Second feature — **completion** for built-ins, user procedures, and variables in scope | ⏭ |
| 6.5 | Concurrency stress test — concurrent `didChange` + `semanticTokens` + `completion` + `inlayHint`, no shared mutable state | ⏭ |
| 7 | Docs Magazine, polish, screenshots, fat-jar release | ⏭ |

**Total tests so far:** 53/53 passing.

---

## Design drivers

Mid-bootstrap, the project mentor sent feedback that **reshaped the roadmap**. Three points stood out and now drive every choice from here on:

1. **Quality over quantity.** A small, polished feature set is preferred over a long list of half-finished features. Performance and correctness are the bar.
2. **Independent concurrent request handling.** The mentor's exact prompt: *"what would your server do if a highlighting request and a completion request arrived at the same time?"* The answer must be: each request runs against an immutable snapshot of the document; there is no shared mutable state for them to fight over.
3. **Test coverage that proves the contract.** Not just unit tests for the parser — tests that *demonstrate* concurrent independence under load.

The roadmap above (and what was dropped — see below) is the response to that email.

### What changed because of the feedback

- **Dropped:** geometry-aware "state-leak" diagnostic, which was a creative-but-orthogonal feature. The sample file `samples/state-leak.logo` is kept as future-work fodder; the `Why` of dropping it is here on purpose, because *deciding what not to build* is itself a design choice.
- **Added:** completion as a first-class feature (mentor-validated), and an explicit concurrency model with a stress test that proves it.
- **Re-prioritised:** the `DocumentStore` is no longer a passive bucket — it is the *centrepiece* of the architecture. Snapshots are immutable; readers and writers never block each other; every request handler operates on a frozen view of the world.

---

## Quick start

Requires **JDK 21+** (the Gradle toolchain will provision JDK 21 automatically if it is not on the host).

```bash
./gradlew build       # compile + run the test suite
./gradlew test        # run tests only
./gradlew run         # start the LSP server on stdio (interactive)
./gradlew shadowJar   # produce build/libs/lsp4logo-<version>.jar (the deliverable)
```

The fat-jar is the artefact a generic LSP client (e.g. [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)) should launch via `java -jar`.

---

## Architecture (so far)

The codebase is organised by *layer of meaning*, not by LSP endpoint. Each layer has a single responsibility and is testable in isolation.

```
io.github.lucasvallejoo.lsp4logo
├── util/         Source positions and ranges (LSP-compatible)
├── lexer/        Token stream, with trivia (whitespace + comments) preserved
├── parser/       Two-pass recursive descent → AST + collected ParseErrors
├── ast/          Sealed Node hierarchy; every node carries a Range
├── analysis/     Resolver, symbol tables, abstract turtle interpreter (next)
├── server/       LSP plumbing — DocumentStore, services, dispatch (next)
└── features/     One file per LSP endpoint (semanticTokens, definition, …)
```

### Why hand-written, not ANTLR

LOGO's grammar is small and we want absolute control over source ranges, error tokens, and trivia. ANTLR would add a runtime dependency, hide the very thing the mentors want to see, and make trivia preservation harder. The reviewer can read `Lexer.kt` and `Parser.kt` end to end in five minutes — that *is* the point.

### Why two-pass parsing

LOGO calls have **no parentheses around arguments**: `TREE :n - 1 :s * 0.7` is the recursive call to `TREE`, but the parser needs to know that `TREE` takes two arguments to know where the first one ends and the second one begins.

The parser solves this in two passes:

1. **`ArityTable.scan`** — sweeps the token stream once looking for `TO name :p1 :p2 … END` declarations. Adds each user procedure to a name → arity map (case-insensitive). Built-in arities (`FORWARD`=1, `MAKE`=2, `HOME`=0, …) are seeded.
2. **`Parser.parse`** — recursive descent that consults the arity table whenever it sees an identifier in statement or expression position, and consumes exactly that many argument expressions.

Forward references — calling a procedure before it is declared — work transparently because of pass 1.

### Why uniform AST (`ProcedureCall` for everything)

`MAKE`, `STOP`, `FORWARD`, and any user-defined procedure all collapse to a single AST shape: `Stmt.ProcedureCall(name, args)`. The only statement shapes that get their own type are `REPEAT` and `IF`, because their `[…]` block is not an expression. This uniformity buys one resolution rule, one inlay-hint visitor, one refactoring target.

### Why trivia preservation from day one

Every `Token` carries `leadingTrivia: List<Trivia>` — the whitespace and comments that appeared before it. This is overkill for syntax highlighting, but it is **exactly** what *Change Signature* (the actual internship topic) needs: a refactoring that adds, removes, or reorders parameters must edit code without disturbing the user's formatting. Building this in now costs almost nothing; retrofitting it later would touch every node.

### Why error recovery, not throw-on-first-error

LSP clients send the server *every keystroke*. The user spends most of their typing time in syntactically broken states. A parser that gives up on the first error blanks out the entire file's editor support. Our parser collects errors into `Program.errors` and synchronises on `TO` / `END` / `REPEAT` / `IF` / `]` / identifier — the rest of the file still produces a usable AST.

*(The concurrency model and the DocumentStore design land in phase 3; this section grows then.)*

---

## How to connect the server to a client

Once a release is built (`./gradlew shadowJar`), point any generic LSP client at:

```
java -jar /path/to/lsp4logo-0.1.0.jar
```

Worked example with **LSP4IJ** in IntelliJ IDEA: *Settings → Languages & Frameworks → Language Server Protocol → +* → command `java -jar …`, file pattern `*.logo`. Detailed walkthrough lands with phase 4.

---

## License

MIT — see [LICENSE](LICENSE).
