package io.github.lucasvallejoo.lsp4logo.server

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe registry of currently-open documents.
 *
 * **The architectural promise** (the mentor's contract):
 *
 *  - Reads are **lock-free**. Calling [snapshotOf] on any thread is a single
 *    `ConcurrentHashMap.get` followed by an `AtomicReference.get`. No mutex,
 *    no contention with concurrent reads or writes.
 *  - Writes are **lock-free**. Calling [open], [update], or [close] computes a
 *    new immutable [DocumentSnapshot] outside any lock and then publishes it
 *    with an atomic swap.
 *  - Readers and writers cannot interfere. A request handler that captures the
 *    snapshot at version *N* will see a perfectly consistent program for that
 *    version even if a `didChange` is racing in to publish version *N+1*.
 *  - Two request handlers reading at the same wall-clock moment may legally see
 *    two different versions if a write interleaves between their reads. That
 *    is *correct* under LSP semantics — each result is tagged with the version
 *    it was computed against, and the client reconciles.
 *
 * **What this store does NOT do (yet)**:
 *  - Asynchronous re-analysis (the snapshot is computed inline in [update]).
 *    For LOGO files at this scale (sub-millisecond pipeline), that is a
 *    feature, not a bug — predictability beats throughput. Future work could
 *    add a worker pool + version-discarding policy without changing this API.
 *  - Document content sync deltas (we accept full content per LSP's
 *    [org.eclipse.lsp4j.TextDocumentSyncKind.Full] mode).
 */
class DocumentStore {

    private val docs: ConcurrentHashMap<URI, AtomicReference<DocumentSnapshot>> = ConcurrentHashMap()

    /** Register a freshly-opened document and analyse it. */
    fun open(uri: URI, version: Int, source: String): DocumentSnapshot {
        val snapshot = DocumentSnapshot.analyse(uri, version, source)
        docs[uri] = AtomicReference(snapshot)
        return snapshot
    }

    /**
     * Replace the document content. The new snapshot is published atomically.
     *
     * If the document is not open we treat this as a tolerant no-op rather
     * than throwing — LSP clients occasionally send didChange before didOpen
     * during edge-case sessions, and surfacing that as an exception would
     * break editor support.
     */
    fun update(uri: URI, version: Int, source: String): DocumentSnapshot? {
        val ref = docs[uri] ?: return null
        val snapshot = DocumentSnapshot.analyse(uri, version, source)
        ref.set(snapshot)
        return snapshot
    }

    /** Forget about a document. No-op if it was not open. */
    fun close(uri: URI) {
        docs.remove(uri)
    }

    /**
     * Read the current snapshot for [uri], or `null` if the document is not
     * open. The returned value is fully immutable — the caller can hold onto
     * it across thread boundaries with no further synchronisation.
     */
    fun snapshotOf(uri: URI): DocumentSnapshot? = docs[uri]?.get()

    /** Number of currently-open documents. */
    val openCount: Int get() = docs.size
}
