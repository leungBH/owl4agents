package org.owl4agents.reasoner;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ConsistencyResult;
import org.semanticweb.owlapi.model.OWLOntology;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Disposable reasoner session for exact consistency checks (D4).
 *
 * <p>Each {@code TransientReasonerSession} wraps a fresh reasoner adapter
 * instance that is NOT registered in {@link ReasonerLifecycleManager}. The
 * session is created via {@link #create(OWLOntology, String, Duration)},
 * used for a single consistency check, and then disposed via {@link #close()}
 * (typically in a try-with-resources block).
 *
 * <p>Disposal contract:
 * <ul>
 *   <li>{@link #close()} SHALL be called on all paths: normal completion,
 *       exception, and timeout.</li>
 *   <li>{@link #close()} is thread-safe: even if the reasoning thread is
 *       still running (due to uninterruptible {@code synchronized} blocks
 *       in HermiT/Openllet tableau operations), {@code close()} SHALL NOT
 *       crash the JVM. A {@link ReentrantLock} with {@code tryLock(timeout)}
 *       mutually excludes reasoning and disposal; if the lock cannot be
 *       acquired within the timeout, the implementation logs a warning and
 *       proceeds with {@code dispose()}, accepting possible reasoner
 *       internal state corruption (the transient reasoner is discarded
 *       anyway — this is acceptable).</li>
 *   <li>The session is NOT entered into the main lifecycle cache.</li>
 *   <li>The session does NOT write persisted reasoning reports.</li>
 *   <li>The session does NOT write inferred hierarchy.</li>
 * </ul>
 *
 * <p>This class does NOT depend on the ReentrantLock serialization planned
 * for v0.9.0 (S0a in v1-0-0-major-release) — it uses an independent reasoner
 * instance with its own lock, completely isolated from the main
 * {@code ReasonerLifecycleManager}.
 */
public final class TransientReasonerSession implements AutoCloseable {

    private final OWLReasonerAdapter adapter;
    private final String reasonerName;
    private final ReentrantLock reasoningLock;
    private volatile boolean disposed = false;

    private TransientReasonerSession(OWLReasonerAdapter adapter, String reasonerName) {
        this.adapter = adapter;
        this.reasonerName = reasonerName;
        this.reasoningLock = new ReentrantLock();
    }

    /**
     * Create a transient reasoner session for the given ontology.
     *
     * @param ontology     the (temporary) ontology to reason over
     * @param reasonerName canonical reasoner name ({@code "HermiT"},
     *                     {@code "ELK"}, or {@code "Openllet"}); if null or
     *                     blank, {@code "HermiT"} is used as the default
     *                     OWL 2 DL reasoner
     * @param timeout      the timeout for consistency checks (advisory; the
     *                     underlying reasoner may not honor Java interrupts —
     *                     see D7 in the design doc); may be null for no
     *                     timeout (not recommended for batch processing)
     * @return a {@link ServiceResult} containing the session on success, or
     *         an error with code {@link ErrorCode#TRANSIENT_REASONER_INIT_FAILED}
     *         on failure
     */
    public static ServiceResult<TransientReasonerSession> create(
            OWLOntology ontology, String reasonerName, Duration timeout) {
        if (ontology == null) {
            return ServiceResult.error(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
                "Ontology must not be null.");
        }
        String canonical = canonicalizeReasonerName(reasonerName);
        OWLReasonerAdapter adapter;
        try {
            adapter = createAdapter(canonical);
        } catch (IllegalArgumentException e) {
            return ServiceResult.error(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
                "Unknown reasoner: " + reasonerName);
        }
        try {
            adapter.initialize(ontology);
        } catch (Exception e) {
            try {
                adapter.shutdown();
            } catch (Exception ignored) {
                // best-effort
            }
            return ServiceResult.error(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
                "Failed to initialize reasoner '" + canonical + "': " + e.getMessage());
        }
        return ServiceResult.success(
            new TransientReasonerSession(adapter, canonical),
            org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Check consistency of the underlying ontology. Thread-safe with respect
     * to {@link #close()}: acquires the reasoning lock so that disposal
     * cannot run concurrently with reasoning.
     *
     * <p>Note: this method does NOT enforce the timeout itself — the caller
     * ({@code ReasonerService.checkConsistencyAfterAdding}) is responsible
     * for wrapping the call in {@code Future.get(timeout)}. The lock here
     * only prevents concurrent disposal during reasoning.
     *
     * @return the {@link ConsistencyResult} from the underlying reasoner
     * @throws IllegalStateException if the session has been disposed
     * @throws RuntimeException      if the reasoner throws during consistency
     *                              check (caller wraps in error result)
     */
    public ConsistencyResult checkConsistency() {
        if (disposed) {
            throw new IllegalStateException(
                "TransientReasonerSession has been disposed; cannot checkConsistency.");
        }
        reasoningLock.lock();
        try {
            return adapter.checkConsistency("transient");
        } finally {
            reasoningLock.unlock();
        }
    }

    /**
     * Get the reasoner name (e.g. {@code "HermiT"}).
     */
    public String reasonerName() {
        return reasonerName;
    }

    /**
     * Whether this adapter supports inconsistency explanation.
     */
    public boolean supportsExplanation() {
        return adapter.supportsExplanation();
    }

    /**
     * Explain the inconsistency of the underlying ontology. Only supported
     * when {@link #supportsExplanation()} returns {@code true} (typically
     * Openllet). Acquires the reasoning lock for thread-safety with
     * {@link #close()}.
     *
     * @throws IllegalStateException if the session has been disposed
     * @throws UnsupportedOperationException if the reasoner does not support explanation
     */
    public org.owl4agents.core.model.InconsistencyExplanation explainInconsistency() {
        if (disposed) {
            throw new IllegalStateException(
                "TransientReasonerSession has been disposed; cannot explainInconsistency.");
        }
        reasoningLock.lock();
        try {
            return adapter.explainInconsistency("transient");
        } finally {
            reasoningLock.unlock();
        }
    }

    /**
     * Dispose the reasoner. Safe to call multiple times. Thread-safe with
     * respect to {@link #checkConsistency()}: uses {@code tryLock} to avoid
     * blocking indefinitely on a reasoning thread stuck in an uninterruptible
     * {@code synchronized} block. If the lock cannot be acquired within 5
     * seconds, the method logs a warning and proceeds with shutdown anyway,
     * accepting possible reasoner state corruption (the transient reasoner
     * is discarded regardless).
     */
    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        boolean acquired = false;
        try {
            acquired = reasoningLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // proceed with shutdown anyway — the reasoning thread will be
            // left in a corrupted state, but the session is being discarded
        }
        if (!acquired) {
            // Reasoning thread is stuck in an uninterruptible block; proceed
            // with shutdown anyway. The reasoner's internal state may be
            // corrupted, but since the session is disposable this is
            // acceptable per D4.
            System.err.println("[TransientReasonerSession] WARNING: could not acquire " +
                "reasoning lock within 5s during close(); proceeding with shutdown " +
                "anyway (reasoner state may be corrupted — acceptable for disposable session).");
        }
        try {
            adapter.shutdown();
        } catch (Throwable t) {
            // best-effort — never let disposal crash the JVM
            System.err.println("[TransientReasonerSession] WARNING: adapter.shutdown() " +
                "threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (acquired) {
                reasoningLock.unlock();
            }
        }
    }

    private static String canonicalizeReasonerName(String name) {
        if (name == null || name.isBlank()) {
            return "HermiT";
        }
        if (name.equalsIgnoreCase("HermiT")) return "HermiT";
        if (name.equalsIgnoreCase("ELK"))    return "ELK";
        if (name.equalsIgnoreCase("Openllet")) return "Openllet";
        return name;
    }

    private static OWLReasonerAdapter createAdapter(String canonicalName) {
        switch (canonicalName) {
            case "HermiT":    return new HermiTAdapter();
            case "ELK":       return new ELKAdapter();
            case "Openllet":  return new OpenlletAdapter();
            default: throw new IllegalArgumentException("Unknown reasoner: " + canonicalName);
        }
    }
}
