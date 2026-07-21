package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 6.6 / task 6.6: verify that {@code executeBenchmarkRun}
 * preserves the parser's structured error code (e.g.
 * {@code QUESTION_SET_NOT_FOUND}) instead of always wrapping as
 * {@code INVALID_EXPERIMENT_CONFIG}.
 *
 * <p>The bug (V085_BUG_REPORT.md §6) was that the adapter wrapped every
 * parser error as {@code INVALID_EXPERIMENT_CONFIG}, hiding the actual
 * root cause (e.g. {@code QUESTION_SET_NOT_FOUND} when the question set
 * file is missing). The fix uses
 * {@link org.owl4agents.core.ErrorCode#fromCode(String)} to map the
 * parser's string code back to the typed enum, falling back to
 * {@code INVALID_EXPERIMENT_CONFIG} only when no match is found.</p>
 *
 * <p>Tests are unit-level (no ontology loading required) because the
 * parser fails before the benchmark service runs.</p>
 */
@DisplayName("v0.8.6 §6.6: executeBenchmarkRun preserves parser error code")
class BenchmarkRunErrorCodeTransparencyTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("bench-error-code-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    private String validConfigYamlExceptQsPath(String questionSetPath) {
        return """
            name: error-code-test
            description: Tests error code transparency
            ontologyIds:
              - pizza
            questionSetPath: %s
            reasoners:
              - hermit
            outputPath: %s
            """.formatted(questionSetPath,
                tempDir.resolve("results.jsonl").toString());
    }

    @Nested
    @DisplayName("QUESTION_SET_NOT_FOUND is preserved (not wrapped as INVALID_EXPERIMENT_CONFIG)")
    class QuestionSetNotFoundTests {

        @Test
        @DisplayName("missing question set file -> error.code == QUESTION_SET_NOT_FOUND")
        void missingQuestionSetFileReturnsQuestionSetNotFound() {
            McpServerAdapter adapter = createAdapter();

            // YAML config points to a nonexistent question set file.
            // No question_set_content override is supplied.
            String configYaml = validConfigYamlExceptQsPath(
                "/nonexistent/path/questions.jsonl");

            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", configYaml);
            // Note: NO question_set_content -> override is null -> parser
            // uses the YAML path -> QUESTION_SET_NOT_FOUND.

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            assertTrue(result.containsKey("error"),
                "Missing question set file must return an error. Got: " + result);
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("QUESTION_SET_NOT_FOUND",
                String.valueOf(error.get("code")),
                "Error code must be QUESTION_SET_NOT_FOUND (not INVALID_EXPERIMENT_CONFIG). " +
                "Got: " + result);
        }

        @Test
        @DisplayName("missing question set file -> error.message mentions the path")
        void missingQuestionSetFileMessageMentionsPath() {
            McpServerAdapter adapter = createAdapter();

            String bogusPath = "/nonexistent/path/questions.jsonl";
            String configYaml = validConfigYamlExceptQsPath(bogusPath);

            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", configYaml);

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            String message = String.valueOf(error.get("message"));
            // The error message should mention the missing path OR the
            // questionSetPath field OR "Question set" (any of these is
            // acceptable proof that the diagnostic was preserved).
            assertTrue(
                message.contains(bogusPath) ||
                message.toLowerCase().contains("questions") ||
                message.toLowerCase().contains("questionsetpath"),
                "Error message should mention the missing path or question set. Got: " + message);
        }
    }

    @Nested
    @DisplayName("Invalid YAML errors are preserved as INVALID_EXPERIMENT_CONFIG")
    class InvalidExperimentConfigTests {

        @Test
        @DisplayName("malformed YAML -> error.code == INVALID_EXPERIMENT_CONFIG")
        void malformedYamlReturnsInvalidExperimentConfig() {
            McpServerAdapter adapter = createAdapter();

            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", "name: {broken mapping without closing");

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            // Malformed YAML is genuinely an INVALID_EXPERIMENT_CONFIG — the
            // transparency fix preserves the parser's code; this case really
            // is INVALID_EXPERIMENT_CONFIG.
            assertEquals("INVALID_EXPERIMENT_CONFIG",
                String.valueOf(error.get("code")),
                "Malformed YAML should still be INVALID_EXPERIMENT_CONFIG. Got: " + result);
        }

        @Test
        @DisplayName("missing required field 'name' -> INVALID_EXPERIMENT_CONFIG with field message")
        void missingNameReturnsInvalidExperimentConfig() throws Exception {
            // The adapter treats inline YAML starting with "name:" as inline
            // content; YAML missing the name field cannot start with "name:",
            // so we write it to a temp file and pass the file path instead.
            java.nio.file.Path tempConfig = tempDir.resolve("missing-name-config.yaml");
            java.nio.file.Files.writeString(tempConfig, """
                description: Missing name field
                ontologyIds:
                  - pizza
                questionSetPath: /nonexistent/questions.jsonl
                reasoners:
                  - hermit
                outputPath: %s
                """.formatted(tempDir.resolve("results.jsonl").toString()));

            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", tempConfig.toString());

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            // Missing field is genuinely INVALID_EXPERIMENT_CONFIG, but we
            // should see the field name in the message (proves the parser's
            // diagnostic was preserved).
            assertEquals("INVALID_EXPERIMENT_CONFIG",
                String.valueOf(error.get("code")));
            String message = String.valueOf(error.get("message"));
            assertTrue(message.contains("name"),
                "Error message should mention the missing 'name' field. Got: " + message);
        }
    }

    @Nested
    @DisplayName("Parser ConfigError.code is mapped via ErrorCode.fromCode (case-insensitive)")
    class ErrorCodeMappingTests {

        @Test
        @DisplayName("parser code 'QUESTION_SET_NOT_FOUND' -> ErrorCode.QUESTION_SET_NOT_FOUND")
        void parserCodeMapsToTypedEnum() {
            // Direct test on ErrorCode.fromCode — proves the lookup used by
            // executeBenchmarkRun works as documented (case-insensitive).
            java.util.Optional<org.owl4agents.core.ErrorCode> ec =
                org.owl4agents.core.ErrorCode.fromCode("QUESTION_SET_NOT_FOUND");
            assertTrue(ec.isPresent(),
                "ErrorCode.fromCode must find QUESTION_SET_NOT_FOUND");
            assertEquals(org.owl4agents.core.ErrorCode.QUESTION_SET_NOT_FOUND, ec.get());

            // Case-insensitive lookup
            java.util.Optional<org.owl4agents.core.ErrorCode> ecLower =
                org.owl4agents.core.ErrorCode.fromCode("question_set_not_found");
            assertTrue(ecLower.isPresent(),
                "ErrorCode.fromCode must be case-insensitive");
            assertEquals(org.owl4agents.core.ErrorCode.QUESTION_SET_NOT_FOUND, ecLower.get());
        }

        @Test
        @DisplayName("unknown parser code -> fallback to INVALID_EXPERIMENT_CONFIG")
        void unknownParserCodeFallsBackToInvalidExperimentConfig() {
            java.util.Optional<org.owl4agents.core.ErrorCode> ec =
                org.owl4agents.core.ErrorCode.fromCode("UNKNOWN_CODE_DOES_NOT_EXIST");
            assertTrue(ec.isEmpty(),
                "Unknown code should return empty Optional (caller falls back)");
        }

        @Test
        @DisplayName("null parser code -> empty Optional (no NPE)")
        void nullParserCodeReturnsEmpty() {
            java.util.Optional<org.owl4agents.core.ErrorCode> ec =
                org.owl4agents.core.ErrorCode.fromCode(null);
            assertTrue(ec.isEmpty(),
                "Null code should return empty Optional (no NPE)");
        }
    }
}
