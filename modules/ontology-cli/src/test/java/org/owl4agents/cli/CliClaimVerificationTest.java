package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI acceptance tests for v0.3 claim verification commands.
 * Tests cover claim validation, error handling, verdict/model structure, and service factory access.
 * Note: Gson cannot deserialize Java records with Optional fields, so claim construction tests
 * use programmatic construction instead of JSON deserialization.
 */
@DisplayName("CLI v0.3 claim verification tests")
class CliClaimVerificationTest {

    @TempDir
    Path tempDir;

    // --- Claim parsing and validation tests (shared by all v0.3 commands) ---

    @Nested
    @DisplayName("Claim validation")
    class ClaimValidationTests {

        private final ClaimValidator validator = new ClaimValidator();

        @Test
        @DisplayName("Valid SUBCLASS claim passes validation")
        void validSubclassClaim() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
            assertEquals(claim, ((ServiceResult.Success<Claim>) result).data());
        }

        @Test
        @DisplayName("Valid DISJOINT_CLASSES claim passes validation")
        void validDisjointClaim() {
            Claim claim = new Claim("test-002", ClaimType.DISJOINT_CLASSES, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "disjointWith",
                new ClaimEntity("class", "http://example.org#Cat"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid INDIVIDUAL_MEMBERSHIP claim passes validation")
        void validIndividualMembershipClaim() {
            Claim claim = new Claim("test-003", ClaimType.INDIVIDUAL_MEMBERSHIP, "test-ont",
                new ClaimEntity("individual", "http://example.org#Fido"),
                "typeOf",
                new ClaimEntity("class", "http://example.org#Dog"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid OBJECT_PROPERTY_ASSERTION claim passes validation")
        void validObjectPropertyAssertionClaim() {
            Claim claim = new Claim("test-004", ClaimType.OBJECT_PROPERTY_ASSERTION, "test-ont",
                new ClaimEntity("individual", "http://example.org#Fido"),
                "relation",
                new ClaimEntity("individual", "http://example.org#PersonJohn"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid DATA_PROPERTY_ASSERTION claim passes validation")
        void validDataPropertyAssertionClaim() {
            Claim claim = new Claim("test-005", ClaimType.DATA_PROPERTY_ASSERTION, "test-ont",
                new ClaimEntity("individual", "http://example.org#Fido"),
                "hasValue",
                new ClaimEntity("literal", "5"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid ONTOLOGY_SCOPE claim passes validation (no entity requirements)")
        void validOntologyScopeClaim() {
            Claim claim = new Claim("test-006", ClaimType.ONTOLOGY_SCOPE, "test-ont",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid ONTOLOGY_CONSISTENCY claim passes validation (no entity requirements)")
        void validOntologyConsistencyClaim() {
            Claim claim = new Claim("test-007", ClaimType.ONTOLOGY_CONSISTENCY, "test-ont",
                null, null, null,
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid OBJECT_PROPERTY_DOMAIN claim passes validation")
        void validPropertyDomainClaim() {
            Claim claim = new Claim("test-008", ClaimType.OBJECT_PROPERTY_DOMAIN, "test-ont",
                new ClaimEntity("object_property", "http://example.org#hasOwner"),
                "domain",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid DATA_PROPERTY_RANGE claim passes validation")
        void validDataPropertyRangeClaim() {
            Claim claim = new Claim("test-009", ClaimType.DATA_PROPERTY_RANGE, "test-ont",
                new ClaimEntity("data_property", "http://example.org#hasAge"),
                "range",
                new ClaimEntity("datatype", "http://www.w3.org/2001/XMLSchema#nonNegativeInteger"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid LITERAL_VALIDITY claim passes validation")
        void validLiteralValidityClaim() {
            Claim claim = new Claim("test-010", ClaimType.LITERAL_VALIDITY, "test-ont",
                new ClaimEntity("datatype", "http://www.w3.org/2001/XMLSchema#nonNegativeInteger"),
                "conformsTo",
                new ClaimEntity("literal", "5"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid CLASS_COMPATIBILITY claim passes validation")
        void validClassCompatibilityClaim() {
            Claim claim = new Claim("test-011", ClaimType.CLASS_COMPATIBILITY, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "compatibleWith",
                new ClaimEntity("class", "http://example.org#Cat"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Valid EQUIVALENT_CLASSES claim passes validation")
        void validEquivalentClassesClaim() {
            Claim claim = new Claim("test-012", ClaimType.EQUIVALENT_CLASSES, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "equivalentTo",
                new ClaimEntity("class", "http://example.org#Canine"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }
    }

    // --- Error handling tests ---

    @Nested
    @DisplayName("Claim validation errors")
    class ClaimValidationErrorTests {

        private final ClaimValidator validator = new ClaimValidator();

        @Test
        @DisplayName("Null claim returns INVALID_CLAIM_SCHEMA")
        void nullClaim() {
            ServiceResult<Claim> result = validator.validate(null);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
        }

        @Test
        @DisplayName("Missing claimId returns INVALID_CLAIM_SCHEMA")
        void missingClaimId() {
            Claim claim = new Claim(null, ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("claimId"));
        }

        @Test
        @DisplayName("Missing type returns INVALID_CLAIM_SCHEMA")
        void missingType() {
            Claim claim = new Claim("test-001", null, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("type"));
        }

        @Test
        @DisplayName("Missing ontologyId returns INVALID_CLAIM_SCHEMA")
        void missingOntologyId() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, null,
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("ontologyId"));
        }

        @Test
        @DisplayName("Wrong entity kind for subject returns INVALID_CLAIM_SCHEMA")
        void wrongSubjectKind() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("individual", "http://example.org#Fido"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("subject.kind"));
        }

        @Test
        @DisplayName("Wrong entity kind for object returns INVALID_CLAIM_SCHEMA")
        void wrongObjectKind() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("individual", "http://example.org#Fido"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("object.kind"));
        }

        @Test
        @DisplayName("Missing required subject returns INVALID_CLAIM_SCHEMA")
        void missingRequiredSubject() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                null, "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("subject is required"));
        }

        @Test
        @DisplayName("Invalid entity kind returns INVALID_CLAIM_SCHEMA")
        void invalidEntityKind() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("unknown_kind", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("not a valid OWL entity kind"));
        }

        @Test
        @DisplayName("Evidence options with non-boolean includeEvidence returns INVALID_CLAIM_SCHEMA")
        void invalidIncludeEvidence() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(),
                Optional.of(java.util.Map.of("includeEvidence", "yes")));

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("includeEvidence must be a boolean"));
        }

        @Test
        @DisplayName("Evidence options with zero maxEvidence returns INVALID_CLAIM_SCHEMA")
        void zeroMaxEvidence() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(),
                Optional.of(java.util.Map.of("maxEvidence", 0)));

            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("maxEvidence must be a positive integer"));
        }
    }

    // --- Claim structure tests (programmatic construction instead of Gson deserialization) ---
    // Gson cannot deserialize Java records with Optional fields due to InaccessibleObjectException.
    // CLI commands handle this at runtime with their own parseClaim logic.

    @Nested
    @DisplayName("Claim structure and field validation")
    class ClaimStructureTests {

        @Test
        @DisplayName("All required fields present produces valid claim")
        void allRequiredFieldsPresent() {
            Claim claim = new Claim("test-001", ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            assertNotNull(claim.claimId());
            assertEquals(ClaimType.SUBCLASS, claim.type());
            assertEquals("test-ont", claim.ontologyId());
            assertNotNull(claim.subject());
            assertEquals("class", claim.subject().kind());
            assertEquals("http://example.org#Dog", claim.subject().iri());
            assertNotNull(claim.object());
            assertEquals("class", claim.object().kind());
            assertEquals("http://example.org#Animal", claim.object().iri());

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Missing claimId fails validation with INVALID_CLAIM_SCHEMA")
        void missingClaimIdFailsValidation() {
            Claim claim = new Claim(null, ClaimType.SUBCLASS, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("claimId"));
        }

        @Test
        @DisplayName("Null type fails validation with INVALID_CLAIM_SCHEMA")
        void nullTypeFailsValidation() {
            Claim claim = new Claim("test-001", null, "test-ont",
                new ClaimEntity("class", "http://example.org#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org#Animal"),
                Optional.empty(), Optional.empty(), Optional.empty());

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertFalse(result.isSuccess());
            var error = ((ServiceResult.Error<Claim>) result).error();
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, error.code());
            assertTrue(error.message().contains("type is required"));
        }

        @Test
        @DisplayName("Fixture data constructs valid supported claim")
        void fixtureSupportedClaimConstructsValidly() {
            Claim claim = new Claim("claim-supported-001", ClaimType.SUBCLASS, "v0.3-claim-verification",
                new ClaimEntity("class", "http://example.org/v0.3#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org/v0.3#Animal"),
                Optional.empty(), Optional.of(GraphScope.EXPLICIT),
                Optional.of(java.util.Map.of("includeEvidence", true)));

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
            assertEquals("claim-supported-001", claim.claimId());
            assertEquals(ClaimType.SUBCLASS, claim.type());
            assertEquals("v0.3-claim-verification", claim.ontologyId());
        }

        @Test
        @DisplayName("Fixture data constructs valid contradicted claim")
        void fixtureContradictedClaimConstructsValidly() {
            Claim claim = new Claim("claim-contradicted-001", ClaimType.DISJOINT_CLASSES, "v0.3-claim-verification",
                new ClaimEntity("class", "http://example.org/v0.3#Dog"),
                "disjointWith",
                new ClaimEntity("class", "http://example.org/v0.3#Cat"),
                Optional.empty(), Optional.of(GraphScope.EXPLICIT),
                Optional.of(java.util.Map.of("includeEvidence", true)));

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Fixture data constructs valid unknown claim")
        void fixtureUnknownClaimConstructsValidly() {
            Claim claim = new Claim("claim-unknown-001", ClaimType.SUBCLASS, "v0.3-claim-verification",
                new ClaimEntity("class", "http://example.org/v0.3#Goldfish"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org/v0.3#Fish"),
                Optional.empty(), Optional.of(GraphScope.EXPLICIT),
                Optional.of(java.util.Map.of("includeEvidence", true)));

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Fixture data constructs valid out-of-scope claim")
        void fixtureOutOfScopeClaimConstructsValidly() {
            Claim claim = new Claim("claim-out-of-scope-001", ClaimType.ONTOLOGY_SCOPE, "v0.3-claim-verification",
                null, null, null,
                Optional.empty(), Optional.of(GraphScope.EXPLICIT),
                Optional.of(java.util.Map.of("includeEvidence", true)));

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> result = validator.validate(claim);
            assertTrue(result.isSuccess());
        }
    }

    // --- Service factory tests for v0.3 services ---

    @Nested
    @DisplayName("Service factory v0.3 services")
    class ServiceFactoryTests {

        @Test
        @DisplayName("CLI service factory creates claim verification service")
        void createsClaimVerificationService() {
            CliServiceFactory factory = new CliServiceFactory("default", tempDir.toString());

            assertNotNull(factory.getClaimVerificationService());
            // Same factory returns same instance
            assertSame(factory.getClaimVerificationService(), factory.getClaimVerificationService());
        }

        @Test
        @DisplayName("CLI service factory creates evidence grounding service")
        void createsEvidenceGroundingService() {
            CliServiceFactory factory = new CliServiceFactory("default", tempDir.toString());

            assertNotNull(factory.getEvidenceGroundingService());
            assertSame(factory.getEvidenceGroundingService(), factory.getEvidenceGroundingService());
        }

        @Test
        @DisplayName("CLI service factory creates all v0.3 dependent services")
        void createsAllV3DependentServices() {
            CliServiceFactory factory = new CliServiceFactory("default", tempDir.toString());

            assertNotNull(factory.getReasonerService());
            assertNotNull(factory.getConsistencyAnalysisService());
            assertNotNull(factory.getSemanticDeepeningService());
            assertNotNull(factory.getClaimVerificationService());
            assertNotNull(factory.getEvidenceGroundingService());
        }
    }

    // --- Verdict and model tests ---

    @Nested
    @DisplayName("Verdict and model structure")
    class VerdictAndModelTests {

        @Test
        @DisplayName("Verdict order: OUT_OF_SCOPE, SUPPORTED, CONTRADICTED, UNKNOWN")
        void verdictOrder() {
            assertArrayEquals(
                new Verdict[] { Verdict.OUT_OF_SCOPE, Verdict.SUPPORTED, Verdict.CONTRADICTED, Verdict.UNKNOWN },
                Verdict.values()
            );
        }

        @Test
        @DisplayName("All 14 claim types are defined")
        void allClaimTypesDefined() {
            assertEquals(14, ClaimType.values().length);
        }

        @Test
        @DisplayName("All 8 evidence kinds are defined")
        void allEvidenceKindsDefined() {
            assertEquals(8, EvidenceKind.values().length);
        }

        @Test
        @DisplayName("All 8 unknown reasons are defined")
        void allUnknownReasonsDefined() {
            assertEquals(8, UnknownReason.values().length);
        }

        @Test
        @DisplayName("Verdict jsonName matches contract names")
        void verdictJsonNames() {
            assertEquals("out_of_scope", Verdict.OUT_OF_SCOPE.jsonName());
            assertEquals("supported", Verdict.SUPPORTED.jsonName());
            assertEquals("contradicted", Verdict.CONTRADICTED.jsonName());
            assertEquals("unknown", Verdict.UNKNOWN.jsonName());
        }
    }

    // --- Error code coverage tests ---

    @Nested
    @DisplayName("v0.3 error codes")
    class ErrorCodeTests {

        @Test
        @DisplayName("INVALID_CLAIM_SCHEMA error code exists")
        void invalidClaimSchemaCode() {
            assertNotNull(ErrorCode.INVALID_CLAIM_SCHEMA);
        }

        @Test
        @DisplayName("UNSUPPORTED_CLAIM_TYPE error code exists")
        void unsupportedClaimTypeCode() {
            assertNotNull(ErrorCode.UNSUPPORTED_CLAIM_TYPE);
        }

        @Test
        @DisplayName("REASONING_NOT_RUN error code exists")
        void reasoningNotRunCode() {
            assertNotNull(ErrorCode.REASONING_NOT_RUN);
        }

        @Test
        @DisplayName("REASONER_NOT_AVAILABLE error code exists")
        void reasonerNotAvailableCode() {
            assertNotNull(ErrorCode.REASONER_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("PROFILE_NOT_SUPPORTED error code exists")
        void profileNotSupportedCode() {
            assertNotNull(ErrorCode.PROFILE_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("INVALID_AXIOM_PARAMETERS error code exists")
        void invalidAxiomParametersCode() {
            assertNotNull(ErrorCode.INVALID_AXIOM_PARAMETERS);
        }

        @Test
        @DisplayName("ServiceError factory methods produce correct codes")
        void serviceErrorFactoryMethods() {
            ServiceError invalidSchema = ServiceError.invalidClaimSchema("test");
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, invalidSchema.code());

            ServiceError unsupportedType = ServiceError.unsupportedClaimType("verify_answer", java.util.List.of("subclass"));
            assertEquals(ErrorCode.UNSUPPORTED_CLAIM_TYPE, unsupportedType.code());
        }
    }

    // --- ClaimParser error code differentiation tests ---

    @Nested
    @DisplayName("ClaimParser error code differentiation")
    class ClaimParserErrorCodeTests {

        @Test
        @DisplayName("Unsupported claim type returns UNSUPPORTED_CLAIM_TYPE ParseResult")
        void unsupportedClaimTypeReturnsCorrectErrorCode() {
            String json = "{\"claimId\":\"c1\",\"type\":\"verify_answer\",\"ontologyId\":\"ont1\","
                + "\"subject\":{\"kind\":\"class\",\"iri\":\"http://ex.org/A\"},"
                + "\"predicate\":\"subClassOf\","
                + "\"object\":{\"kind\":\"class\",\"iri\":\"http://ex.org/B\"}}";

            ClaimParser.ParseResult result = ClaimParser.parseClaim(json);
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.UNSUPPORTED_CLAIM_TYPE, result.errorCode());
            assertTrue(result.errorMessage().contains("verify_answer"));
        }

        @Test
        @DisplayName("Malformed JSON returns INVALID_CLAIM_SCHEMA ParseResult")
        void malformedJsonReturnsInvalidSchema() {
            String json = "not-valid-json";

            ClaimParser.ParseResult result = ClaimParser.parseClaim(json);
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, result.errorCode());
        }

        @Test
        @DisplayName("Missing type field returns INVALID_CLAIM_SCHEMA via ClaimValidator (not UNSUPPORTED_CLAIM_TYPE)")
        void missingTypeReturnsInvalidSchema() {
            String json = "{\"claimId\":\"c1\",\"ontologyId\":\"ont1\","
                + "\"subject\":{\"kind\":\"class\",\"iri\":\"http://ex.org/A\"},"
                + "\"predicate\":\"subClassOf\","
                + "\"object\":{\"kind\":\"class\",\"iri\":\"http://ex.org/B\"}}";

            ClaimParser.ParseResult result = ClaimParser.parseClaim(json);
            // type field is null → Claim.type is null → ClaimValidator catches it as INVALID_CLAIM_SCHEMA
            assertTrue(result.isSuccess());
            Claim claim = result.claim();
            assertNull(claim.type());

            ClaimValidator validator = new ClaimValidator();
            ServiceResult<Claim> validationResult = validator.validate(claim);
            assertFalse(validationResult.isSuccess());
            assertEquals(ErrorCode.INVALID_CLAIM_SCHEMA, ((ServiceResult.Error<Claim>) validationResult).error().code());
        }
    }
}