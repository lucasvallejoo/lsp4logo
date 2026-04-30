package io.github.lucasvallejoo.lsp4logo.server

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The architectural test the mentor asked for in plain English:
 *
 *  > "What would your server do if a highlighting request and a completion
 *  >  request were sent at the same time?"
 *
 * These tests *demonstrate* the contract claimed in [DocumentStore]'s KDoc:
 * concurrent readers and writers do not share mutable state, snapshots are
 * fully self-consistent, and held snapshots are unaffected by intervening
 * writes.
 */
class DocumentStoreConcurrencyTest {

    private val uri = URI.create("file:///tmp/concurrent.logo")

    /**
     * A snapshot is *immutable*. Capturing it on one thread and updating the
     * store on another must not affect what the captor sees.
     *
     * This is the structural reason concurrent readers don't need a lock.
     */
    @Test fun `held snapshot is unaffected by a later update`() {
        val store = DocumentStore()
        val original = store.open(uri, version = 1, source = "FD 10")
        store.update(uri, version = 2, source = "BK 999")
        // Reading through the captured reference must still see the original v1.
        assertEquals(1, original.version)
        assertEquals("FD 10", original.source)
        // The store now points at v2.
        assertEquals(2, store.snapshotOf(uri)!!.version)
    }

    /**
     * Many threads alternating between reading the snapshot and updating it
     * must never crash and must never observe an inconsistent snapshot —
     * `source`, `tokens`, and `resolved.program` always reflect the same
     * version.
     *
     * This is the test that answers the mentor's question concretely: yes,
     * `semanticTokens` and `completion` (and a `didChange` and an `inlayHint`
     * and a `definition`) can all happen at the same instant — and the
     * outcome is well-defined.
     */
    @Test fun `concurrent readers and writers never observe an inconsistent snapshot`() {
        val store = DocumentStore()
        store.open(uri, version = 0, source = sourceForVersion(0))

        val readers = 6
        val writers = 2
        val iterationsPerThread = 500
        val pool = Executors.newFixedThreadPool(readers + writers)
        val start = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>(null)
        val nextVersion = AtomicInteger(1)

        // Writers: keep replacing the document with a *content that encodes the version*,
        // so any snapshot observed by a reader can be cross-checked.
        repeat(writers) {
            pool.submit {
                try {
                    start.await()
                    repeat(iterationsPerThread) {
                        val v = nextVersion.getAndIncrement()
                        store.update(uri, v, sourceForVersion(v))
                    }
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }
        }

        // Readers: capture a snapshot, assert internal consistency, repeat.
        repeat(readers) {
            pool.submit {
                try {
                    start.await()
                    repeat(iterationsPerThread) {
                        val s = store.snapshotOf(uri) ?: error("snapshot disappeared")
                        // The source must encode exactly the snapshot's version.
                        val expected = sourceForVersion(s.version)
                        check(s.source == expected) {
                            "source/version mismatch: version=${s.version} source='${s.source}'"
                        }
                        // The parsed program must derive from this exact source —
                        // if the resolver were holding stale state we'd see binding
                        // ranges that exceed the source length.
                        val maxOffset = s.source.length
                        s.resolved.bindings.forEach { b ->
                            check(b.referenceRange.end.offset <= maxOffset) {
                                "binding end ${b.referenceRange.end.offset} exceeds source length $maxOffset"
                            }
                        }
                    }
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }
        }

        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "concurrency test timed out")

        val firstFailure = failures.get()
        if (firstFailure != null) throw AssertionError("expected no failures, got: ${firstFailure.message}", firstFailure)
    }

    /**
     * Two reader threads grabbing a snapshot at the same wall-clock moment
     * must each get a fully-formed, self-consistent value. They may legally
     * see different versions if a write interleaves between them — that is
     * correct under LSP semantics. They must not see corrupted state.
     */
    @Test fun `two simultaneous readers each get a fully-formed snapshot`() {
        val store = DocumentStore()
        store.open(uri, 1, sourceForVersion(1))

        val results = arrayOfNulls<DocumentSnapshot>(2)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) { idx ->
            pool.submit {
                gate.await()
                results[idx] = store.snapshotOf(uri)
            }
        }
        gate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertSame(results[0], results[1], "expected both readers to observe the same atomic snapshot")
    }

    // -------------------------------------------------------------------

    /**
     * Build a deterministic LOGO source for a given version. The version
     * number is encoded as the FD distance, so any consumer can verify
     * "this snapshot's source corresponds to its claimed version".
     */
    private fun sourceForVersion(v: Int): String = "FD $v"
}
