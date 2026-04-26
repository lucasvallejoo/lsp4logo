package io.github.lucasvallejoo.lsp4logo.parser

import io.github.lucasvallejoo.lsp4logo.ast.ProcedureDecl
import io.github.lucasvallejoo.lsp4logo.ast.Stmt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end smoke tests over the real `.logo` files we ship in the `samples`
 * directory.
 *
 * These tests guard against subtle regressions where a unit test still passes
 * but a complete real-world program no longer round-trips. They also serve as
 * a quick sanity check the maintainers can rerun after every change to the
 * lexer / parser / AST.
 */
class SamplesIntegrationTest {

    private val samplesDir: Path =
        Paths.get(System.getProperty("user.dir"), "samples").toAbsolutePath()

    private fun readSample(name: String): String =
        Files.readString(samplesDir.resolve(name))

    @Test fun `samples directory exists and is non-empty`() {
        assertTrue(Files.isDirectory(samplesDir), "missing samples dir at $samplesDir")
        val logoFiles = Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".logo") }.toList()
        }
        assertTrue(logoFiles.isNotEmpty(), "expected at least one .logo sample")
    }

    @Test fun `square_logo parses without errors`() {
        val program = Parser.parse(readSample("square.logo"))
        assertTrue(program.errors.isEmpty(), "errors: ${program.errors}")
        // SQUARE decl + SQUARE 100 + HOME
        assertEquals(3, program.items.size)
        val decl = assertIs<ProcedureDecl>(program.items[0])
        assertEquals("SQUARE", decl.name)
        assertEquals(listOf("SIDE"), decl.parameters.map { it.name })
    }

    @Test fun `tree_logo parses without errors and contains the expected calls`() {
        val program = Parser.parse(readSample("tree.logo"))
        assertTrue(program.errors.isEmpty(), "errors: ${program.errors}")
        // TREE decl + MAKE "depth 6 + TREE :depth 80
        assertEquals(3, program.items.size)
        val decl = assertIs<ProcedureDecl>(program.items[0])
        assertEquals("TREE", decl.name)
        assertEquals(listOf("DEPTH", "SIZE"), decl.parameters.map { it.name })

        val make = assertIs<Stmt.ProcedureCall>(program.items[1])
        assertEquals("MAKE", make.name.name)

        val driveCall = assertIs<Stmt.ProcedureCall>(program.items[2])
        assertEquals("TREE", driveCall.name.name)
        assertEquals(2, driveCall.args.size)
    }

    @Test fun `state-leak_logo parses without errors — the leak is semantic, not syntactic`() {
        // We *want* this to be a syntactically clean file. The leak (RIGHT 100
        // instead of 120) is exactly what feature C will diagnose later — it
        // would defeat the demo if the parser refused to even read the file.
        val program = Parser.parse(readSample("state-leak.logo"))
        assertTrue(program.errors.isEmpty(), "errors: ${program.errors}")
        val decl = assertIs<ProcedureDecl>(program.items.first())
        assertEquals("TRI", decl.name)
    }

    @Test fun `every shipped sample parses without errors`() {
        val logoFiles = Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".logo") }.sorted().toList()
        }
        val failures = logoFiles.mapNotNull { f ->
            val program = Parser.parse(Files.readString(f))
            if (program.errors.isEmpty()) null else f.fileName.toString() to program.errors
        }
        assertTrue(
            failures.isEmpty(),
            "samples produced parse errors:\n" + failures.joinToString("\n") { (n, errs) ->
                "  $n: ${errs.joinToString { it.message }}"
            },
        )
    }
}
