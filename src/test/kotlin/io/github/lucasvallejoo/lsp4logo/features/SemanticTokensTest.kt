package io.github.lucasvallejoo.lsp4logo.features

import io.github.lucasvallejoo.lsp4logo.server.DocumentSnapshot
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTokensTest {

    private val uri = URI.create("file:///tmp/x.logo")

    private fun snap(src: String): DocumentSnapshot = DocumentSnapshot.analyse(uri, version = 1, source = src)

    /** The encoded data is a flat list of 5-int quintuples. */
    private fun quintuples(data: List<Int>): List<List<Int>> {
        check(data.size % 5 == 0) { "encoded data length ${data.size} is not a multiple of 5" }
        return data.chunked(5)
    }

    @Test fun `legend has the 8 expected token types in declared order`() {
        val types = SemanticTokens.legend.tokenTypes
        assertEquals(8, types.size)
        assertEquals("keyword", types[0])
        assertEquals("function", types[1])
        assertEquals("parameter", types[2])
        assertEquals("variable", types[3])
        assertEquals("number", types[4])
        assertEquals("operator", types[5])
        assertEquals("comment", types[6])
        assertEquals("string", types[7])
    }

    @Test fun `empty source produces no tokens`() {
        val data = SemanticTokens.encode(snap("")).data
        assertTrue(data.isEmpty())
    }

    @Test fun `single keyword TO is encoded as type 0 with deltas (0,0)`() {
        val data = SemanticTokens.encode(snap("TO F END")).data
        val q = quintuples(data)
        // First quintuple should be: deltaLine=0, deltaStart=0, length=2, type=keyword(0), mods=0
        assertEquals(listOf(0, 0, 2, SemanticTokens.TestSupport.keyword, 0), q[0])
    }

    @Test fun `TO and END both classify as keyword`() {
        val data = SemanticTokens.encode(snap("TO F END")).data
        val q = quintuples(data)
        // First TO, then F (function), then END (keyword)
        assertEquals(SemanticTokens.TestSupport.keyword, q.first()[3])
        assertEquals(SemanticTokens.TestSupport.keyword, q.last()[3])
    }

    @Test fun `procedure name is highlighted as function`() {
        val data = SemanticTokens.encode(snap("TO SQUARE END")).data
        val q = quintuples(data)
        // Sequence: TO(keyword), SQUARE(function), END(keyword)
        val types = q.map { it[3] }
        assertEquals(
            listOf(
                SemanticTokens.TestSupport.keyword,
                SemanticTokens.TestSupport.function,
                SemanticTokens.TestSupport.keyword,
            ),
            types,
        )
    }

    @Test fun `parameter reference is parameter, global reference is variable`() {
        val src = """
            MAKE "g 1
            TO F :p
              FD :p
              FD :g
            END
        """.trimIndent()
        val data = SemanticTokens.encode(snap(src)).data
        val q = quintuples(data)
        // Find every quintuple whose type is parameter and whose type is variable
        val hasParameter = q.any { it[3] == SemanticTokens.TestSupport.parameter }
        val hasVariable = q.any { it[3] == SemanticTokens.TestSupport.variable }
        assertTrue(hasParameter, "expected at least one 'parameter' token: $q")
        assertTrue(hasVariable, "expected at least one 'variable' token: $q")
    }

    @Test fun `comment trivia produces a comment token`() {
        val src = "; this is a comment\nFD 50"
        val data = SemanticTokens.encode(snap(src)).data
        val q = quintuples(data)
        // First entry must be the comment on line 0
        val first = q.first()
        assertEquals(0, first[0])  // deltaLine
        assertEquals(0, first[1])  // deltaStart
        assertEquals(SemanticTokens.TestSupport.comment, first[3])
    }

    @Test fun `entries are emitted in source order with correct delta encoding`() {
        // Two tokens on the same line: "FD 50"
        // Expected raw entries:
        //   FD: line=0, col=0, len=2, type=function
        //   50: line=0, col=3, len=2, type=number
        // Delta encoded:
        //   (0, 0, 2, function, 0)
        //   (0, 3, 2, number, 0)   ← deltaStart = 3 because same line as previous
        val data = SemanticTokens.encode(snap("FD 50")).data
        val q = quintuples(data)
        assertEquals(2, q.size)
        assertEquals(listOf(0, 0, 2, SemanticTokens.TestSupport.function, 0), q[0])
        assertEquals(listOf(0, 3, 2, SemanticTokens.TestSupport.number, 0), q[1])
    }

    @Test fun `multi-line input resets the deltaStart on a new line`() {
        // "FD 50\n  RT 90"
        // Entries:
        //   FD line=0 col=0 len=2 function
        //   50 line=0 col=3 len=2 number
        //   RT line=1 col=2 len=2 function
        //   90 line=1 col=5 len=2 number
        // Delta-encoded:
        //   (0,0,2,function,0)
        //   (0,3,2,number,0)
        //   (1,2,2,function,0)   ← deltaLine=1 ⇒ deltaStart is absolute (= 2)
        //   (0,3,2,number,0)
        val data = SemanticTokens.encode(snap("FD 50\n  RT 90")).data
        val q = quintuples(data)
        assertEquals(4, q.size)
        assertEquals(listOf(1, 2, 2, SemanticTokens.TestSupport.function, 0), q[2])
    }

    @Test fun `delta encoding round-trips manually computed entries`() {
        val entries = listOf(
            SemanticTokens.Entry(line = 0, char = 0, length = 2, type = 0),
            SemanticTokens.Entry(line = 0, char = 3, length = 2, type = 4),
            SemanticTokens.Entry(line = 2, char = 4, length = 5, type = 1),
        )
        val encoded = SemanticTokens.deltaEncode(entries)
        assertEquals(
            listOf(
                0, 0, 2, 0, 0,
                0, 3, 2, 4, 0,
                2, 4, 5, 1, 0,
            ),
            encoded,
        )
    }
}
