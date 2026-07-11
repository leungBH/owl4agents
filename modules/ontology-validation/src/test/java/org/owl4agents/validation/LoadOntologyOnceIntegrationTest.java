package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INTEG-1: verifies that {@code ClaimVerificationService.verify()} loads the
 * ontology exactly once per claim verification (v0.8.4 Decision 3).
 */
@DisplayName("INTEG-1: Per-request ontology single loading")
class LoadOntologyOnceIntegrationTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";
    private static final String MARGHERITA = PIZZA_NS + "Margherita";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private static class CountingReasonerService extends ReasonerServiceImpl {
        final AtomicInteger loadCount = new AtomicInteger(0);

        CountingReasonerService(StubCatalogStore catalog, String workspace, String wsName,
                                OntologyCache cache, EntitySignatureCacheManager escManager) {
            super(catalog, workspace, wsName, cache, escManager);
        }

        @Override
        public OWLOntology loadOntologyForClaim(OntologyId ontologyId) throws OWLOntologyCreationException {
            loadCount.incrementAndGet();
            return super.loadOntologyForClaim(ontologyId);
        }
    }

    @Test
    @DisplayName("INTEG-1: loadOntologyForClaim() called exactly once for a SUBCLASS claim")
    void loadOntologyForClaimCalledOnceForSubclassClaim() {
        OntologyCache cache = new OntologyCache(WORKSPACE, "default");
        StubCatalogStore catalog = new StubCatalogStore();
        EntitySignatureCacheManager escManager = new EntitySignatureCacheManager();
        cache.addReloadListener(escManager);

        CountingReasonerService reasonerService = new CountingReasonerService(
            catalog, WORKSPACE, "default", cache, escManager);
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(
            reasonerService.getLifecycleManager(), WORKSPACE, cache, escManager);
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(WORKSPACE, cache);
        ClaimVerificationService svc = new ClaimVerificationService(
            reasonerService, consistencyService, deepeningService, catalog, new WorkspaceId("default"));

        Claim claim = new Claim("integ-1", ClaimType.SUBCLASS, "pizza",
            new ClaimEntity("class", MARGHERITA),
            "http://www.w3.org/2000/01/rdf-schema#subClassOf",
            new ClaimEntity("class", PIZZA),
            Optional.empty(), Optional.empty(), Optional.empty());

        ServiceResult<ClaimVerificationResult> result = svc.verify(claim);
        assertTrue(result.isSuccess(), "verify() should succeed");
        assertEquals(1, reasonerService.loadCount.get(),
            "loadOntologyForClaim() must be called exactly once per verify() call");
    }
}
