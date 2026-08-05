package org.owl4agents.reasoner.write;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.owl4agents.reasoner.TemporaryOntologyHandle;
import org.owl4agents.reasoner.TemporaryOntologyOptions;

import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * v0.9.1 mcp-write-tools-expansion D2: Transaction staging area lifecycle.
 *
 * <p>Holds the active {@link WriteTransaction} map keyed by transactionId.
 * Lazy creation: the first write tool call with an unknown
 * {@code transaction_id} creates a {@link WriteTransaction} seeded from
 * the current committed state via {@link OntologyCache#getOrCreate}.</p>
 *
 * <p>Commit swap (D2): {@link #commit} serializes the staging ontology to
 * the workspace canonical ontology file, invalidates the {@link OntologyCache}
 * entry, creates a {@link VersionSnapshot} via {@link VersionHistoryStore},
 * appends an audit entry, and removes the transaction. SHACL-on-commit
 * (D3) is performed by the caller (WriteToolsHandler) BEFORE invoking
 * {@link #commit}; this service does NOT call SHACL directly.</p>
 *
 * <p>Conflict detection (D2): each transaction records the
 * {@code committedVersion} counter at seed time; at commit, if the current
 * counter differs, {@link #commit} returns {@code TRANSACTION_CONFLICT}.</p>
 *
 * <p>TTL sweep (task 3.4): a daemon scheduler runs every 60s; transactions
 * whose {@code lastActivityAt} is older than
 * {@code owl4agents.write.transaction.ttl.seconds} (default 300) are
 * auto-rolled-back with an audit entry {@code transaction_expired}.</p>
 *
 * <p>JVM shutdown hook (task 3.9): appends {@code transaction_lost_on_shutdown}
 * for each open transaction and releases staging managers.</p>
 */
public final class WriteTransactionService {

    private static final Logger LOGGER =
        Logger.getLogger(WriteTransactionService.class.getName());

    private final OntologyCache ontologyCache;
    private final TemporaryOntologyFactory tempFactory;
    private final VersionHistoryStore versionHistoryStore;
    private final AuditLog auditLog;

    private final ConcurrentHashMap<String, WriteTransaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> committedVersionCounters = new ConcurrentHashMap<>();

    private final long ttlSeconds;
    private final ScheduledExecutorService sweepExecutor;
    private final Thread shutdownHook;

    /**
     * @param ontologyCache       shared OntologyCache (committed-state source of truth)
     * @param tempFactory         TemporaryOntologyFactory for isolated staging copies
     * @param versionHistoryStore VersionHistoryStore for snapshot creation on commit
     * @param auditLog            AuditLog for append-only operation logging
     */
    public WriteTransactionService(OntologyCache ontologyCache,
                                   TemporaryOntologyFactory tempFactory,
                                   VersionHistoryStore versionHistoryStore,
                                   AuditLog auditLog) {
        this.ontologyCache = ontologyCache;
        this.tempFactory = tempFactory;
        this.versionHistoryStore = versionHistoryStore;
        this.auditLog = auditLog;
        this.ttlSeconds = parseLongProperty("owl4agents.write.transaction.ttl.seconds", 300L);

        // TTL sweep every 60s
        this.sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "owl4agents-write-ttl-sweep");
            t.setDaemon(true);
            return t;
        });
        this.sweepExecutor.scheduleAtFixedRate(this::sweepExpiredTransactions,
            60, 60, TimeUnit.SECONDS);

        // JVM shutdown hook
        this.shutdownHook = new Thread(this::onShutdown, "owl4agents-write-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down; ignore
        }
    }

    /**
     * Lazy get-or-create: if {@code transactionId} is unknown, create a new
     * {@link WriteTransaction} seeded from the current committed state.
     *
     * @return success containing the transaction, or error if the ontology
     *         cannot be loaded (ONTOLOGY_NOT_FOUND wraps as INVALID_ARGUMENTS)
     */
    public synchronized ServiceResult<WriteTransaction> getOrCreate(
            String transactionId, OntologyId ontologyId) {
        if (transactionId == null || transactionId.isBlank()) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "transaction_id is required");
        }
        if (ontologyId == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "ontology_id is required");
        }

        WriteTransaction existing = transactions.get(transactionId);
        if (existing != null) {
            // Defensive: ensure the transaction targets the requested ontology
            if (!existing.ontologyId().id().equals(ontologyId.id())) {
                return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                    "transaction_id '" + transactionId + "' is bound to ontology '"
                        + existing.ontologyId().id() + "', not '" + ontologyId.id() + "'");
            }
            existing.touch();
            return ServiceResult.success(existing, org.owl4agents.core.ResultMetadata.empty());
        }

        // Lazy creation: seed from current committed state
        OWLOntology committed;
        try {
            committed = ontologyCache.getOrCreate(ontologyId);
        } catch (OWLOntologyCreationException e) {
            // v0.9.1 P1-1 secondary fix: ontology_id not in catalog or not
            // loadable from disk — return ONTOLOGY_NOT_FOUND (matches the
            // readonly tool behavior for the same condition) instead of
            // the misleading INVALID_ARGUMENTS.
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND,
                "Cannot seed transaction: ontology '" + ontologyId.id()
                    + "' not loadable: " + e.getMessage());
        }

        ServiceResult<TemporaryOntologyHandle> base = tempFactory.createBase(
            committed, TemporaryOntologyOptions.defaults());
        if (!base.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<TemporaryOntologyHandle>) base).error());
        }
        TemporaryOntologyHandle handle = ((ServiceResult.Success<TemporaryOntologyHandle>) base).data();

        long version = committedVersionCounters
            .computeIfAbsent(ontologyId.id(), k -> new AtomicLong(0))
            .get();

        WriteTransaction tx = new WriteTransaction(
            transactionId,
            ontologyId,
            handle.ontology(),
            handle.ontology().getOWLOntologyManager(),
            version
        );
        transactions.put(transactionId, tx);
        return ServiceResult.success(tx, org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Look up an existing transaction. Returns null if not found.
     */
    public WriteTransaction getTransaction(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            return null;
        }
        return transactions.get(transactionId);
    }

    /**
     * Check for commit conflict: returns Error(TRANSACTION_CONFLICT) if the
     * committed state has changed since the transaction was seeded.
     * Also returns Error(TRANSACTION_NOT_FOUND) for unknown transactionId.
     *
     * <p>Per task 3.7, the caller SHOULD invoke this BEFORE running SHACL
     * validation to avoid wasted SHACL work on a stale transaction.</p>
     */
    public synchronized ServiceResult<Void> checkConflict(String transactionId) {
        WriteTransaction tx = transactions.get(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }
        AtomicLong counter = committedVersionCounters.get(tx.ontologyId().id());
        long current = counter == null ? 0 : counter.get();
        if (current != tx.committedVersion()) {
            return ServiceResult.error(ErrorCode.TRANSACTION_CONFLICT,
                "Committed state changed (seededVersion=" + tx.committedVersion()
                    + ", currentVersion=" + current + "); rollback and re-apply edits.");
        }
        return ServiceResult.success(null, org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Commit swap (D2): serialize staging → workspace file → invalidate
     * OntologyCache → create VersionSnapshot → audit → remove transaction.
     *
     * <p>SHACL validation is NOT performed here; the caller MUST run SHACL
     * before invoking this method.</p>
     *
     * @return success containing the new VersionSnapshot, or error:
     *         TRANSACTION_NOT_FOUND, TRANSACTION_CONFLICT, or INVALID_ARGUMENTS
     */
    public synchronized ServiceResult<VersionSnapshot> commit(
            String transactionId, String message, String author) {
        WriteTransaction tx = transactions.get(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }

        // Atomic conflict re-check (defensive: caller may have pre-checked)
        AtomicLong counter = committedVersionCounters.get(tx.ontologyId().id());
        long currentVersion = counter == null ? 0 : counter.get();
        if (currentVersion != tx.committedVersion()) {
            auditLog.append(tx.ontologyId(), new AuditEntry(
                null, Instant.now(), tx.ontologyId().id(), transactionId,
                "commit", author, null, null, null, "conflict",
                "seededVersion=" + tx.committedVersion()
                    + " currentVersion=" + currentVersion));
            return ServiceResult.error(ErrorCode.TRANSACTION_CONFLICT,
                "Committed state changed; rollback and re-apply edits.");
        }

        try {
            // 1. Serialize staging to workspace canonical ontology file.
            // v0.9.1 P1-3 fix: write to a temp file in the same directory
            // then atomically move (REPLACE_EXISTING + ATOMIC_MOVE if
            // supported). Direct CREATE+TRUNCATE_EXISTING on the canonical
            // file was failing on Windows with AccessDeniedException whose
            // getMessage() returned only the file path — leaving clients
            // with the uninformative "Commit failed: <path>" message.
            // Atomic temp+move also defends against partial writes if the
            // JVM is killed mid-commit, and works around Windows file
            // locking when a concurrent reader has the canonical file open.
            Path canonicalPath = versionHistoryStore.resolveCanonicalOntologyPath(tx.ontologyId());
            Files.createDirectories(canonicalPath.getParent());
            OWLXMLDocumentFormat format = new OWLXMLDocumentFormat();
            Path tempFile = Files.createTempFile(canonicalPath.getParent(), ".commit-", ".owx.tmp");
            try {
                try (var out = Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                    tx.stagingOntology().getOWLOntologyManager()
                        .saveOntology(tx.stagingOntology(), format, out);
                }
                // Atomic move with REPLACE_EXISTING. On Windows, ATOMIC_MOVE
                // may fall back to non-atomic rename, but REPLACE_EXISTING
                // still avoids the "open file" truncation problem.
                try {
                    Files.move(tempFile, canonicalPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                    Files.move(tempFile, canonicalPath,
                        StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // Best-effort cleanup if move threw before consuming tempFile
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }

            // 2. Invalidate OntologyCache entry so subsequent reads pick up
            //    the new committed state
            ontologyCache.invalidate(tx.ontologyId());

            // 3. Determine parent version (current head before this commit)
            ServiceResult<List<VersionSnapshot>> historyResult =
                versionHistoryStore.listHistory(tx.ontologyId(), 1);
            String parentVersionId = null;
            if (historyResult.isSuccess()) {
                List<VersionSnapshot> history = ((ServiceResult.Success<List<VersionSnapshot>>) historyResult).data();
                if (!history.isEmpty()) {
                    parentVersionId = history.get(0).versionId();
                }
            }

            // 4. Create VersionSnapshot
            ServiceResult<VersionSnapshot> snapResult = versionHistoryStore.createSnapshot(
                tx.ontologyId(), tx.stagingOntology(), author, parentVersionId, message);
            if (!snapResult.isSuccess()) {
                return snapResult;
            }
            VersionSnapshot snapshot = ((ServiceResult.Success<VersionSnapshot>) snapResult).data();

            // 5. Increment committedVersion counter
            counter.incrementAndGet();

            // 6. Audit success
            auditLog.append(tx.ontologyId(), new AuditEntry(
                null, Instant.now(), tx.ontologyId().id(), transactionId,
                "commit", author, null, null, snapshot.versionId(), "ok",
                message == null ? "" : message));

            // 7. Release staging manager and remove transaction
            tx.releaseStagingManager();
            transactions.remove(transactionId);

            return ServiceResult.success(snapshot, org.owl4agents.core.ResultMetadata.empty());
        } catch (IOException | OWLOntologyStorageException e) {
            // v0.9.1 P1-3 fix: log the full stack trace to stderr and
            // surface the exception class name + cause so the next failure
            // is diagnosable. Use COMMIT_PERSIST_FAILED (new ErrorCode)
            // instead of INVALID_ARGUMENTS so clients can distinguish a
            // persist failure from genuinely bad arguments.
            LOGGER.log(Level.SEVERE, "commit failed for ontology '" + tx.ontologyId().id()
                + "' transaction '" + transactionId + "'", e);
            String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            if (cause != null && cause != e) {
                errMsg += " (cause: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage() + ")";
            }
            return ServiceResult.error(ErrorCode.COMMIT_PERSIST_FAILED,
                "Commit failed: " + errMsg);
        }
    }

    /**
     * Discard the transaction's staging ontology, release the isolated
     * manager, and remove the transaction. Audit entry appended.
     */
    public synchronized ServiceResult<Void> rollback(String transactionId, String author) {
        WriteTransaction tx = transactions.remove(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }
        tx.releaseStagingManager();
        auditLog.append(tx.ontologyId(), new AuditEntry(
            null, Instant.now(), tx.ontologyId().id(), transactionId,
            "rollback", author, null, null, null, "ok",
            "staged operations discarded: " + tx.stagedOperationCount()));
        return ServiceResult.success(null, org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Rollback to a specific version. Restores the snapshot content to the
     * workspace canonical file, creates a NEW version (history is append-only),
     * invalidates OntologyCache, and audits.
     */
    public synchronized ServiceResult<VersionSnapshot> rollbackToVersion(
            OntologyId ontologyId, String versionId, String author) {
        ServiceResult<VersionSnapshot> rbResult =
            versionHistoryStore.rollbackToVersion(ontologyId, versionId, author);
        if (!rbResult.isSuccess()) {
            return rbResult;
        }
        VersionSnapshot newSnapshot = ((ServiceResult.Success<VersionSnapshot>) rbResult).data();

        // Write restored content to canonical file
        ServiceResult<Void> writeResult = versionHistoryStore.writeRestoredContentToCanonical(
            ontologyId, newSnapshot.snapshotPath());
        if (!writeResult.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<Void>) writeResult).error());
        }

        // Invalidate cache + increment version counter
        ontologyCache.invalidate(ontologyId);
        committedVersionCounters
            .computeIfAbsent(ontologyId.id(), k -> new AtomicLong(0))
            .incrementAndGet();

        // Audit
        auditLog.append(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), null,
            "rollback_to_version", author, null, null,
            newSnapshot.versionId(), "ok",
            "restored to " + versionId));

        return ServiceResult.success(newSnapshot, org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * Record a non-commit audit entry from the OntologyEditService
     * (add_axiom, remove_axiom, edit_entity, create_class, merge).
     */
    public void recordEditAudit(OntologyId ontologyId, AuditEntry entry) {
        auditLog.append(ontologyId, entry);
    }

    /**
     * TTL sweep: auto-rollback transactions older than ttlSeconds.
     */
    private void sweepExpiredTransactions() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        for (Map.Entry<String, WriteTransaction> entry : transactions.entrySet()) {
            WriteTransaction tx = entry.getValue();
            if (tx.lastActivityAt().isBefore(cutoff)) {
                transactions.remove(entry.getKey(), tx);
                tx.releaseStagingManager();
                auditLog.append(tx.ontologyId(), new AuditEntry(
                    null, Instant.now(), tx.ontologyId().id(), tx.transactionId(),
                    "transaction_expired", "system", null, null, null, "expired",
                    "TTL=" + ttlSeconds + "s exceeded; auto-rollback"));
            }
        }
    }

    /**
     * JVM shutdown hook: release all staging managers and audit
     * transaction_lost_on_shutdown for each open transaction.
     */
    private void onShutdown() {
        try {
            sweepExecutor.shutdownNow();
        } catch (Exception ignored) {
            // best-effort
        }
        for (Map.Entry<String, WriteTransaction> entry : transactions.entrySet()) {
            WriteTransaction tx = entry.getValue();
            try {
                tx.releaseStagingManager();
                auditLog.append(tx.ontologyId(), new AuditEntry(
                    null, Instant.now(), tx.ontologyId().id(), tx.transactionId(),
                    "transaction_lost_on_shutdown", "system", null, null, null, "expired",
                    "JVM shutdown; " + tx.stagedOperationCount() + " staged operations lost"));
            } catch (Exception ignored) {
                // best-effort
            }
        }
        transactions.clear();
    }

    private static long parseLongProperty(String key, long def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
