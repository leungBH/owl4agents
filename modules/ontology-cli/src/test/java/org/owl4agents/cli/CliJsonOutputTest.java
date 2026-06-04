package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for DEFECT-006 / DEFECT-006-R1: CLI --json output must
 * serialize model records with Optional fields without throwing
 * InaccessibleObjectException or JsonIOException.
 *
 * This test exercises GsonFactory.createGson() directly against all v0.3
 * model types that contain Optional fields, since the CLI --json execution
 * path was previously uncovered by unit tests (the MCP path uses a different
 * serializer and the text-output path does not call gson.toJson).
 */
@DisplayName("CLI --json regression tests")
class CliJsonOutputTest {

    private static final com.google.gson.Gson gson = GsonFactory.createGson();

    @Nested
    @DisplayName("ClaimVerificationResult serialization")
    class ClaimVerificationResultTests {

        @Test
        @DisplayName("Supported verdict serializes without JsonIOException")
        void supportedVerdictSerializes() {
            ClaimVerificationResult result = new ClaimVerificationResult(
                "claim-supported-001",
                "v0.3-claim-verification",
                ClaimType.SUBCLASS,
                Verdict.SUPPORTED,
                List.of(new EvidenceItem(
                    "entailment-claim-supported-001",
                    EvidenceItem.ROLE_SUPPORTING,
                    EvidenceKind.INFERRED_AXIOM,
                    "SubClassOf: http://example.org/v0.3#Dog → http://example.org/v0.3#Animal",
                    "inferred",
                    "HermiT",
                    "explicit",
                    List.of("http://example.org/v0.3#Dog", "http://example.org/v0.3#Animal"),
                    EvidenceItem.CONFIDENCE_ENTAILED
                )),
                Optional.empty(),
                Optional.empty(),
                Optional.of("HermiT"),
                Optional.of(GraphScope.EXPLICIT),
                false,
                1
            );

            String json = gson.toJson(result);
            assertNotNull(json);
            assertTrue(json.contains("\"verdict\":\"supported\""));
            assertTrue(json.contains("\"claimId\":\"claim-supported-001\""));
            assertTrue(json.contains("\"evidence\""));
        }

        @Test
        @DisplayName("Unknown verdict with Optional fields serializes correctly")
        void unknownVerdictSerializes() {
            ClaimVerificationResult result = new ClaimVerificationResult(
                "claim-unknown-001",
                "v0.3-claim-verification",
                ClaimType.SUBCLASS,
                Verdict.UNKNOWN,
                List.of(),
                Optional.of(UnknownReason.INSUFFICIENT_AXIOMS),
                Optional.of("No entailment found for Goldfish→Fish"),
                Optional.empty(),
                Optional.empty(),
                false,
                0
            );

            String json = gson.toJson(result);
            assertNotNull(json);
            assertTrue(json.contains("\"verdict\":\"unknown\""));
            assertTrue(json.contains("\"unknownReason\":\"insufficient_axioms\""));
            assertTrue(json.contains("\"unknownExplanation\":\"No entailment found for Goldfish→Fish\""));
        }

        @Test
        @DisplayName("All-Optional-empty fields serialize as null")
        void allOptionalEmptySerializesAsNull() {
            ClaimVerificationResult result = new ClaimVerificationResult(
                "claim-minimal",
                "v0.3-claim-verification",
                ClaimType.ONTOLOGY_SCOPE,
                Verdict.SUPPORTED,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                0
            );

            String json = gson.toJson(result);
            assertNotNull(json);
            // With serializeNulls, empty Optional fields should appear as null
            assertTrue(json.contains("\"unknownReason\":null"));
            assertTrue(json.contains("\"unknownExplanation\":null"));
            assertTrue(json.contains("\"reasonerName\":null"));
        }
    }

    @Nested
    @DisplayName("Claim serialization")
    class ClaimSerializationTests {

        @Test
        @DisplayName("Claim with Optional fields serializes without exception")
        void claimSerializes() {
            Claim claim = new Claim(
                "test-claim",
                ClaimType.SUBCLASS,
                "v0.3-claim-verification",
                new ClaimEntity("class", "http://example.org/v0.3#Dog"),
                "subClassOf",
                new ClaimEntity("class", "http://example.org/v0.3#Animal"),
                Optional.of("HermiT"),
                Optional.of(GraphScope.EXPLICIT),
                Optional.of(Map.of("includeEvidence", true, "maxEvidence", 10))
            );

            String json = gson.toJson(claim);
            assertNotNull(json);
            assertTrue(json.contains("\"claimId\":\"test-claim\""));
            assertTrue(json.contains("\"reasoner\":\"HermiT\""));
        }

        @Test
        @DisplayName("Claim with empty Optional fields serializes as null")
        void claimEmptyOptionalFieldsSerializeAsNull() {
            Claim claim = new Claim(
                "test-claim",
                ClaimType.ONTOLOGY_CONSISTENCY,
                "v0.3-claim-verification",
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );

            String json = gson.toJson(claim);
            assertNotNull(json);
            assertTrue(json.contains("\"reasoner\":null"));
        }
    }

