# lsp4logo

A Language Server Protocol implementation for **LOGO**, written in Kotlin.

> Test assignment for the JetBrains internship "Change signature refactoring support in LSP".
> The README will grow into the full design story as the project progresses — this file is the skeleton.

## Status

Day 1 — bootstrap. Build green, server boots, no features yet.

## Quick start

Requires JDK 21+ (the Gradle toolchain will provision JDK 21 automatically if you don't have it).

```bash
./gradlew build
./gradlew run        # starts the LSP server on stdio
./gradlew shadowJar  # produces build/libs/lsp4logo-<version>.jar
```

## Roadmap

- [ ] Lexer + Parser + AST with source ranges
- [ ] Symbol table & resolver (binds references to declarations)
- [ ] LSP: semantic tokens (syntax highlighting)
- [ ] LSP: go-to-declaration
- [ ] LSP: parse-error diagnostics
- [ ] **Standout feature 1** — turtle-state inlay hints (concrete + symbolic)
- [ ] **Standout feature 2** — geometry-aware diagnostics (state-leak detection)
- [ ] Docs Magazine

## Architecture

To be filled in with the *Why* of every decision as we make them.

## License

MIT — see [LICENSE](LICENSE).
