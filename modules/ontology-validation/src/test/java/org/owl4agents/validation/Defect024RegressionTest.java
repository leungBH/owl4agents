package org.owl4agents.validation;

import java.util.List;
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
import org.owl4agents.core.model.ClassCompatibilityResult;
import org.owl4agents.core.model.MembershipResult;
import org.owl4agents.core.model.RelationAssertionResult;
import org.owl4agents.core.model.ScopeDescription;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

/**
 * Regression tests for DEFECT-024: ClaimVerificationService.mapError()
 * must not throw IllegalArgumentException when entityIRI or ontologyId
 * in error details is blank/null.
 *
 * Root cause: When ConsistencyAnalysisService.checkClassCompatibility()
 * returned CLASS_NOT_FOUND for an out-of-scope entity, mapError() tried
 * to construct EntityId("") and OntologyId(""), which throw IAE due to
 * their compact constructor validation.
 */
@DisplayName("DEFECT-024 regression: mapError blank IRI guard")
class Defect024RegressionTest {

    private StubReasonerService stubReasoner;
    private Defect024StubConsistencyService stubConsistency;
    private ClaimVerificationService service;

    @BeforeEach
    void setUp() {
        stubReasoner = new StubReasonerService();
        stubConsistency = new Defect024StubConsistencyService();
        service = new ClaimVerificationService(
            stubReasoner,
            stubConsistency,
            new SemanticDeepeningService("dummy-path"),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    @Nested
    @DisplayName("mapError does not throw on blank entity IRI in error details")
    class MapErrorBlankIriTests {

        @Test
        @DisplayName("CLASS_NOT_FOUND with blank entityIRI → error, not IAE")
        void classNotFoundBlankEntityIriNoCrash() {
            // Simulate the exact crash scenario from DEFECT-024:
            // checkClassCompatibility returns CLASS_NOT_FOUND with no/blank entityIRI
            stubConsistency.withCompatibilityError(
                ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "Class not found", Map.of())
            );

            Claim claim = new Claim("d024-1", ClaimType.DISJOINT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "disjointWith",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            // Before fix: this threw IllegalArgumentException("Entity IRI must not be null or blank")
            // After fix: returns ServiceResult.Error with appropriate error
            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertFalse(result.isSuccess(), "Should return error for class not found");

            ServiceError error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
            assertEquals(ErrorCode.CLASS_NOT_FOUND, error.code(),
                "Error code must be CLASS_NOT_FOUND");
            assertNotNull(error.message(), "Error message must not be null");
            // Must NOT throw IllegalArgumentException
        }

        @Test
        @DisplayName("CLASS_NOT_FOUND with empty-string entityIRI → error, not IAE")
        void classNotFoundEmptyStringEntityIriNoCrash() {
            // Entity IRI in details is empty string — would have created EntityId("")
            stubConsistency.withCompatibilityError(
                ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "Class not found",
                    Map.of("entityIRI", ""))
            );

            Claim claim = new Claim("d024-2", ClaimType.DISJOINT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "disjointWith",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertFalse(result.isSuccess());
            // Must NOT throw IllegalArgumentException
        }

        @Test
        @DisplayName("ONTOLOGY_NOT_FOUND with blank ontologyId → error, not IAE")
        void ontologyNotFoundBlankOntologyIdNoCrash() {
            // Ontology ID in error details is empty — would have created OntologyId("")
            StubCatalogStore catalog = new StubCatalogStore()
                .withNotFound("missing-ont");

            ClaimVerificationService serviceWithNotFound = new ClaimVerificationService(
                stubReasoner,
                stubConsistency,
                new SemanticDeepeningService("dummy-path"),
                catalog,
                new WorkspaceId("default")
            );

            Claim claim = new Claim("d024-4", ClaimType.DISJOINT_CLASSES, "missing-ont",
                new ClaimEntity("class", "http://ex.org/A"), "disjointWith",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = serviceWithNotFound.verify(claim);
            assertFalse(result.isSuccess());
            ServiceError error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
            assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, error.code());
            // Must NOT throw IllegalArgumentException
        }

        @Test
        @DisplayName("CLASS_NOT_FOUND with valid entityIRI → proper error with IRI in message")
        void classNotFoundValidEntityIriReturnsProperError() {
            // When entityIRI IS present and non-blank, should still work correctly
            stubConsistency.withCompatibilityError(
                ServiceError.of(ErrorCode.CLASS_NOT_FOUND, "Class not found",
                    Map.of("entityIRI", "http://ex.org/OutOfScopeClass"))
            );

            Claim claim = new Claim("d024-5", ClaimType.DISJOINT_CLASSES, "test-ontology",
                new ClaimEntity("class", "http://ex.org/A"), "disjointWith",
                new ClaimEntity("class", "http://ex.org/B"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<ClaimVerificationResult> result = service.verify(claim);
            assertFalse(result.isSuccess());
            ServiceError error = ((ServiceResult.Error<ClaimVerificationResult>) result).error();
            assertEquals(ErrorCode.CLASS_NOT_FOUND, error.code());
            assertTrue(error.message().contains("http://ex.org/OutOfScopeClass"),
                "Valid entity IRI must appear in error message");
        }
    }

    /**
     * Stub consistency service that can be configured to return specific errors,
     * including CLASS_NOT_FOUND with blank entityIRI (the DEFECT-024 crash scenario).
     */
    private static class Defect024StubConsistencyService extends ConsistencyAnalysisService {

        private ServiceError compatibilityError = null;

        Defect024StubConsistencyService() {
            super(new ReasonerLifecycleManager(), "dummy-path");
        }

        Defect024StubConsistencyService withCompatibilityError(ServiceError error) {
            this.compatibilityError = error;
            return this;
        }

        @Override
        public ServiceResult<ClassCompatibilityResult> checkClassCompatibility(
                OntologyId ontologyId, String class1IRI, String class2IRI) {
            if (compatibilityError != null) {
                return ServiceResult.error(compatibilityError);
            }
            // Default: return compatible result
            ClassCompatibilityResult compat = new ClassCompatibilityResult(
                ontologyId.id(), class1IRI, class2IRI,
                ClassCompatibilityResult.COMPATIBLE,
                null);
            return ServiceResult.success(compat, org.owl4agents.core.ResultMetadata.empty());
        }

        @Override
        public boolean isEntityDeclared(OntologyId ontologyId, String entityIRI, String kind) {
            return true; // stub: everything is declared
        }

        @Override
        public ServiceResult<ScopeDescription> getScope(OntologyId ontologyId) {
            ScopeDescription scope = new ScopeDescription(
                ontologyId.id(), List.of("test"), List.of(), List.of(), List.of());
            return ServiceResult.success(scope, org.owl4agents.core.ResultMetadata.empty());
        }

        @Override
        public ServiceResult<MembershipResult> checkIndividualMembership(
                OntologyId ontologyId, String individualIRI, String classIRI, Optional<String> reasoner) {
            MembershipResult result = new MembershipResult(
                ontologyId.id(), individualIRI, classIRI, true,
                MembershipResult.EXPLICIT, null);
            return ServiceResult.success(result, org.owl4agents.core.ResultMetadata.empty());
        }

        @Override
        public ServiceResult<RelationAssertionResult> checkRelationAssertion(
                OntologyId ontologyId, String sourceIRI, String propertyIRI, String targetIRI, Optional<String> reasoner) {
            RelationAssertionResult result = new RelationAssertionResult(
                ontologyId.id(), sourceIRI, propertyIRI, targetIRI, true,
                RelationAssertionResult.EXPLICIT, null);
            return ServiceResult.success(result, org.owl4agents.core.ResultMetadata.empty());
        }
    }
}