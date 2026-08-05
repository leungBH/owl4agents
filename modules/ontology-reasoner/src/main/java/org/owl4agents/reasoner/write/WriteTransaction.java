package org.owl4agents.reasoner.write;

import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.9.1 mcp-write-tools-expansion D2: Mutable transaction state.
 *
 * <p>Each {@code WriteTransaction} holds an isolated staging ontology (built
 * by {@code TemporaryOntologyFactory.createBase} from the committed state)
 * plus a {@code committedVersion} counter used for conflict detection at
 * commit time. Staged operations are recorded in {@code stagedOperations}
 * for audit purposes ({@code before}/{@code after} snapshots).</p>
 *
 * <p>This class is NOT thread-safe; callers MUST synchronize on the
 * enclosing {@code WriteTransactionService} when accessing a transaction.
 * The {@code lastActivityAt} field is updated on every write tool call
 * (add/remove/edit/create/merge/commit) for TTL sweep.</p>
 */
public final class WriteTransaction {

    private final String transactionId;
    private final OntologyId ontologyId;
    private final OWLOntology stagingOntology;
    private final OWLOntologyManager stagingManager;
    private final long committedVersion;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;
    private final List<Map<String, Object>> stagedOperations;

    /**
     * @param transactionId    caller-supplied UUID identifying this transaction
     * @param ontologyId       target ontology
     * @param stagingOntology  isolated staging ontology copy
     * @param stagingManager   isolated OWLOntologyManager owning stagingOntology
     * @param committedVersion committed-state version counter at lazy creation
     */
    public WriteTransaction(String transactionId,
                            OntologyId ontologyId,
                            OWLOntology stagingOntology,
                            OWLOntologyManager stagingManager,
                            long committedVersion) {
        this.transactionId = transactionId;
        this.ontologyId = ontologyId;
        this.stagingOntology = stagingOntology;
        this.stagingManager = stagingManager;
        this.committedVersion = committedVersion;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.stagedOperations = new ArrayList<>();
    }

    public String transactionId() {
        return transactionId;
    }

    public OntologyId ontologyId() {
        return ontologyId;
    }

    public OWLOntology stagingOntology() {
        return stagingOntology;
    }

    public OWLOntologyManager stagingManager() {
        return stagingManager;
    }

    public long committedVersion() {
        return committedVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    /**
     * Reset the activity timestamp to "now". Called on every write tool call.
     */
    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    /**
     * Append a staged operation record to the in-memory audit trail.
     * Each entry shape: {@code {op, axiom, before, after}} (any field may be null).
     */
    public void recordStagedOperation(String op, Object axiom, Object before, Object after) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", op == null ? "unknown" : op);
        entry.put("axiom", axiom);
        entry.put("before", before);
        entry.put("after", after);
        stagedOperations.add(entry);
    }

    /**
     * Return an unmodifiable view of the staged operations list.
     */
    public List<Map<String, Object>> stagedOperations() {
        return List.copyOf(stagedOperations);
    }

    /**
     * Number of staged operations recorded so far.
     */
    public int stagedOperationCount() {
        return stagedOperations.size();
    }

    /**
     * Release the isolated staging manager. Best-effort: never throws.
     */
    public void releaseStagingManager() {
        if (stagingManager == null) {
            return;
        }
        try {
            for (OWLOntology ont : java.util.Set.copyOf(stagingManager.getOntologies())) {
                try {
                    stagingManager.removeOntology(ont);
                } catch (Exception ignored) {
                    // best-effort per-ontology removal
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
