package org.owl4agents.reasoner;

import org.owl4agents.core.model.*;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v0.8.6 D3 / task 5.13-5.16: Minimal test-only {@link OWLReasonerAdapter}
 * implementation. Tracks {@code shutdown()} invocations and optionally
 * throws on {@code shutdown()} to test the dispose-exception path in
 * {@link ReasonerLifecycleManager#evictIfFull}.
 *
 * <p>No Mockito is used in this project — this hand-written mock is the
 * equivalent of {@code mock(OWLReasonerAdapter.class)} with stubbed
 * {@code shutdown()} and {@code isActive()}.</p>
 *
 * <p>Package-private so it is only visible to tests in
 * {@code org.owl4agents.reasoner}.</p>
 */
class MockReasonerAdapter implements OWLReasonerAdapter {

    private final String name;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger shutdownCount = new AtomicInteger(0);
    private final RuntimeException shutdownException;

    /**
     * Create a mock that reports the given name and successfully shuts down.
     */
    MockReasonerAdapter(String name) {
        this(name, null);
    }

    /**
     * Create a mock that throws {@code shutdownException} from
     * {@link #shutdown()} (used by task 5.16 dispose-exception test).
     */
    MockReasonerAdapter(String name, RuntimeException shutdownException) {
        this.name = name;
        this.shutdownException = shutdownException;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void initialize(OWLOntology ontology) {
        // No-op — mock does not actually wrap a real reasoner.
    }

    @Override
    public ClassificationResult classify(String ontologyId) {
        throw new UnsupportedOperationException("Mock does not implement classify");
    }

    @Override
    public RealizationResult realize(String ontologyId) {
        throw new UnsupportedOperationException("Mock does not implement realize");
    }

    @Override
    public ConsistencyResult checkConsistency(String ontologyId) {
        throw new UnsupportedOperationException("Mock does not implement checkConsistency");
    }

    @Override
    public Set<String> getUnsatClasses() {
        return Collections.emptySet();
    }

    @Override
    public InconsistencyExplanation explainInconsistency(String ontologyId) {
        throw new UnsupportedOperationException("Mock does not implement explainInconsistency");
    }

    @Override
    public UnsatClassExplanation explainUnsatClass(String ontologyId, String classIRI) {
        throw new UnsupportedOperationException("Mock does not implement explainUnsatClass");
    }

    @Override
    public boolean supportsExplanation() {
        return false;
    }

    @Override
    public List<String> getSupportedProfiles() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getSupportedOperations() {
        return Collections.emptyList();
    }

    @Override
    public void shutdown() {
        shutdownCount.incrementAndGet();
        active.set(false);
        if (shutdownException != null) {
            throw shutdownException;
        }
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public OWLReasoner getUnderlyingReasoner() {
        return null;
    }

    @Override
    public boolean isSatisfiable(OWLClassExpression expr) {
        throw new UnsupportedOperationException("Mock does not implement isSatisfiable");
    }

    /** Number of times {@link #shutdown()} was invoked. */
    int shutdownCount() {
        return shutdownCount.get();
    }
}
