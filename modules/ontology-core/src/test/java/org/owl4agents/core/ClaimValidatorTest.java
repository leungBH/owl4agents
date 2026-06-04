package org.owl4agents.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for ClaimValidator — verifies that malformed and free-text-only
 * claims are rejected, and that all supported v0.3 structured claim types are accepted.
 */
@DisplayName("ClaimValidator contract tests")
class ClaimValidatorTest {

    private final ClaimValidator validator = new ClaimValidator();

    // ── Rejection tests (Task 2.4) ──

    @Nested
    @DisplayName("Rejection: null and missing required fields")
    class RequiredFieldRejectionTests {

        @Test
        @DisplayName("Null claim is rejected with INVALID_CLAIM_SCHEMA")
        void nullClaimRejected() {
            ServiceResult<Claim> result = validator.validate(null);
            assertFalse(result.isSuccess());
            ServiceError error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("must not be null"));
        }

        @Test
        @DisplayName("Missing claimId is rejected")
        void missingClaimIdRejected() {
            Claim claim = new Claim(null, ClaimType.SUBCLASS, "ont",
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "claimId");
        }

        @Test
        @DisplayName("Blank claimId is rejected")
        void blankClaimIdRejected() {
            Claim claim = new Claim("  ", ClaimType.SUBCLASS, "ont",
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "claimId");
        }

        @Test
        @DisplayName("Missing type is rejected")
        void missingTypeRejected() {
            Claim claim = new Claim("c1", null, "ont",
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "type");
        }

