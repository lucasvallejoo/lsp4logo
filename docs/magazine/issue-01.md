# lsp4logo — Issue №01

## *A turtle, a tree, and a typed editor.*

> A small magazine about a small language server. About what it feels like to teach a 1968 language new tricks, and what modern editors give back to a learner who is just trying to draw a square.

---

## In this issue

1. **[The 1968 in the room](#1-the-1968-in-the-room)** — what LOGO is, why it taught a generation, and why "still alive" is not the same as "still useful".
2. **[How an inlay hint became pedagogy](#2-how-an-inlay-hint-became-pedagogy)** — the design story of the headline feature, from "wouldn't it be nice if you could see where the turtle is" to a small symbolic abstract interpreter.
3. **[Two requests, one moment in time](#3-two-requests-one-moment-in-time)** — concurrent independence in the document store, written for someone who has never seen `AtomicReference` before.
4. **[An editorial: what we did not build](#4-an-editorial-what-we-did-not-build)** — and why that decision is itself a feature.
5. **[Colophon](#5-colophon)** — references, acknowledgements, license.

---

## 1. The 1968 in the room

Seymour Papert designed LOGO in 1968 with a small claim and a big argument. The small claim: that *all* programming languages secretly assume the user already thinks like a computer, and that fact is doing an enormous amount of damage to children's confidence around mathematics. The big argument: that you could make a language whose primitive object — a small triangle on a screen with a position and a direction — was already so anthropomorphic, so embodied, that thinking like the computer no longer required the child to leave their own body behind.

> "In teaching the computer how to think, children embark on an exploration about how they themselves think."
> — *Mindstorms*, 1980.

That bet won't work everywhere; reasonable people argue about how it scales, and what happens after the squares and the spirals. But what is undeniable is that millions of people still learn to write `FORWARD 50` as their first instruction to a machine, and an enormous fraction of them do it through a UI that — by 2026 standards — would be considered cruel for any other language.

A typical learner's editor for LOGO is roughly:

- A monospaced text box.
- A canvas.
- A "run" button.

Compared to what a Python or Kotlin learner gets, this is plain. There is no **squiggly red line** under a typo. There is no **⌘-click** to jump from a procedure call to its declaration. There is no **dropdown of available commands** when the user hesitates over the keyboard. There is no **whisper of where the turtle currently stands**, mid-procedure, before the run button is even pressed.

A modern Language Server Protocol implementation gives a learning language all of those for free, in any editor. Not because LOGO needs IDE-grade tooling to work — it manifestly does not — but because the absence of those affordances is exactly the friction that turns "I am learning to think with the machine" into "I made a typo and now nothing works and I don't know why".

This project is what an LSP server for LOGO can look like when the goal is not "tick the box on the assignment" but "design something pedagogically humane, written in code clean enough to be defended at an interview."

> *Aside.* The assignment that prompted this project — "build an LSP for LOGO" — is not, in itself, the JetBrains internship topic. The internship is *Change signature refactoring support in LSP*. The LOGO server is the entry exam: a chance to demonstrate that the candidate can read a protocol spec, design a small language toolchain, and reason about IDE-grade editing under realistic constraints. Treating it as a portfolio piece rather than a homework is, in a sense, also part of the answer.

---

## 2. How an inlay hint became pedagogy

The first time you write a procedure in LOGO that doesn't behave the way you expected, you fall off the same cliff that every learner since 1968 has fallen off. You wrote:

```
TO TRI :SIDE
  REPEAT 3 [
    FORWARD :SIDE
    RIGHT 100
  ]
END
```

You wanted a triangle. You got an inscrutable bowtie that drifted off the screen. The reason — that 100° is not 120°, that 3 × 100 = 300, that 360 − 300 = 60 of "leftover" rotation per cycle — is mathematically completely clear in retrospect. In the moment, it is opaque, because the missing piece of information is invisible: where, exactly, *was* the turtle when the loop ended? Where was it pointing? What did the next iteration *start from*?

A normal language can fix this with a debugger. You step through, you read variables, you keep your breath. LOGO is graphical, embodied, and meant for an eight-year-old. Asking the eight-year-old to set a breakpoint is not the right move.

There is a better move. The LSP spec defines **inlay hints**: small ghosted annotations the editor renders beside source code, after the code is laid out but before any input is typed. Originally inlay hints were popularised by Rust-Analyzer for showing inferred types. We can borrow the same idea for showing **inferred turtle state**.

```
TO SQUARE :SIDE
  REPEAT 4 [
    FORWARD :SIDE                   ⟶ x=0  y=:SIDE  h=0°
    RIGHT 90                        ⟶ x=0  y=:SIDE  h=90°
  ]                                 ⟶ home
END
```

That last line is the magic. The editor is telling you, before you've run anything, that this `REPEAT` block returns the turtle to where it started. Compare:

```
TO TRI :SIDE
  REPEAT 3 [
    FORWARD :SIDE                   ⟶ x=0  y=:SIDE  h=0°
    RIGHT 100                       ⟶ x=0  y=:SIDE  h=100°
  ]                                 ⟶ x=-1.45·:SIDE  y=0.5·:SIDE  h=300°
END
```

There is the bowtie, in symbolic form, before the run button has been pressed. **The bug is now visible, and the eight-year-old does not need a debugger to see it.**

### What had to happen for this to be possible

The hint above hides three pieces of engineering, none of them trivial.

The first is that the interpreter has to handle **symbolic values**. `:SIDE` is not a number — it is a parameter that becomes a number only at the call site. A naïve interpreter would refuse to evaluate `FORWARD :SIDE` at all. Our interpreter handles values in three modes:

- **Concrete** — a real number we know everything about (e.g., `90`).
- **Linear** — an affine expression in named parameters: *constant + Σ coefficient · parameter*. This captures every common LOGO arithmetic pattern: `:SIZE * 0.7`, `:SIDE - 30`, `:DEPTH + 1`.
- **Unknown** — anything else.

These three modes have a complete arithmetic. `Concrete + Concrete = Concrete`. `Concrete · Linear = Linear` (it scales the coefficients). `Linear · Linear = Unknown` (it would be quadratic, which we deliberately refuse). `Unknown` is contagious through every operator, the way `null` is in some languages — once you've lost certainty, you don't pretend to recover it.

The second piece is that the interpreter has to handle **`REPEAT` blocks**. We could be clever about this — solve the affine equations across the loop and produce a closed-form post-state. We deliberately did not. Instead, we **unroll the loop literally** for any concrete count up to 100. This is structurally simpler, it costs essentially nothing on real LOGO programs (one-iteration counts are tiny), and it lets the property "the shape closes" emerge from the actual arithmetic instead of from a special case. After 4 unrolled iterations of `[FORWARD :SIDE; RIGHT 90]`, the position vector reduces to *(0, 0)* and the heading reduces to *0°*. We don't need to *prove* the shape closes — we just *observe* that it did.

The third piece is mundane and the most likely place for the whole edifice to silently fail. `cos(90°)` on the JVM does not return exactly zero. It returns `6.123 × 10⁻¹⁷`. After four loop iterations of arithmetic, that fuzz had compounded into a phantom `2 × 10⁻¹⁶ · :SIDE` term in the *x* coordinate, and our test for "the square closes" started failing for a reason that had nothing to do with the geometry. The fix — snap any value below `10⁻⁹` to zero, *at the trig boundary, exactly once* — is six lines of code and was the difference between "this works" and "this almost works, and we don't quite know why".

> *Pull quote.* Floating-point fuzz is the kind of bug that, once you have hit it, you start placing snap-to-zero gates in your code reflexively. It shows up everywhere geometry meets arithmetic, and only in retrospect does it look obvious.

### The home label

When the abstract interpreter's post-block state has *x = 0, y = 0, heading = 0°, pen down*, the formatter does not print `x=0  y=0  h=0°`. It prints `home`.

This is the smallest-possible feature in the whole project — five characters in a `format()` function — and arguably the most important. It is the moment the tool stops describing the turtle's *position* and starts giving the user a *concept* they can act on. **"My loop closes. My triangle does not. The triangle is the bug."** The eight-year-old does not need to be told what a coordinate is. They need to be told that the turtle came home.

That is what designing for pedagogy looks like. It looks like cheap code, well placed.

---

## 3. Two requests, one moment in time

There is a question that the project mentor asked, in plain English, in their feedback email: *"What would your server do if a highlighting request and a completion request arrived at the same time?"*

The honest answer most LSP submissions would give, if pressed, is "I don't know — probably they wait for each other; let me check." The mentor's question is a polite way of saying: *that answer is not good enough, and the architecture should make it impossible for it to be true.*

Here is the architecture in one diagram.

```
   Editor                                     Server
   ──────                                     ──────

   user types one keystroke
   ┌─────────────────────────►  didChange ──┐
   │                                        ▼
   │                              ┌──────────────────────────┐
   │                              │   compute new snapshot   │
   │                              │   (lex → parse → resolve)│
   │                              └──────────────────────────┘
   │                                        │
   │                                        ▼  AtomicReference.set
   │                              ┌──────────────────────────┐
   │                              │    DocumentSnapshot      │
   │                              │    (immutable)           │
   │                              └──────────────────────────┘
   │                                  ▲       ▲       ▲
   semanticTokens request ────────────┘       │       │
   completion     request ────────────────────┘       │
   inlayHint      request ────────────────────────────┘
                  three readers, all working in parallel,
                  each holding a frozen value, none of them
                  blocking, none of them able to corrupt
                  the others.
```

The point is the immutability. **The reason two simultaneous request handlers cannot interfere is not that we wrote a careful lock around the right structure** — it is that the structure they are reading is *literally incapable of being mutated* once the reader has captured a reference to it. There is nothing to lock. There is nothing to corrupt.

When `didChange` arrives, the writer doesn't *modify* the document — it *replaces* the document. The new snapshot is computed entirely off-thread. The replacement is one atomic store. Any reader that captured the old snapshot before the swap sees the old snapshot, *forever*, and it is internally consistent because the lexer, parser, and resolver all ran against the same source string.

In one sentence: **two LSP requests on the same document cannot interfere because they don't share data — they share a pointer to the same frozen value, and pointers aren't dangerous.**

### What this gives the user

This is why an LSP user never feels their editor "lock up" while typing — even on a server that is doing real analysis. The request that fires from a fast cursor movement is computed against whatever document existed at the moment the request was issued. If, three milliseconds later, the user types another keystroke and a fresh request fires, the *new* request gets its own snapshot. Both responses come back, tagged with their version, and the editor reconciles. There is no queue. There is no contention.

For LOGO files, where the entire pipeline is microseconds, this is invisible — but the architecture is a habit. A habit that *will* matter when this same engine has to support Change Signature, where the refactoring needs to compute its preview while the user is still typing, on a thousand-line file, without the cursor stuttering.

> *Sidebar.* The test that proves this property in the repository spawns six reader threads and two writer threads. Each thread runs five hundred iterations. The writer encodes the document version into the source itself, and the readers assert that whatever snapshot they see, its source matches its version exactly, and every binding range is within bounds. The test runs in roughly thirty milliseconds. If, one day, somebody refactors the document store and breaks immutability, this test fails immediately and visibly, on every CI run.

---

## 4. An editorial: what we did not build

There was a feature called the *state-leak diagnostic*. The plan, before the mentor's feedback arrived, was: when a user-defined procedure exits with the turtle in a different position or heading than it entered, flag it as a warning. *"This procedure leaks turtle state — the turtle is at (50, 30) when it returns instead of where it started."* The diagnostic would have been creative, distinctive, easy to demo, and unique in the LSP world.

It was cut.

Two weeks before the deadline, the mentor wrote: *"It is better to focus on the performance and correctness rather than provide a rich set of features. We prefer quality over quantity."* That sentence was the most expensive piece of feedback in the project, and not because it was difficult to act on — *because it was easy*. The temptation, when you have a clever feature in your back pocket, is to ship it anyway. *They didn't say I couldn't.* But they did say something more important: that the bar of evaluation was about *judgement*, not *list length*.

The state-leak diagnostic stayed cut. The substrate it would have been built on — the abstract interpreter, the `TurtleState` model, the per-procedure entry/exit comparison — *all* shipped, because they were also the substrate the inlay-hint feature needed. The sample file `samples/state-leak.logo` is in the repository as a quiet acknowledgement that the diagnostic *could* be added in roughly fifty more lines, and a deliberate choice not to add them.

> *Pull quote.* The hardest thing about engineering judgement is that the right answer often looks, in your commit history, exactly like *not having done something*. Half the work in this project is in the files. The other half is in the files that are not there.

What was added in its place was a second feature explicitly named in the mentor's email — completion — and a stress test that proves the concurrency contract under load. The first is mentor-validated. The second answers a question the mentor asked. Both are visible improvements; the cut is invisible, except in the README which lists it on purpose.

This is what "quality over quantity" looks like in practice: it looks like restraint, documented.

---

## 5. Colophon

This magazine was written between phase 5 and the final commit, after the code was already done, in the same Markdown the README is written in.

### Acknowledgements

- Seymour Papert, for designing a language that an eight-year-old can think with.
- The Eclipse LSP4J team, for a JSON-RPC implementation that lets the surface of the codebase be the *language*, not the *protocol*.
- The project mentor, whose feedback email turned a scattered "do many features" project into a focused "do few features properly" project.

### References & further reading

- *Mindstorms: Children, Computers, and Powerful Ideas* — Seymour Papert, 1980. The book that argues for what LOGO was, and remains, *for*.
- The [Language Server Protocol specification](https://microsoft.github.io/language-server-protocol/) — the contract every client and server in this ecosystem speaks.
- [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) — the JetBrains-built LSP client used to verify this server visually during development.
- [The Berkeley LOGO reference manual](https://people.eecs.berkeley.edu/~bh/v2-manual.pdf) — the closest thing to a formal LOGO spec, deferred to whenever this project encountered ambiguity.
- IDEA-385867 — the JetBrains internship topic this LSP server is the entry exam for.

### License

MIT, like the rest of the project. See [`../../LICENSE`](../../LICENSE).
