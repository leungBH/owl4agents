package org.owl4agents.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 6.7 / task 6.7: verify that the
 * {@link ExperimentConfigParser#parse(String, String)} overload applies
 * the {@code questionSetPathOverride} BEFORE the missing-field check and
 * that the returned {@link ExperimentConfig#questionSetPath()} reflects
 * the override (not the YAML value).
 *
 * <p>The bug (V085_BUG_REPORT.md §6) was that the parser only had a
 * 1-arg {@code parse(String configPath)} method, so the adapter could
 * not pass an override. The fix introduces the 2-arg overload and
 * applies the override at a single injection point (before the
 * missing-field check) so a YAML missing {@code questionSetPath} is
 * acceptable when an override is supplied.</p>
 *
 * <p>Tests are unit-level (no MCP adapter or ontology loading required)
 * because the parser is a standalone class.</p>
 */
@DisplayName("v0.8.6 §6.7: ExperimentConfigParser override wins over YAML questionSetPath")
class ExperimentConfigParserOverrideTest {

    private ExperimentConfigParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new ExperimentConfigParser();
    }

    private String writeYaml(String content) throws Exception {
        Path file = tempDir.resolve("test-config-" + System.nanoTime() + ".yaml");
        Files.writeString(file, content);
        return file.toString();
    }

    private String writeJsonl(String... lines) throws Exception {
        Path file = tempDir.resolve("test-questions-" + System.nanoTime() + ".jsonl");
        Files.writeString(file, String.join("\n", lines));
        return file.toString();
    }

    private static final String VALID_JSONL_LINE =
        "{\"questionId\":\"test-001\",\"source\":\"owl4agents\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://example.org/A\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://example.org/B\"}}],\"reviewStatus\":\"approved\"}";

    private String validConfigYaml(String questionSetPath) {
        return """
            name: override-test
            description: Test config for override behavior
            ontologyIds:
              - pizza
            questionSetPath: %s
            reasoners:
              - hermit
            outputPath: results/test-output.jsonl
            """.formatted(questionSetPath);
    }

    @Nested
    @DisplayName("Override wins over YAML questionSetPath")
    class OverrideWinsTests {

        @Test
        @DisplayName("override replaces YAML value in returned config")
        void overrideReplacesYamlValue() throws Exception {
            // YAML has one path
            String yamlQsPath = writeJsonl(VALID_JSONL_LINE);
            // Override has a different path
            String overrideQsPath = writeJsonl(VALID_JSONL_LINE);

            String configPath = writeYaml(validConfigYaml(yamlQsPath));

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, overrideQsPath);

            assertTrue(result.isSuccess(),
                "Parse should succeed with override. Got: " + result.error());
            assertEquals(overrideQsPath, result.config().questionSetPath(),
                "Override path must replace YAML value in returned config");
            assertNotEquals(yamlQsPath, result.config().questionSetPath(),
                "YAML path must NOT be used when override is supplied");
        }

        @Test
        @DisplayName("override path is validated for format (first-line JSON check)")
        void overridePathIsValidated() throws Exception {
            // YAML has valid path, override has INVALID first line.
            String yamlQsPath = writeJsonl(VALID_JSONL_LINE);
            String overrideQsPath = writeJsonl("this is not valid JSON");

            String configPath = writeYaml(validConfigYaml(yamlQsPath));

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, overrideQsPath);

            // The override is applied BEFORE the format check, so the format
            // check runs on the override path and fails (first line is not JSON).
            assertFalse(result.isSuccess(),
                "Parse should fail when override has invalid JSON first line. " +
                "Got: " + result);
            assertNotNull(result.error());
            // The error should be INVALID_EXPERIMENT_CONFIG (format check failure)
            // NOT QUESTION_SET_NOT_FOUND (the override file exists).
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code(),
                "Format check failure should be INVALID_EXPERIMENT_CONFIG. " +
                "Got: " + result.error().code());
        }

        @Test
        @DisplayName("override with nonexistent path -> QUESTION_SET_NOT_FOUND")
        void overrideNonexistentPathReturnsQuestionSetNotFound() throws Exception {
            String yamlQsPath = writeJsonl(VALID_JSONL_LINE);
            String configPath = writeYaml(validConfigYaml(yamlQsPath));

            // Override points to a file that doesn't exist
            String nonexistentOverride = tempDir.resolve("does-not-exist.jsonl").toString();

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, nonexistentOverride);

            assertFalse(result.isSuccess(),
                "Parse should fail when override path does not exist. " +
                "Got: " + result);
            assertNotNull(result.error());
            // The override path doesn't exist -> QUESTION_SET_NOT_FOUND
            // (proves the override was applied BEFORE the existence check)
            assertEquals("QUESTION_SET_NOT_FOUND", result.error().code(),
                "Missing override path should return QUESTION_SET_NOT_FOUND. " +
                "Got: " + result.error().code());
        }
    }

    @Nested
    @DisplayName("Override is applied before missing-field check")
    class OverrideBeforeMissingFieldCheckTests {

        @Test
        @DisplayName("override supplies missing questionSetPath -> parse succeeds")
        void overrideSuppliesMissingQuestionSetPath() throws Exception {
            // YAML has NO questionSetPath field
            String yamlNoQs = """
                name: no-qs-test
                description: Config without questionSetPath
                ontologyIds:
                  - pizza
                reasoners:
                  - hermit
                outputPath: results/test-output.jsonl
                """;
            String configPath = writeYaml(yamlNoQs);

            // Override supplies the path
            String overrideQsPath = writeJsonl(VALID_JSONL_LINE);

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, overrideQsPath);

            // Per D8 design: override is applied BEFORE the missing-field
            // check, so a missing YAML questionSetPath is acceptable when
            // an override is supplied.
            assertTrue(result.isSuccess(),
                "Missing YAML questionSetPath should be acceptable when override is supplied. " +
                "Got: " + (result.error() != null ? result.error().code() + " " + result.error().diagnostic() : ""));
            assertEquals(overrideQsPath, result.config().questionSetPath(),
                "Override path should be in returned config");
        }

        @Test
        @DisplayName("no override + missing questionSetPath -> INVALID_EXPERIMENT_CONFIG")
        void noOverrideMissingQuestionSetPathFails() throws Exception {
            String yamlNoQs = """
                name: no-qs-test
                description: Config without questionSetPath
                ontologyIds:
                  - pizza
                reasoners:
                  - hermit
                outputPath: results/test-output.jsonl
                """;
            String configPath = writeYaml(yamlNoQs);

            // No override
            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, null);

            // Without override, missing questionSetPath must fail with
            // INVALID_EXPERIMENT_CONFIG (the existing behavior).
            assertFalse(result.isSuccess(),
                "Missing questionSetPath without override should fail. Got: " + result);
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code(),
                "Missing field should be INVALID_EXPERIMENT_CONFIG. Got: " + result.error().code());
        }
    }

    @Nested
    @DisplayName("Backward compatibility: 1-arg parse delegates to 2-arg with null override")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("1-arg parse delegates to 2-arg with null override (YAML value used)")
        void oneArgParseDelegatesToTwoArgWithNull() throws Exception {
            String yamlQsPath = writeJsonl(VALID_JSONL_LINE);
            String configPath = writeYaml(validConfigYaml(yamlQsPath));

            // Call the deprecated 1-arg overload
            @SuppressWarnings("deprecation")
            ExperimentConfigParser.ParseResult result = parser.parse(configPath);

            assertTrue(result.isSuccess(),
                "1-arg parse should succeed when YAML has valid questionSetPath. " +
                "Got: " + result.error());
            assertEquals(yamlQsPath, result.config().questionSetPath(),
                "1-arg parse should use YAML questionSetPath (null override)");
        }

        @Test
        @DisplayName("1-arg parse is @Deprecated")
        void oneArgParseIsDeprecated() throws Exception {
            // Verify the @Deprecated annotation is present (compile-time contract).
            // This is a documentation test — the annotation ensures callers see
            // a deprecation warning in their IDE.
            boolean isDeprecated = false;
            for (java.lang.reflect.Method m : ExperimentConfigParser.class.getMethods()) {
                if (m.getName().equals("parse") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                    if (m.isAnnotationPresent(Deprecated.class)) {
                        isDeprecated = true;
                        break;
                    }
                }
            }
            assertTrue(isDeprecated,
                "1-arg parse(String) must be @Deprecated per D8 design");
        }
    }

    @Nested
    @DisplayName("Override=null preserves existing behavior (YAML value used)")
    class NullOverrideTests {

        @Test
        @DisplayName("override=null uses YAML questionSetPath")
        void nullOverrideUsesYamlValue() throws Exception {
            String yamlQsPath = writeJsonl(VALID_JSONL_LINE);
            String configPath = writeYaml(validConfigYaml(yamlQsPath));

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, null);

            assertTrue(result.isSuccess());
            assertEquals(yamlQsPath, result.config().questionSetPath(),
                "Null override should preserve YAML questionSetPath");
        }

        @Test
        @DisplayName("override=null + missing YAML questionSetPath -> INVALID_EXPERIMENT_CONFIG")
        void nullOverrideMissingYamlFails() throws Exception {
            String yamlNoQs = """
                name: no-qs-test
                description: Config without questionSetPath
                ontologyIds:
                  - pizza
                reasoners:
                  - hermit
                outputPath: results/test-output.jsonl
                """;
            String configPath = writeYaml(yamlNoQs);

            ExperimentConfigParser.ParseResult result =
                parser.parse(configPath, null);

            assertFalse(result.isSuccess());
            assertEquals("INVALID_EXPERIMENT_CONFIG", result.error().code());
        }
    }
}
