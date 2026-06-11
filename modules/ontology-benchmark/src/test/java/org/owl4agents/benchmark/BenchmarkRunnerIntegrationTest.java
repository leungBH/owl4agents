package org.owl4agents.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5.4 integration tests: benchmark runner validation paths.
 *
 * V06-BR-003: NL-only line → INVALID_QUESTION_SET, subsequent lines continue
 * V06-BR-004: missing subject.iri → INVALID_CLAIM_SCHEMA, blocked
 *
 * Full ontology-dependent integration tests (V06-BR-001/002) require
 * workspace setup with imported ontologies and are deferred to
 * build-verification phase (Task 11).
 */
@DisplayName("Benchmark runner integration tests")
class BenchmarkRunnerIntegrationTest {

    @TempDir
    Path tempDir;

    // ── V06-BR-003: NL-only line handling ──

    @Nested
    @DisplayName("V06-BR-003: NL-only lines produce INVALID_QUESTION_SET, subsequent lines continue")
    class NlOnlyLineTests {

        @Test
        @DisplayName("NL-only line is rejected but subsequent valid lines continue processing")
        void nlOnlyRejectedButSubsequentContinue() throws Exception {
            // Create a question set with NL-only line followed by a valid line
            String nlOnlyLine = """
                {"questionId":"nl-001","question":"Is Margherita a pizza?","expectedVerdict":"supported","claims":[]}
                """.strip();

            String validLine = """
                {"questionId":"valid-001","question":"Is Margherita a subclass of Pizza?","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"}}],"ontologyIds":["pizza"],"edgeCase":false,"reviewStatus":"approved","source":"owl4agents","sourceLicense":"Apache-2.0"}
                """.strip();

            Path qsFile = tempDir.resolve("nl-mixed.jsonl");
            Files.writeString(qsFile, nlOnlyLine + "\n" + validLine + "\n");

            // Create a minimal config pointing to this question set
            String configYaml = """
                name: nl-only-test
                description: Test NL-only rejection
                ontologyIds: [pizza]
                questionSetPath: %s
                outputPath: %s
                reasoners: [hermit]
                """.formatted(qsFile.toString(), tempDir.resolve("results.jsonl").toString());

            Path configFile = tempDir.resolve("nl-only-config.yaml");
            Files.writeString(configFile, configYaml);

            // Parse config
            ExperimentConfigParser parser = new ExperimentConfigParser();
            ExperimentConfigParser.ParseResult parseResult = parser.parse(configFile.toString());
            assertTrue(parseResult.isSuccess(), "Config should parse successfully");

            ExperimentConfig config = parseResult.config();

            // Validate question set — NL-only line should be rejected
            BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();

            // Check NL-only line validation
            BenchmarkQuestionSetValidator.LineValidationResult nlResult = validator.validateLine(nlOnlyLine);
            assertTrue(nlResult.isBlocked(), "NL-only line should be blocked");
            assertTrue(nlResult.errors().stream()
                .anyMatch(e -> e.code().equals("INVALID_QUESTION_SET")),
                "NL-only line should produce INVALID_QUESTION_SET");

            // Check valid line validation
            BenchmarkQuestionSetValidator.LineValidationResult validResult = validator.validateLine(validLine);
            assertFalse(validResult.isBlocked(), "Valid line should not be blocked");
            assertNotNull(validResult.question(), "Valid line should produce a question");
            assertEquals("valid-001", validResult.question().questionId());
        }

