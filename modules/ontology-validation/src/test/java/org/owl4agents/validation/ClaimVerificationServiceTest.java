package org.owl4agents.validation;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.model.EntailmentResult;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * Task 3.9 tests: proves that lack of entailment alone returns UNKNOWN, never CONTRADICTED.
 *
 * Tests cover all entailment-based claim types that go through
 * reasonerService.checkEntailment() → mapEntailmentVerdict().
 */
class ClaimVerificationServiceTest {

    private StubReasonerService stubReasoner;
    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        service = new ClaimVerificationService(
            stubReasoner,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path"),
            new SemanticDeepeningService("dummy-path"),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    private OntologyId ontId() {
        return new OntologyId("test-ontology");
    }

    // ── Entailment-based claims: NOT_ENTAILED → UNKNOWN ──

    @Nested
    @DisplayName("Entailment-based claim types: NOT_ENTAILED → UNKNOWN")
    class EntailmentNotEntailedTests {

        @Test
        @DisplayName("SUBCLASS: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void subclassNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict(),
                "NOT_ENTAILED must never yield CONTRADICTED");
        }

        @Test
        @DisplayName("EQUIVALENT_CLASSES: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void equivalentClassesNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c2", ClaimType.EQUIVALENT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), null,
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict());
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_DOMAIN: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void objectPropertyDomainNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c3", ClaimType.OBJECT_PROPERTY_DOMAIN, "test-ontology",
                new ClaimEntity("object_property", "http://ex.org/p"), null,
                new ClaimEntity("class", "http://ex.org/C"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict());
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_RANGE: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void objectPropertyRangeNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c4", ClaimType.OBJECT_PROPERTY_RANGE, "test-ontology",
                new ClaimEntity("object_property", "http://ex.org/p"), null,
                new ClaimEntity("class", "http://ex.org/D"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict());
        }

        @Test
        @DisplayName("DATA_PROPERTY_DOMAIN: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void dataPropertyDomainNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c5", ClaimType.DATA_PROPERTY_DOMAIN, "test-ontology",
                new ClaimEntity("data_property", "http://ex.org/dp"), null,
                new ClaimEntity("class", "http://ex.org/E"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict());
        }

        @Test
        @DisplayName("DATA_PROPERTY_ASSERTION: NOT_ENTAILED → UNKNOWN, not CONTRADICTED")
        void dataPropertyAssertionNotEntailedYieldsUnknown() {
            stubReasoner.withEntailmentResult(EntailmentResult.NOT_ENTAILED);
            Claim claim = new Claim("c6", ClaimType.DATA_PROPERTY_ASSERTION, "test-ontology",
                new ClaimEntity("individual", "http://ex.org/i"), "http://ex.org/age",
                new ClaimEntity("literal", "42"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertNotEquals(Verdict.CONTRADICTED, data.verdict());
        }
    }

    // ── Entailment-based claims: ENTAILED → SUPPORTED ──

    @Nested
    @DisplayName("Entailment-based claim types: ENTAILED → SUPPORTED")
    class EntailmentEntailedTests {

        @Test
        @DisplayName("SUBCLASS: ENTAILED → SUPPORTED")
        void subclassEntailedYieldsSupported() {
            stubReasoner.withEntailmentResult(EntailmentResult.ENTAILED);
            Claim claim = new Claim("c7", ClaimType.SUBCLASS, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.SUPPORTED, data.verdict());
        }
    }

    // ── ONTOLOGY_CONSISTENCY: consistent → SUPPORTED ──

    @Nested
    @DisplayName("ONTOLOGY_CONSISTENCY: consistent and inconsistent paths")
    class OntologyConsistencyTests {

        @Test
        @DisplayName("ONTOLOGY_CONSISTENCY: consistent → SUPPORTED")
        void ontologyConsistentYieldsSupported() {
            stubReasoner.withConsistent(true);
            Claim claim = new Claim("c8", ClaimType.ONTOLOGY_CONSISTENCY, "test-ontology",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.SUPPORTED, data.verdict());
        }

        @Test
        @DisplayName("ONTOLOGY_CONSISTENCY: inconsistent → CONTRADICTED (explicit negative evidence)")
        void ontologyInconsistentYieldsContradicted() {
            stubReasoner.withConsistent(false);
            Claim claim = new Claim("c9", ClaimType.ONTOLOGY_CONSISTENCY, "test-ontology",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.CONTRADICTED, data.verdict());
            // CONTRADICTED is correct here because inconsistency IS direct negative evidence,
            // not merely lack of entailment.
        }
    }

    // ── UNSUPPORTED_AXIOM_TYPE → UNKNOWN with reason ──

    @Nested
    @DisplayName("UNSUPPORTED_AXIOM_TYPE → UNKNOWN with UNSUPPORTED_CLAIM_TYPE reason")
    class UnsupportedAxiomTypeTests {

        @Test
        @DisplayName("SUBCLASS: UNSUPPORTED_AXIOM_TYPE → UNKNOWN with UNSUPPORTED_CLAIM_TYPE reason")
        void subclassUnsupportedAxiomYieldsUnknownWithReason() {
            stubReasoner.withEntailmentResult(EntailmentResult.UNSUPPORTED_AXIOM_TYPE);
            Claim claim = new Claim("c10", ClaimType.SUBCLASS, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertTrue(result.isSuccess());
            ClaimVerificationResult data = ((ServiceResult.Success<ClaimVerificationResult>) result).data();
            assertEquals(Verdict.UNKNOWN, data.verdict());
            assertTrue(data.unknownReason().isPresent());
            assertEquals("unsupported_claim_type", data.unknownReason().get().jsonName());
        }
    }

    // ── ONTOLOGY_NOT_FOUND: catalog check before verification ──

    @Nested
    @DisplayName("ONTOLOGY_NOT_FOUND: catalog check returns error before verification")
    class OntologyNotFoundTests {

        @Test
        @DisplayName("Unknown ontology ID returns ONTOLOGY_NOT_FOUND error code")
        void unknownOntologyReturnsNotFoundErrorCode() {
            StubCatalogStore catalog = new StubCatalogStore().withNotFound("nonexistent-ontology");
            ClaimVerificationService serviceWithNotFound = new ClaimVerificationService(
                stubReasoner,
                new ConsistencyAnalysisService(new ReasonerLifecycleManager(), "dummy-path"),
                new SemanticDeepeningService("dummy-path"),
                catalog,
                new WorkspaceId("default")
            );

            Claim claim = new Claim("c11", ClaimType.SUBCLASS, "nonexistent-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "http://ex.org/subClassOf",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = serviceWithNotFound.verify(claim);
            assertFalse(result.isSuccess());
            ServiceError error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
            assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, error.code());
            assertTrue(error.message().contains("nonexistent-ontology"));
        }
    }
}