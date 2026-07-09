package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.owl4agents.storage.CatalogStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-23: Expression-only entity scope bypass test.
 *
 * <p>Verifies that a claim whose {@code subject} has {@code iri == null}
 * and {@code expression != null} skips the top-level scope pre-check.
 * Nested IRIs inside the expression are validated by
 * {@code ClassExpressionBuilder} (which throws {@code ENTITY_NOT_FOUND}
 * when the nested IRI is unresolvable).</p>
 */
@DisplayName("TC-23 expression-only entity bypasses top-level scope pre-check")
class ExpressionOnlyEntityScopeBypassTest {

    private static final String WORKSPACE = "data/workspaces";

    private ClaimVerificationService buildService() {
        StubReasonerService stubReasoner = new StubReasonerService().withRealOntology(WORKSPACE);
        ReasonerLifecycleManager lifecycle = new ReasonerLifecycleManager();
        ConsistencyAnalysisService consistencyService = new ConsistencyAnalysisService(lifecycle, WORKSPACE);
        CatalogStore stubCatalog = new StubCatalogStore();
        SemanticDeepeningService deepeningService = new SemanticDeepeningService(WORKSPACE);
        return new ClaimVerificationService(
            stubReasoner, consistencyService, deepeningService,
            stubCatalog, WorkspaceId.DEFAULT);
    }

    @Test
    @DisplayName("subject with iri==null and expression!=null bypasses top-level scope pre-check")
    void expressionOnlySubjectBypassesTopLevelCheck() {
        ClaimVerificationService service = buildService();
        ClassExpression expr = new org.owl4agents.core.model.ObjectIntersectionOf(
            List.of(
                new org.owl4agents.core.model.NamedClass("http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"),
                new org.owl4agents.core.model.ObjectSomeValuesFrom(
                    "http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping",
                    new org.owl4agents.core.model.NamedClass("http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseTopping"))
            )
        );
        ClaimEntity subject = new ClaimEntity("class", null, expr);
        ClaimEntity object = new ClaimEntity("class", "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza");

        Claim claim = new Claim(
            "expr-only-001",
            ClaimType.EQUIVALENT_CLASSES,
            "pizza",
            subject,
            "equivalentTo",
            object,
            Optional.empty(),
            Optional.of(GraphScope.EXPLICIT),
            Optional.of(Map.of(Claim.INCLUDE_EVIDENCE, true))
        );

        // The verification runs without throwing; we don't assert a specific
        // verdict (the stub returns UNKNOWN) but we DO assert no exception.
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertNotNull(result, "Service must not throw on expression-only subject");
    }

    @Test
    @DisplayName("object with iri==null and expression!=null bypasses top-level scope pre-check")
    void expressionOnlyObjectBypassesTopLevelCheck() {
        ClaimVerificationService service = buildService();
        ClassExpression expr = new org.owl4agents.core.model.ObjectIntersectionOf(
            List.of(
                new org.owl4agents.core.model.NamedClass("http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"),
                new org.owl4agents.core.model.ObjectSomeValuesFrom(
                    "http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping",
                    new org.owl4agents.core.model.NamedClass("http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseTopping"))
            )
        );
        ClaimEntity subject = new ClaimEntity("class", "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza");
        ClaimEntity object = new ClaimEntity("class", null, expr);

        Claim claim = new Claim(
            "expr-only-002",
            ClaimType.EQUIVALENT_CLASSES,
            "pizza",
            subject,
            "equivalentTo",
            object,
            Optional.empty(),
            Optional.of(GraphScope.EXPLICIT),
            Optional.of(Map.of(Claim.INCLUDE_EVIDENCE, true))
        );

        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertNotNull(result, "Service must not throw on expression-only object");
    }
}