        @Test
        @DisplayName("Empty claims array triggers INVALID_QUESTION_SET")
        void emptyClaimsArrayRejected() {
            String emptyClaimsLine = """
                {"questionId":"empty-001","expectedVerdict":"supported","claims":[]}
                """.strip();

            BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(emptyClaimsLine);

            assertTrue(result.isBlocked());
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.code().equals("INVALID_QUESTION_SET") &&
                    e.diagnostic().contains("Empty claims")));
        }
    }

    // ── V06-BR-004: missing subject.iri → INVALID_CLAIM_SCHEMA, blocked ──

    @Nested
    @DisplayName("V06-BR-004: missing subject.iri blocks execution with INVALID_CLAIM_SCHEMA")
    class MissingSubjectIriTests {

        @Test
        @DisplayName("Missing subject.iri produces INVALID_CLAIM_SCHEMA and blocks execution")
        void missingSubjectIriBlocksExecution() {
            String missingIriLine = """
                {"questionId":"miss-iri-001","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]}
                """.strip();

            BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(missingIriLine);

            assertTrue(result.isBlocked(), "Missing subject.iri should block execution");
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.code().equals("INVALID_CLAIM_SCHEMA")),
                "Missing subject.iri should produce INVALID_CLAIM_SCHEMA");
        }

        @Test
        @DisplayName("Missing object.iri produces INVALID_CLAIM_SCHEMA and blocks execution")
        void missingObjectIriBlocksExecution() {
            String missingObjIriLine = """
                {"questionId":"miss-obj-001","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class"}}]}
                """.strip();

            BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(missingObjIriLine);

            assertTrue(result.isBlocked(), "Missing object.iri should block execution");
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.code().equals("INVALID_CLAIM_SCHEMA")),
                "Missing object.iri should produce INVALID_CLAIM_SCHEMA");
        }

        @Test
        @DisplayName("Missing claim type produces INVALID_CLAIM_SCHEMA and blocks execution")
        void missingClaimTypeBlocksExecution() {
            String missingTypeLine = """
                {"questionId":"miss-type-001","expectedVerdict":"supported","claims":[{"id":"c1","required":true,"subject":{"kind":"class","iri":"http://example.org/A"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/B"}}]}
                """.strip();

            BenchmarkQuestionSetValidator validator = new BenchmarkQuestionSetValidator();
            BenchmarkQuestionSetValidator.LineValidationResult result = validator.validateLine(missingTypeLine);

            assertTrue(result.isBlocked(), "Missing claim type should block execution");
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.code().equals("INVALID_CLAIM_SCHEMA")),
                "Missing claim type should produce INVALID_CLAIM_SCHEMA");
        }
    }

    // ── Config error integration paths ──

    @Nested
    @DisplayName("Config parsing integration paths")
    class ConfigIntegrationTests {

        @Test
        @DisplayName("Missing required field produces INVALID_EXPERIMENT_CONFIG")
        void missingRequiredField() throws Exception {
            String invalidConfig = """
                name: incomplete
                description: Missing required fields
                ontologyIds: [pizza]
                """.strip();

            Path configFile = tempDir.resolve("missing-fields.yaml");
            Files.writeString(configFile, invalidConfig);

            ExperimentConfigParser parser = new ExperimentConfigParser();
            ExperimentConfigParser.ParseResult result = parser.parse(configFile.toString());

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
        }

        @Test
        @DisplayName("repeatCount produces INVALID_EXPERIMENT_CONFIG with diagnostic")
        void repeatCountRejected() throws Exception {
            String repeatConfig = """
                name: repeat-test
                description: Has repeatCount
                ontologyIds: [pizza]
                questionSetPath: /tmp/test.jsonl
                outputPath: /tmp/results.jsonl
                reasoners: [hermit]
                repeatCount: 3
                """.strip();

            Path configFile = tempDir.resolve("repeat-config.yaml");
            Files.writeString(configFile, repeatConfig);

            ExperimentConfigParser parser = new ExperimentConfigParser();
            ExperimentConfigParser.ParseResult result = parser.parse(configFile.toString());

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().diagnostic().contains("repeatCount"));
        }

        @Test
        @DisplayName("Malformed YAML produces INVALID_EXPERIMENT_CONFIG")
        void malformedYaml() throws Exception {
            String malformedYaml = "name: {broken mapping without closing brace";

            Path configFile = tempDir.resolve("malformed.yaml");
            Files.writeString(configFile, malformedYaml);

            ExperimentConfigParser parser = new ExperimentConfigParser();
            ExperimentConfigParser.ParseResult result = parser.parse(configFile.toString());

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
        }

        @Test
        @DisplayName("Nonexistent config file produces CONFIG_NOT_FOUND")
        void nonexistentConfigFile() {
            ExperimentConfigParser parser = new ExperimentConfigParser();
            ExperimentConfigParser.ParseResult result = parser.parse("/nonexistent/path.yaml");

            assertFalse(result.isSuccess());
            assertEquals("CONFIG_NOT_FOUND", result.error().code());
        }
    }
}