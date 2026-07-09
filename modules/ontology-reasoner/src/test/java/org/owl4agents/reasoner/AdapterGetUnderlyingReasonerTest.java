package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-27: {@link OWLReasonerAdapter#getUnderlyingReasoner()} contract test.
 * Verifies all three adapter implementations expose a non-null
 * {@code getUnderlyingReasoner()} accessor (after the adapter is
 * activated by classifying an ontology).
 *
 * <p>The {@code getUnderlyingReasoner()} method was added in v0.8.1 to
 * expose the raw reasoner instance to service-layer code; previously
 * the private {@code getOWLReasonerFromAdapter(adapter)} bridge in
 * {@code ReasonerServiceImpl} returned {@code null}.</p>
 */
@DisplayName("TC-27 OWLReasonerAdapter.getUnderlyingReasoner()")
class AdapterGetUnderlyingReasonerTest {

    @Test
    @DisplayName("HermiTAdapter declares getUnderlyingReasoner()")
    void hermitDeclaresGetUnderlyingReasoner() throws Exception {
        HermiTAdapter adapter = new HermiTAdapter();
        var method = OWLReasonerAdapter.class.getMethod("getUnderlyingReasoner");
        assertNotNull(method, "OWLReasonerAdapter must declare getUnderlyingReasoner()");
        assertEquals(org.semanticweb.owlapi.reasoner.OWLReasoner.class,
            method.getReturnType(),
            "getUnderlyingReasoner() must return an OWLReasoner");
    }

    @Test
    @DisplayName("ELKAdapter declares getUnderlyingReasoner()")
    void elkDeclaresGetUnderlyingReasoner() throws Exception {
        ELKAdapter adapter = new ELKAdapter();
        var method = OWLReasonerAdapter.class.getMethod("getUnderlyingReasoner");
        assertNotNull(method);
        assertEquals(org.semanticweb.owlapi.reasoner.OWLReasoner.class,
            method.getReturnType());
    }

    @Test
    @DisplayName("OpenlletAdapter declares getUnderlyingReasoner()")
    void openlletDeclaresGetUnderlyingReasoner() throws Exception {
        OpenlletAdapter adapter = new OpenlletAdapter();
        var method = OWLReasonerAdapter.class.getMethod("getUnderlyingReasoner");
        assertNotNull(method);
        assertEquals(org.semanticweb.owlapi.reasoner.OWLReasoner.class,
            method.getReturnType());
    }
}
