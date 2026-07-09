package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-22: scope pre-check exemption list.
 *
 * <p>Verifies the three claim types that exempt from the global scope
 * pre-check: {@code ontology_scope}, {@code ontology_consistency},
 * {@code literal_validity}. These are the "meta" claim types whose
 * semantics inherently require accepting the caller's IRIs even when
 * they are not in the ontology signature.</p>
 */
@DisplayName("TC-22 Scope pre-check exemption list")
class ScopePrecheckExemptionListTest {

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private ClaimVerificationService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
        service = new ClaimVerificationService(
            stub,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    @Test
    @DisplayName("TC-22a: ontology_scope is exempt from pre-check, but method still reports OUT_OF_SCOPE for undeclared entities")
    void ontologyScopeExempt() {
        // v0.8.1 DEFECT-2 fix: ONTOLOGY_SCOPE is exempt from the *global* pre-check
        // (which would short-circuit before reaching the dispatcher), but the
        // method itself must still report OUT_OF_SCOPE for undeclared entities
        // per spec `claim-verification/spec.md` line 100-101, 236. The previous
        // interpretation ("ONTOLOGY_SCOPE is always SUPPORTED") was a regression.
        Claim claim = new Claim("c1", ClaimType.ONTOLOGY_SCOPE, "pizza",
            new ClaimEntity("class", "http://example.org/external#Anything"), "inScopeOf",
            null,
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        // Per spec: external IRI → OUT_OF_SCOPE (via the scope-check path, not
        // the verify-entry pre-check). The exemption is on the *pre-check*
        // (which would have rejected the claim before the dispatcher); the
        // method itself still applies the spec-mandated out-of-scope logic.
        assertEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
            "ONTOLOGY_SCOPE with undeclared subject must report OUT_OF_SCOPE per spec");
        assertTrue(data.unknownReason().isPresent());
        assertEquals(org.owl4agents.core.model.UnknownReason.MISSING_ENTITY,
            data.unknownReason().get(),
            "OUT_OF_SCOPE must include MISSING_ENTITY unknown reason");
    }

    @Test
    @DisplayName("TC-22b: ontology_consistency is exempt from pre-check")
    void ontologyConsistencyExempt() {
        Claim claim = new Claim("c2", ClaimType.ONTOLOGY_CONSISTENCY, "pizza",
            new ClaimEntity("class", "http://example.org/external#Class"), "consistent",
            null,
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
            "ONTOLOGY_CONSISTENCY must be exempt from pre-check");
    }

    @Test
    @DisplayName("TC-22c: literal_validity is exempt from pre-check")
    void literalValidityExempt() {
        Claim claim = new Claim("c3", ClaimType.LITERAL_VALIDITY, "pizza",
            new ClaimEntity("literal", "\"42\"^^xsd:integer"), "valid",
            null,
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
        assertNotEquals(Verdict.OUT_OF_SCOPE, data.verdict(),
            "LITERAL_VALIDITY must be exempt from pre-check");
    }

    @Test
    @DisplayName("TC-22d: subclass is NOT exempt and external IRI is out-of-scope")
    void subclassNotExempt() {
        Claim claim = new Claim("c4", ClaimType.SUBCLASS, "pizza",
            new ClaimEntity("class", "http://example.org/external#Virus"), "subClassOf",
            new ClaimEntity("class", "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"),
            Optional.empty(), Optional.empty(), Optional.empty());
        ServiceResult<ClaimVerificationResult> result = service.verify(claim);
        assertTrue(result.isSuccess());
        assertEquals(Verdict.OUT_OF_SCOPE, ((ServiceResult.Success<ClaimVerificationResult>) result).data().verdict(),
            "SUBCLASS is NOT exempt; external subject IRI must be out-of-scope");
    }
}
