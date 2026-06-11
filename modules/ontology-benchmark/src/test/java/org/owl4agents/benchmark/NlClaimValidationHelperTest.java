package org.owl4agents.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4.3 tests: NlClaimValidationHelper validates schema conformance
 * of NL-to-claim decomposition output without executing verification.
 */
class NlClaimValidationHelperTest {

    private NlClaimValidationHelper helper;

    @BeforeEach
    void setUp() {
        helper = new NlClaimValidationHelper();
    }

    private static final String VALID_CLAIMS = """
        [{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]
        """.strip();

    @Nested
    @DisplayName("Valid decomposition passes validation")
    class ValidDecomposition {

        @Test
        @DisplayName("Valid claim decomposition passes with no errors")
        void validClaimsPass() {
            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(VALID_CLAIMS);

            assertTrue(result.isValid());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("Valid claims wrapped in object pass")
        void validClaimsWrapped() {
            String wrapped = """
                {"claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]}
                """.strip();

            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(wrapped);

            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Missing field → INVALID_CLAIM_SCHEMA")
    class MissingFields {

        @Test
        @DisplayName("Missing type → INVALID_CLAIM_SCHEMA")
        void missingType() {
            String claims = """
                [{"id":"c1","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]
                """.strip();

            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(claims);

            assertFalse(result.isValid());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("type")));
        }

        @Test
        @DisplayName("Missing subject.iri → INVALID_CLAIM_SCHEMA")
        void missingSubjectIri() {
            String claims = """
                [{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]
                """.strip();

            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(claims);

            assertFalse(result.isValid());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("subject.iri")));
        }

        @Test
        @DisplayName("Invalid claim type → error")
        void invalidClaimType() {
            String claims = """
                [{"id":"c1","type":"invalid_type","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]
                """.strip();

            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(claims);

            assertFalse(result.isValid());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("Invalid claim type")));
        }
    }

    @Nested
    @DisplayName("NL-only rejection")
    class NlOnlyRejection {

        @Test
        @DisplayName("Empty claims → rejected consistent with benchmark runner")
        void emptyClaims() {
            NlClaimValidationHelper.ValidationResult result = helper.validateClaims("[]");

            assertFalse(result.isValid());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.diagnostic().contains("Empty claims")));
        }

        @Test
        @DisplayName("Null input → rejected")
        void nullInput() {
            NlClaimValidationHelper.ValidationResult result = helper.validateClaims(null);

            assertFalse(result.isValid());
            assertEquals("INVALID_CLAIM_SCHEMA", result.errors().get(0).code());
        }

        @Test
        @DisplayName("Blank input → rejected")
        void blankInput() {
            NlClaimValidationHelper.ValidationResult result = helper.validateClaims("  ");

            assertFalse(result.isValid());
            assertEquals("INVALID_CLAIM_SCHEMA", result.errors().get(0).code());
        }
    }
}