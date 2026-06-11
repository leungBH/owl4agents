package org.owl4agents.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2.3 tests: ExperimentConfigParser validates required fields,
 * applies defaults, rejects repeatCount, and validates optional field types.
 */
class ExperimentConfigParserTest {

    private ExperimentConfigParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new ExperimentConfigParser();
    }

    /** Helper: write a YAML string to a temp file and return its path. */
    private String writeYaml(String content) throws Exception {
        Path file = tempDir.resolve("test-config.yaml");
        Files.writeString(file, content);
        return file.toString();
    }

    /** Helper: write a minimal valid JSONL line to a temp file. */
    private String writeJsonl(String... lines) throws Exception {
        Path file = tempDir.resolve("test-questions.jsonl");
        Files.writeString(file, String.join("\n", lines));
        return file.toString();
    }

    private static final String VALID_JSONL_LINE =
        "{\"questionId\":\"test-001\",\"source\":\"owl4agents\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://example.org/A\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://example.org/B\"}}],\"reviewStatus\":\"approved\"}";

    private String validConfigYaml(String questionSetPath) {
        return """
            name: test-config
            description: Test config for unit testing
            ontologyIds:
              - pizza
            questionSetPath: %s
            reasoners:
              - hermit
            outputPath: results/test-output.jsonl
            """.formatted(questionSetPath);
    }

    @Nested
    @DisplayName("Valid config parsing")
    class ValidConfigParsing {

        @Test
        @DisplayName("Valid config with defaults parses successfully")
        void validConfigWithDefaults() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String configPath = writeYaml(validConfigYaml(qsPath));

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertTrue(result.isSuccess());
            assertNotNull(result.config());
            assertEquals("test-config", result.config().name());
            assertEquals("Test config for unit testing", result.config().description());
            assertEquals(List.of("pizza"), result.config().ontologyIds());
            assertEquals(List.of("hermit"), result.config().reasoners());
            // Defaults applied
            assertEquals(30, result.config().timeoutPerQuestion());
            assertFalse(result.config().hallucinationDetection());
            assertEquals(ExperimentConfig.EdgeCasePolicy.exclude, result.config().edgeCasePolicy());
            assertEquals(ExperimentConfig.ReportFormat.markdown, result.config().reportFormat());
            assertEquals(Optional.empty(), result.config().reportOutputPath());
            assertEquals(OptionalInt.empty(), result.config().maxContextTokens());
        }

        @Test
        @DisplayName("Valid config with all optional fields parses correctly")
        void validConfigWithAllOptions() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                name: full-config
                description: Config with all optional fields
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                  - elk
                outputPath: results/full-output.jsonl
                timeoutPerQuestion: 60
                hallucinationDetection: true
                edgeCasePolicy: include
                maxContextTokens: 8000
                reportFormat: json
                reportOutputPath: reports/full-report.json
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertTrue(result.isSuccess());
            assertEquals(60, result.config().timeoutPerQuestion());
            assertTrue(result.config().hallucinationDetection());
            assertEquals(ExperimentConfig.EdgeCasePolicy.include, result.config().edgeCasePolicy());
            assertEquals(OptionalInt.of(8000), result.config().maxContextTokens());
            assertEquals(ExperimentConfig.ReportFormat.json, result.config().reportFormat());
            assertEquals(Optional.of("reports/full-report.json"), result.config().reportOutputPath());
            assertEquals(List.of("hermit", "elk"), result.config().reasoners());
        }
    }

    @Nested
    @DisplayName("Missing required field → INVALID_EXPERIMENT_CONFIG")
    class MissingRequiredFields {

        @Test
        @DisplayName("Missing name → INVALID_EXPERIMENT_CONFIG")
        void missingName() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                description: Missing name
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("name"));
        }

        @Test
        @DisplayName("Missing description → INVALID_EXPERIMENT_CONFIG")
        void missingDescription() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                name: no-desc
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("description"));
        }

        @Test
        @DisplayName("Missing questionSetPath → INVALID_EXPERIMENT_CONFIG")
        void missingQuestionSetPath() throws Exception {
            String yaml = """
                name: no-qspath
                description: Missing questionSetPath
                ontologyIds:
                  - pizza
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                """;
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("questionSetPath"));
        }
    }

    @Nested
    @DisplayName("Malformed YAML → parse error")
    class MalformedYaml {

        @Test
        @DisplayName("Malformed YAML → INVALID_EXPERIMENT_CONFIG")
        void malformedYaml() throws Exception {
            // Use truly malformed YAML: unbalanced flow mapping with missing colon
            String configPath = writeYaml("name: {broken mapping without closing brace");

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
        }
    }

    @Nested
    @DisplayName("repeatCount → INVALID_EXPERIMENT_CONFIG")
    class RepeatCountRejection {

        @Test
        @DisplayName("Config with repeatCount → INVALID_EXPERIMENT_CONFIG with diagnostic")
        void rejectRepeatCount() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                name: with-repeat
                description: Should be rejected
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                repeatCount: 5
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("repeatCount is not supported"));
            assertTrue(result.error().diagnostic().contains("repeatCount is not supported"));
        }
    }

    @Nested
    @DisplayName("hallucinationDetection type validation")
    class HallucinationDetectionValidation {

        @Test
        @DisplayName("hallucinationDetection as string → INVALID_EXPERIMENT_CONFIG")
        void nonBooleanHallucinationDetection() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                name: bad-halluc
                description: Non-boolean hallucinationDetection
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                hallucinationDetection: "yes"
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("hallucinationDetection must be a boolean"));
        }
    }

    @Nested
    @DisplayName("edgeCasePolicy value validation")
    class EdgeCasePolicyValidation {

        @Test
        @DisplayName("Invalid edgeCasePolicy value → INVALID_EXPERIMENT_CONFIG")
        void invalidEdgeCasePolicy() throws Exception {
            String qsPath = writeJsonl(VALID_JSONL_LINE);
            String yaml = """
                name: bad-policy
                description: Invalid edgeCasePolicy
                ontologyIds:
                  - pizza
                questionSetPath: %s
                reasoners:
                  - hermit
                outputPath: results/out.jsonl
                edgeCasePolicy: report-only
                """.formatted(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("Invalid edgeCasePolicy"));
        }
    }

    @Nested
    @DisplayName("Config file not found")
    class ConfigNotFound {

        @Test
        @DisplayName("Nonexistent config file → CONFIG_NOT_FOUND")
        void configFileNotFound() {
            String nonexistent = tempDir.resolve("nonexistent.yaml").toString();

            ExperimentConfigParser.ParseResult result = parser.parse(nonexistent);

            assertFalse(result.isSuccess());
            assertEquals("CONFIG_NOT_FOUND", result.error().code());
        }
    }

    @Nested
    @DisplayName("Question set format validation")
    class QuestionSetFormatValidation {

        @Test
        @DisplayName("Nonexistent question set → QUESTION_SET_NOT_FOUND")
        void questionSetNotFound() throws Exception {
            String yaml = validConfigYaml(tempDir.resolve("nonexistent.jsonl").toString());
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("QUESTION_SET_NOT_FOUND", result.error().code());
        }

        @Test
        @DisplayName("Empty question set → INVALID_EXPERIMENT_CONFIG")
        void emptyQuestionSet() throws Exception {
            String qsPath = writeJsonl(); // empty
            String yaml = validConfigYaml(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("empty"));
        }

        @Test
        @DisplayName("Question set with invalid JSON first line → INVALID_EXPERIMENT_CONFIG")
        void invalidJsonFirstLine() throws Exception {
            String qsPath = writeJsonl("this is not json");
            String yaml = validConfigYaml(qsPath);
            String configPath = writeYaml(yaml);

            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
            assertTrue(result.error().message().contains("not valid JSON"));
        }
    }
}