    @Nested
    @DisplayName("UnknownExplanation serialization")
    class UnknownExplanationTests {

        @Test
        @DisplayName("UnknownExplanation with Optional fields serializes")
        void unknownExplanationSerializes() {
            UnknownExplanation explanation = new UnknownExplanation(
                "claim-unknown-001",
                "v0.3-claim-verification",
                UnknownReason.INSUFFICIENT_AXIOMS,
                Optional.of("No entailment path exists"),
                List.of("http://example.org/v0.3#Goldfish"),
                Optional.of("Add Fish as a superclass of Goldfish")
            );

            String json = gson.toJson(explanation);
            assertNotNull(json);
            assertTrue(json.contains("\"reason\":\"insufficient_axioms\""));
            assertTrue(json.contains("\"explanation\":\"No entailment path exists\""));
            assertTrue(json.contains("\"suggestedAction\":\"Add Fish as a superclass of Goldfish\""));
        }
    }

    @Nested
    @DisplayName("MissingEntityResult serialization")
    class MissingEntityResultTests {

        @Test
        @DisplayName("MissingEntityResult with Optional fields in EntityMatch serializes")
        void missingEntityResultSerializes() {
            MissingEntityResult result = new MissingEntityResult(
                "v0.3-claim-verification",
                List.of(new MissingEntityResult.EntityMatch(
                    "http://example.org/v0.3#Dog",
                    Optional.of("http://example.org/v0.3#Dog"),
                    Optional.of("class"),
                    Optional.of("Dog")
                )),
                List.of(),
                List.of(),
                List.of()
            );

            String json = gson.toJson(result);
            assertNotNull(json);
            assertTrue(json.contains("\"matchedIRI\":\"http://example.org/v0.3#Dog\""));
            assertTrue(json.contains("\"kind\":\"class\""));
        }
    }

    @Nested
    @DisplayName("EvidencePath serialization")
    class EvidencePathTests {

        @Test
        @DisplayName("EvidencePath with Optional fields serializes")
        void evidencePathSerializes() {
            EvidencePath path = new EvidencePath(
                "claim-supported-001",
                "v0.3-claim-verification",
                List.of(new EvidenceItem(
                    "entailment-1",
                    EvidenceItem.ROLE_SUPPORTING,
                    EvidenceKind.INFERRED_AXIOM,
                    "SubClassOf: Dog → Animal",
                    "inferred",
                    "HermiT",
                    "explicit",
                    List.of("Dog", "Animal"),
                    EvidenceItem.CONFIDENCE_ENTAILED
                )),
                Optional.of("HermiT"),
                Optional.of(GraphScope.EXPLICIT),
                false,
                1
            );

            String json = gson.toJson(path);
            assertNotNull(json);
            assertTrue(json.contains("\"reasonerName\":\"HermiT\""));
            assertTrue(json.contains("\"graphScope\":\"explicit\""));
        }
    }

    @Nested
    @DisplayName("GsonFactory configuration")
    class GsonFactoryTests {

        @Test
        @DisplayName("GsonFactory creates a Gson instance that handles Optional")
        void gsonFactoryCreatesOptionalAwareGson() {
            com.google.gson.Gson g = GsonFactory.createGson();
            assertNotNull(g);

            // Verify it can serialize Optional fields without exception
            ClaimVerificationResult result = new ClaimVerificationResult(
                "test", "test", ClaimType.SUBCLASS, Verdict.SUPPORTED,
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, 0
            );
            String json = g.toJson(result);
            assertNotNull(json);
        }

        @Test
        @DisplayName("GsonFactory serializeNulls is enabled")
        void gsonFactorySerializeNullsEnabled() {
            ClaimVerificationResult result = new ClaimVerificationResult(
                "test", "test", ClaimType.SUBCLASS, Verdict.SUPPORTED,
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, 0
            );
            String json = GsonFactory.createGson().toJson(result);
            // With serializeNulls, empty Optional should be "null" not omitted
            assertTrue(json.contains("\"unknownReason\":null"));
        }

        @Test
        @DisplayName("GsonFactory disableHtmlEscaping is enabled")
        void gsonFactoryDisableHtmlEscapingEnabled() {
            ClaimVerificationResult result = new ClaimVerificationResult(
                "test", "test", ClaimType.SUBCLASS, Verdict.SUPPORTED,
                List.of(new EvidenceItem(
                    "ev-1", "supporting", EvidenceKind.INFERRED_AXIOM,
                    "http://example.org/v0.3#Dog&Cat", "inferred", "HermiT", "explicit",
                    List.of(), "entailed"
                )),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, 1
            );
            String json = GsonFactory.createGson().toJson(result);
            // With disableHtmlEscaping, '&' should not be encoded as &
            assertTrue(json.contains("&"));
            assertFalse(json.contains("\\u0026"));
        }
    }
}