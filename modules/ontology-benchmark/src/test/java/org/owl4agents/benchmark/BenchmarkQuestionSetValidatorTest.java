package org.owl4agents.benchmark;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.owl4agents.core.model.Verdict;

/**
 * Task 3.5 tests: BenchmarkQuestionSetValidator two-tier validation,
 * NL-only rejection, and structural schema validation.
 */
class BenchmarkQuestionSetValidatorTest {

    private BenchmarkQuestionSetValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BenchmarkQuestionSetValidator();
    }

    private static final String VALID_LINE = """
        {"questionId":"test-001","source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved","edgeCase":false}
        """.strip();

    @Nested
    @DisplayName("Valid line parsing")
    class ValidLineParsing {

        @Test
        @DisplayName("Valid JSONL line parses successfully")
        void validLineParses() {
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(VALID_LINE);

            assertFalse(result.isBlocked());
            assertTrue(result.errors().isEmpty());
            assertNotNull(result.question());
            assertEquals("test-001", result.question().questionId());
            assertEquals(Verdict.SUPPORTED, result.question().expectedVerdict());
            assertEquals("approved", result.reviewStatus());
        }

        @Test
        @DisplayName("Valid line with all fields parses correctly")
        void validLineWithAllFields() {
            String line = """
                {"questionId":"test-002","source":"owl4agents","sourceYear":"2026","domain":"food","ontologyIds":["pizza"],"question":"Is A a subclass of B?","answerType":"yesno","expectedAnswer":"yes","expectedVerdict":"contradicted","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved","edgeCase":false,"options":{"reasoner":"hermit","requireReasoning":true}}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertFalse(result.isBlocked());
            assertEquals("food", result.question().domain());
            assertEquals(List.of("pizza"), result.question().ontologyIds());
            assertEquals(Verdict.CONTRADICTED, result.question().expectedVerdict());
            assertFalse(result.question().edgeCase());
        }
    }

    @Nested
    @DisplayName("Missing required field → INVALID_QUESTION_SET")
    class MissingRequiredFields {

        @Test
        @DisplayName("Missing questionId → INVALID_QUESTION_SET")
        void missingQuestionId() {
            String line = """
                {"source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_QUESTION_SET") && e.diagnostic().contains("questionId")));
        }

        @Test
        @DisplayName("Missing expectedVerdict → INVALID_QUESTION_SET")
        void missingExpectedVerdict() {
            String line = """
                {"questionId":"test-003","source":"owl4agents","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_QUESTION_SET") && e.diagnostic().contains("expectedVerdict")));
        }

        @Test
        @DisplayName("Missing claims → INVALID_QUESTION_SET")
        void missingClaims() {
            String line = """
                {"questionId":"test-004","source":"owl4agents","expectedVerdict":"supported","reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_QUESTION_SET") && e.diagnostic().contains("claims")));
        }
    }

    @Nested
    @DisplayName("NL-only rejection → INVALID_QUESTION_SET")
    class NlOnlyRejection {

        @Test
        @DisplayName("Empty claims array → INVALID_QUESTION_SET")
        void emptyClaimsArray() {
            String line = """
                {"questionId":"test-005","source":"owl4agents","expectedVerdict":"supported","claims":[],"reviewStatus":"approved","edgeCase":false}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_QUESTION_SET") && e.diagnostic().contains("Empty claims")));
        }
    }

    @Nested
    @DisplayName("Missing claim schema fields → INVALID_CLAIM_SCHEMA")
    class MissingClaimSchemaFields {

        @Test
        @DisplayName("Missing subject.iri → INVALID_CLAIM_SCHEMA")
        void missingSubjectIri() {
            String line = """
                {"questionId":"test-006","source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("subject.iri")));
        }

        @Test
        @DisplayName("Missing object.iri → INVALID_CLAIM_SCHEMA")
        void missingObjectIri() {
            String line = """
                {"questionId":"test-007","source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class"}}],"reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("object.iri")));
        }

        @Test
        @DisplayName("Missing type → INVALID_CLAIM_SCHEMA")
        void missingClaimType() {
            String line = """
                {"questionId":"test-008","source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved"}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream().anyMatch(e ->
                e.code().equals("INVALID_CLAIM_SCHEMA") && e.diagnostic().contains("type")));
        }
    }

    @Nested
    @DisplayName("Edge case flag")
    class EdgeCaseFlag {

        @Test
        @DisplayName("edgeCase: true is parsed correctly")
        void edgeCaseTrue() {
            String line = """
                {"questionId":"test-009","source":"owl4agents","expectedVerdict":"unknown","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved","edgeCase":true}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertFalse(result.isBlocked());
            assertTrue(result.question().edgeCase());
        }

        @Test
        @DisplayName("verdictConfidence does not appear in test data")
        void verdictConfidenceNotUsed() {
            // Per spec: verdictConfidence SHALL NOT appear in test data;
            // edgeCase: true is the sole mechanism for marking ambiguous questions.
            // This test verifies the validator does not require verdictConfidence.
            String line = """
                {"questionId":"test-010","source":"owl4agents","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}],"reviewStatus":"approved","edgeCase":false}
                """.strip();

            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(line);

            assertFalse(result.isBlocked());
            assertFalse(result.question().edgeCase());
        }
    }

    @Nested
    @DisplayName("Invalid input handling")
    class InvalidInput {

        @Test
        @DisplayName("Empty line → INVALID_QUESTION_SET")
        void emptyLine() {
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine("");

            assertTrue(result.isBlocked());
            assertEquals("INVALID_QUESTION_SET", result.errors().get(0).code());
        }

        @Test
        @DisplayName("Null line → INVALID_QUESTION_SET")
        void nullLine() {
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(null);

            assertTrue(result.isBlocked());
            assertEquals("INVALID_QUESTION_SET", result.errors().get(0).code());
        }

        @Test
        @DisplayName("Invalid JSON → INVALID_QUESTION_SET")
        void invalidJson() {
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine("not json");

            assertTrue(result.isBlocked());
            assertEquals("INVALID_QUESTION_SET", result.errors().get(0).code());
        }
    }
}