package io.github.lucasvallejoo.lsp4logo.server

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentStoreTest {

    private val uri = URI.create("file:///tmp/example.logo")

    @Test fun `open stores an analysed snapshot and increments openCount`() {
        val store = DocumentStore()
        assertEquals(0, store.openCount)
        val snap = store.open(uri, version = 1, source = "FD 50")
        assertEquals(1, store.openCount)
        assertEquals(1, snap.version)
        assertEquals("FD 50", snap.source)
        // Snapshot is fully analysed at insertion time.
        assertNotNull(snap.resolved)
    }

    @Test fun `update replaces the snapshot for an existing document`() {
        val store = DocumentStore()
        store.open(uri, 1, "FD 50")
        val updated = store.update(uri, 2, "BK 30")
        assertNotNull(updated)
        assertEquals(2, updated!!.version)
        assertEquals("BK 30", store.snapshotOf(uri)?.source)
    }

    @Test fun `update on an unknown URI is a tolerant no-op`() {
        val store = DocumentStore()
        val result = store.update(URI.create("file:///nope"), 1, "FD 50")
        assertNull(result)
        assertEquals(0, store.openCount)
    }

    @Test fun `close removes the document`() {
        val store = DocumentStore()
        store.open(uri, 1, "FD 50")
        store.close(uri)
        assertEquals(0, store.openCount)
        assertNull(store.snapshotOf(uri))
    }

    @Test fun `snapshotOf returns the same instance until update is called`() {
        val store = DocumentStore()
        store.open(uri, 1, "FD 50")
        val a = store.snapshotOf(uri)
        val b = store.snapshotOf(uri)
        assertSame(a, b, "expected lock-free reads to return the same atomic snapshot")
    }

    @Test fun `snapshot diagnostics combine parser and resolver`() {
        val store = DocumentStore()
        // ":x" is unresolved (resolver), and "REPEAT 4 [ FD 50" is missing the
        // closing bracket (parser). Both should appear in diagnostics.
        val snap = store.open(uri, 1, "REPEAT 4 [ FD :x")
        val sources = snap.diagnostics.map { it.source }.toSet()
        assertTrue("parser" in sources, "expected parser diagnostic, got ${snap.diagnostics}")
        assertTrue("resolver" in sources, "expected resolver diagnostic, got ${snap.diagnostics}")
    }
}