        @Test
        @DisplayName("Missing ontologyId is rejected")
        void missingOntologyIdRejected() {
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, null,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "ontologyId");
        }

        @Test
        @DisplayName("Blank ontologyId is rejected")
        void blankOntologyIdRejected() {
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, "  ",
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "ontologyId");
        }
    }

    @Nested
    @DisplayName("Rejection: entity kind mismatches")
    class EntityKindMismatchTests {

        @Test
        @DisplayName("SUBCLASS with individual subject is rejected")
        void subclassWrongSubjectKind() {
            Claim claim = buildClaim(ClaimType.SUBCLASS,
                new ClaimEntity("individual", "http://ex.org#a"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "subject.kind must be 'class'");
        }

        @Test
        @DisplayName("SUBCLASS with individual object is rejected")
        void subclassWrongObjectKind() {
            Claim claim = buildClaim(ClaimType.SUBCLASS,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("individual", "http://ex.org#a"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "object.kind must be 'class'");
        }

        @Test
        @DisplayName("INDIVIDUAL_MEMBERSHIP with class subject is rejected")
        void individualMembershipWrongSubject() {
            Claim claim = buildClaim(ClaimType.INDIVIDUAL_MEMBERSHIP,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "subject.kind must be 'individual'");
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_ASSERTION with class object is rejected")
        void objectPropertyAssertionWrongObject() {
            Claim claim = buildClaim(ClaimType.OBJECT_PROPERTY_ASSERTION,
                new ClaimEntity("individual", "http://ex.org#a"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "object.kind must be 'individual'");
        }

        @Test
        @DisplayName("DATA_PROPERTY_ASSERTION with class object is rejected")
        void dataPropertyAssertionWrongObject() {
            Claim claim = buildClaim(ClaimType.DATA_PROPERTY_ASSERTION,
                new ClaimEntity("individual", "http://ex.org#a"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "object.kind must be 'literal'");
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_DOMAIN with individual subject is rejected")
        void objectPropertyDomainWrongSubject() {
            Claim claim = buildClaim(ClaimType.OBJECT_PROPERTY_DOMAIN,
                new ClaimEntity("individual", "http://ex.org#a"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "subject.kind must be 'object_property'");
        }

        @Test
        @DisplayName("DATA_PROPERTY_RANGE with class object is rejected")
        void dataPropertyRangeWrongObject() {
            Claim claim = buildClaim(ClaimType.DATA_PROPERTY_RANGE,
                new ClaimEntity("data_property", "http://ex.org#p"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "object.kind must be 'datatype'");
        }
    }

    @Nested
    @DisplayName("Rejection: missing required entities")
    class MissingEntityRejectionTests {

        @Test
        @DisplayName("SUBCLASS with missing subject is rejected")
        void subclassMissingSubject() {
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, "ont",
                null, null,
                new ClaimEntity("class", "http://ex.org#B"),
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "subject is required");
        }

        @Test
        @DisplayName("SUBCLASS with missing object is rejected")
        void subclassMissingObject() {
            Claim claim = new Claim("c1", ClaimType.SUBCLASS, "ont",
                new ClaimEntity("class", "http://ex.org#A"), null, null,
                Optional.empty(), Optional.empty(), Optional.empty());
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "object is required");
        }
    }

    @Nested
    @DisplayName("Rejection: invalid entity kinds")
    class InvalidKindRejectionTests {

        @Test
        @DisplayName("Invalid subject kind is rejected")
        void invalidSubjectKind() {
            Claim claim = buildClaim(ClaimType.SUBCLASS,
                new ClaimEntity("ontology", "http://ex.org#O"), null,
                new ClaimEntity("class", "http://ex.org#B"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "not a valid OWL entity kind");
        }

        @Test
        @DisplayName("Invalid object kind (non-literal, non-OWL) is rejected")
        void invalidObjectKind() {
            Claim claim = buildClaim(ClaimType.SUBCLASS,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("ontology", "http://ex.org#O"));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "not a valid entity kind");
        }
    }

    @Nested
    @DisplayName("Rejection: invalid evidence options")
    class EvidenceOptionRejectionTests {

        @Test
        @DisplayName("includeEvidence as string is rejected")
        void includeEvidenceWrongType() {
            Claim claim = new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(),
                Optional.of(Map.of(Claim.INCLUDE_EVIDENCE, "yes")));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "includeEvidence must be a boolean");
        }

        @Test
        @DisplayName("maxEvidence as string is rejected")
        void maxEvidenceWrongType() {
            Claim claim = new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(),
                Optional.of(Map.of(Claim.MAX_EVIDENCE, "five")));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "maxEvidence must be a number");
        }

        @Test
        @DisplayName("maxEvidence zero is rejected")
        void maxEvidenceZeroRejected() {
            Claim claim = new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(),
                Optional.of(Map.of(Claim.MAX_EVIDENCE, 0)));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "maxEvidence must be a positive integer");
        }

        @Test
        @DisplayName("maxEvidence negative is rejected")
        void maxEvidenceNegativeRejected() {
            Claim claim = new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(),
                Optional.of(Map.of(Claim.MAX_EVIDENCE, -1)));
            ServiceResult<Claim> result = validator.validate(claim);
            assertSchemaError(result, "maxEvidence must be a positive integer");
        }
    }

    // ── Acceptance tests (Task 2.5) ──

    @Nested
    @DisplayName("Acceptance: all supported v0.3 claim types")
    class ClaimTypeAcceptanceTests {

        @Test
        @DisplayName("SUBCLASS claim is accepted")
        void subclassAccepted() {
            assertValid(buildClaim(ClaimType.SUBCLASS,
                new ClaimEntity("class", "http://ex.org#GoldenRetriever"), null,
                new ClaimEntity("class", "http://ex.org#Dog")));
        }

        @Test
        @DisplayName("EQUIVALENT_CLASSES claim is accepted")
        void equivalentClassesAccepted() {
            assertValid(buildClaim(ClaimType.EQUIVALENT_CLASSES,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B")));
        }

        @Test
        @DisplayName("DISJOINT_CLASSES claim is accepted")
        void disjointClassesAccepted() {
            assertValid(buildClaim(ClaimType.DISJOINT_CLASSES,
                new ClaimEntity("class", "http://ex.org#Cat"), null,
                new ClaimEntity("class", "http://ex.org#Dog")));
        }

        @Test
        @DisplayName("CLASS_COMPATIBILITY claim is accepted")
        void classCompatibilityAccepted() {
            assertValid(buildClaim(ClaimType.CLASS_COMPATIBILITY,
                new ClaimEntity("class", "http://ex.org#A"), null,
                new ClaimEntity("class", "http://ex.org#B")));
        }

        @Test
        @DisplayName("INDIVIDUAL_MEMBERSHIP claim is accepted")
        void individualMembershipAccepted() {
            assertValid(buildClaim(ClaimType.INDIVIDUAL_MEMBERSHIP,
                new ClaimEntity("individual", "http://ex.org#alice"), null,
                new ClaimEntity("class", "http://ex.org#Student")));
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_ASSERTION claim is accepted")
        void objectPropertyAssertionAccepted() {
            assertValid(buildClaim(ClaimType.OBJECT_PROPERTY_ASSERTION,
                new ClaimEntity("individual", "http://ex.org#alice"), null,
                new ClaimEntity("individual", "http://ex.org#bob")));
        }

        @Test
        @DisplayName("DATA_PROPERTY_ASSERTION claim is accepted")
        void dataPropertyAssertionAccepted() {
            assertValid(buildClaim(ClaimType.DATA_PROPERTY_ASSERTION,
                new ClaimEntity("individual", "http://ex.org#alice"), null,
                new ClaimEntity("literal", "25")));
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_DOMAIN claim is accepted")
        void objectPropertyDomainAccepted() {
            assertValid(buildClaim(ClaimType.OBJECT_PROPERTY_DOMAIN,
                new ClaimEntity("object_property", "http://ex.org#worksFor"), null,
                new ClaimEntity("class", "http://ex.org#Person")));
        }

        @Test
        @DisplayName("OBJECT_PROPERTY_RANGE claim is accepted")
        void objectPropertyRangeAccepted() {
            assertValid(buildClaim(ClaimType.OBJECT_PROPERTY_RANGE,
                new ClaimEntity("object_property", "http://ex.org#worksFor"), null,
                new ClaimEntity("class", "http://ex.org#Organization")));
        }

        @Test
        @DisplayName("DATA_PROPERTY_DOMAIN claim is accepted")
        void dataPropertyDomainAccepted() {
            assertValid(buildClaim(ClaimType.DATA_PROPERTY_DOMAIN,
                new ClaimEntity("data_property", "http://ex.org#hasAge"), null,
                new ClaimEntity("class", "http://ex.org#Person")));
        }

        @Test
        @DisplayName("DATA_PROPERTY_RANGE claim is accepted")
        void dataPropertyRangeAccepted() {
            assertValid(buildClaim(ClaimType.DATA_PROPERTY_RANGE,
                new ClaimEntity("data_property", "http://ex.org#hasAge"), null,
                new ClaimEntity("datatype", "http://ex.org#AgeType")));
        }

        @Test
        @DisplayName("LITERAL_VALIDITY claim is accepted")
        void literalValidityAccepted() {
            assertValid(buildClaim(ClaimType.LITERAL_VALIDITY,
                new ClaimEntity("datatype", "http://ex.org#AgeType"), null,
                new ClaimEntity("literal", "25")));
        }

        @Test
        @DisplayName("ONTOLOGY_CONSISTENCY claim is accepted without subject/object")
        void ontologyConsistencyAccepted() {
            assertValid(new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("ONTOLOGY_SCOPE claim is accepted without subject/object")
        void ontologyScopeAccepted() {
            assertValid(new Claim("c1", ClaimType.ONTOLOGY_SCOPE, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty()));
        }
    }

    @Nested
    @DisplayName("Acceptance: optional fields")
    class OptionalFieldAcceptanceTests {

        @Test
        @DisplayName("Claim with reasoner is accepted")
        void claimWithReasoner() {
            assertValid(new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.of("hermit"), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("Claim with graphScope is accepted")
        void claimWithGraphScope() {
            assertValid(new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.of(GraphScope.INFERRED), Optional.empty()));
        }

        @Test
        @DisplayName("Claim with valid evidence options is accepted")
        void claimWithValidOptions() {
            assertValid(new Claim("c1", ClaimType.ONTOLOGY_CONSISTENCY, "ont",
                null, null, null,
                Optional.empty(), Optional.empty(),
                Optional.of(Map.of(Claim.INCLUDE_EVIDENCE, true, Claim.MAX_EVIDENCE, 5))));
        }
    }

    // ── Helpers ──

    private Claim buildClaim(ClaimType type, ClaimEntity subject, String predicate, ClaimEntity object) {
        return new Claim("c1", type, "ont", subject, predicate, object,
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private void assertValid(Claim claim) {
        ServiceResult<Claim> result = validator.validate(claim);
        assertTrue(result.isSuccess(), "Expected claim to be valid but got error: "
            + (result.isSuccess() ? "" : ((ServiceResult.Error<Claim>) result).error().message()));
    }

    private void assertSchemaError(ServiceResult<Claim> result, String expectedFragment) {
        assertFalse(result.isSuccess());
        ServiceError error = ((ServiceResult.Error<Claim>) result).error();
        assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
        assertTrue(error.message().contains(expectedFragment),
            "Expected message to contain '" + expectedFragment + "' but got: " + error.message());
    }
}