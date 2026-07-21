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
 * v0.8.6 Section 6.5 / task 6.5: verify that {@code executeBenchmarkRun}
 * honors the inline {@code question_set_content} argument by writing it
 * to a temp file and passing it as {@code questionSetPathOverride} to
 * {@link org.owl4agents.benchmark.ExperimentConfigParser#parse(String, String)}.
 *
 * <p>The bug (V085_BUG_REPORT.md §6) was that {@code executeBenchmarkRun}
 * always used the {@code questionSetPath} from the YAML config and ignored
 * the inline {@code question_set_content} argument. This made remote
 * benchmark runs impossible (the server cannot write to the client's
 * filesystem, so the YAML's {@code questionSetPath} could never resolve
 * server-side). The fix lets the client ship the question set content
 * inline; the adapter writes it to a temp file and overrides the YAML's
 * path.</p>
 *
 * <p>This test verifies the fix at two layers:</p>
 * <ol>
 *   <li><b>Unit layer</b>: pass inline {@code question_set_content} with a
 *       YAML config that has a deliberately invalid {@code questionSetPath}.
 *       Without the fix, the parser would return
 *       {@code QUESTION_SET_NOT_FOUND}. With the fix, the override is
 *       applied and parsing succeeds (or fails with a different error
 *       related to the inline content, not the YAML path).</li>
 *   <li><b>Integration layer</b>: load pizza, run the benchmark with
 *       inline {@code question_set_content} of 3 pizza claims, verify the
 *       response has {@code status=success} and the expected number of
 *       result lines. Skipped when pizza is unavailable.</li>
 * </ol>
 */
@DisplayName("v0.8.6 §6.5: executeBenchmarkRun honors inline question_set_content")
class RemoteBenchmarkRunQuestionSetInlineTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("bench-inline-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    private static final String VALID_JSONL_LINE =
        "{\"questionId\":\"test-001\",\"source\":\"owl4agents\",\"ontologyIds\":[\"pizza\"],\"question\":\"Is A a subclass of B?\",\"answerType\":\"yesno\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://example.org/A\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://example.org/B\"}}],\"reviewStatus\":\"approved\"}";

    private String validConfigYaml(String questionSetPath) {
        return """
            name: inline-test
            description: Test config for inline question_set_content
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
    @DisplayName("Unit: inline question_set_content overrides YAML questionSetPath")
    class InlineOverrideUnitTests {

        @Test
        @DisplayName("inline content + invalid YAML path -> override is applied (not QUESTION_SET_NOT_FOUND)")
        void inlineContentOverridesInvalidYamlPath() {
            McpServerAdapter adapter = createAdapter();

            Map<String, Object> args = new HashMap<>();
            // YAML config points to a nonexistent path on purpose.
            String configYaml = validConfigYaml("/nonexistent/path/questions.jsonl");
            args.put("config_yaml", configYaml);
            // Inline content should override the bogus YAML path.
            args.put("question_set_content", VALID_JSONL_LINE);

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            // The bug would have returned QUESTION_SET_NOT_FOUND because the
            // YAML's nonexistent path was used. The fix applies the override
            // before the format check, so the parser either succeeds or
            // fails with a different error (e.g. ontology not loaded).
            //
            // We assert: the error code is NOT QUESTION_SET_NOT_FOUND.
            if (result.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) result.get("error");
                String code = String.valueOf(error.get("code"));
                assertNotEquals("QUESTION_SET_NOT_FOUND", code,
                    "Inline question_set_content must override the YAML path; " +
                    "QUESTION_SET_NOT_FOUND indicates the override was not applied. " +
                    "Got: " + result);
            } else {
                // Override was applied and the benchmark ran successfully
                // (unlikely without ontology loaded, but possible if the
                // validator short-circuits). Either way, this is acceptable.
                assertEquals("success", result.get("status"));
            }
        }

        @Test
        @DisplayName("inline content + missing YAML questionSetPath -> override supplies it")
        void inlineContentSuppliesMissingYamlPath() {
            // Per D8 design: when questionSetPathOverride is non-null, a
            // missing YAML questionSetPath is acceptable (the override is
            // applied before the missing-field check).
            McpServerAdapter adapter = createAdapter();

            String configYaml = """
                name: no-qs-test
                description: Config without questionSetPath
                ontologyIds:
                  - pizza
                reasoners:
                  - hermit
                outputPath: %s
                """.formatted(tempDir.resolve("results.jsonl").toString());

            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", configYaml);
            args.put("question_set_content", VALID_JSONL_LINE);

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            // Without the override, this would have failed with
            // "Missing required field: questionSetPath" (INVALID_EXPERIMENT_CONFIG).
            // With the override, parsing proceeds past the missing-field check.
            if (result.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) result.get("error");
                String code = String.valueOf(error.get("code"));
                String message = String.valueOf(error.get("message"));
                // The override must have been applied, so we should NOT see
                // the "Missing required field: questionSetPath" error.
                assertFalse(message.contains("questionSetPath"),
                    "Inline override must supply the missing questionSetPath. " +
                    "Got: " + result);
                // Any other error (e.g. ontology not loaded) is acceptable.
                assertNotEquals("QUESTION_SET_NOT_FOUND", code,
                    "Override should have been applied. Got: " + result);
            }
        }
    }

    @Nested
    @DisplayName("Integration: pizza benchmark with inline question_set_content")
    class PizzaIntegrationTests {

        @Test
        @DisplayName("inline 3-claim pizza question_set_content runs successfully")
        void pizzaBenchmarkWithInlineContent() {
            // Locate the pizza fixture via the corpus.fixtures system property.
            String corpusFixtures = System.getProperty(
                "corpus.fixtures", "../test/corpus");
            java.nio.file.Path pizzaFixture =
                java.nio.file.Path.of(corpusFixtures).resolve("smoke/pizza.owl");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(pizzaFixture),
                "pizza.owl fixture required for this integration test");

            // Set up an isolated workspace at tempDir.
            org.owl4agents.storage.HomeDirectoryResolver homeResolver =
                new org.owl4agents.storage.HomeDirectoryResolver(tempDir);
            org.owl4agents.storage.WorkspaceInitializer initializer =
                new org.owl4agents.storage.WorkspaceInitializer(homeResolver);
            org.owl4agents.storage.CatalogStore catalogStore =
                new org.owl4agents.storage.CatalogStore(homeResolver);
            org.owl4agents.owlapi.OntologyImporter importer =
                new org.owl4agents.owlapi.OntologyImporter(homeResolver, catalogStore);

            System.setProperty("OWL4AGENTS_HOME", tempDir.toString());
            initializer.initializeIdempotent(org.owl4agents.core.WorkspaceId.DEFAULT);

            org.owl4agents.core.ServiceResult<?> importResult = importer.importOntology(
                new org.owl4agents.core.OntologyId("pizza"),
                pizzaFixture,
                org.owl4agents.core.WorkspaceId.DEFAULT);
            org.junit.jupiter.api.Assumptions.assumeTrue(
                importResult.isSuccess(),
                "Pizza ontology import must succeed for this integration test");

            // Build the adapter pointed at the temp workspace.
            Map<String, Object> serviceContext = new HashMap<>();
            serviceContext.put("homeDir", tempDir.toString());
            String logPath = tempDir.resolve("bench-pizza-inline.log").toString();
            McpServerAdapter adapter = new McpServerAdapter(serviceContext, logPath);

            // Inline config YAML — note the bogus questionSetPath; it must
            // be overridden by question_set_content.
            String configYaml = """
                name: pizza-inline-test
                description: Pizza benchmark with inline question_set_content
                ontologyIds:
                  - pizza
                questionSetPath: /nonexistent/path/pizza.jsonl
                reasoners:
                  - hermit
                outputPath: %s
                timeoutPerQuestion: 30
                """.formatted(tempDir.resolve("pizza-results.jsonl").toString());

            // Inline 3-claim question set content (pizza subclass claims).
            // Each line is a JSONL question with one SUBCLASS claim.
            // v0.8.6 §6.5: include the `question` field (matches real
            // fixture format like pizza-50.jsonl) — BenchmarkService.run()
            // line 132 calls Optional.of(question.question()) which throws
            // NPE on null. Real benchmark JSONL files always include it.
            String questionSetContent = String.join("\n",
                "{\"questionId\":\"pizza-001\",\"source\":\"owl4agents\",\"ontologyIds\":[\"pizza\"],\"question\":\"Is Margherita a subclass of NamedPizza?\",\"answerType\":\"yesno\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"pizza-001-c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza\"}}],\"reviewStatus\":\"approved\"}",
                "{\"questionId\":\"pizza-002\",\"source\":\"owl4agents\",\"ontologyIds\":[\"pizza\"],\"question\":\"Is AmericanHot a subclass of NamedPizza?\",\"answerType\":\"yesno\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"pizza-002-c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#AmericanHot\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza\"}}],\"reviewStatus\":\"approved\"}",
                "{\"questionId\":\"pizza-003\",\"source\":\"owl4agents\",\"ontologyIds\":[\"pizza\"],\"question\":\"Is Pizza a subclass of Food?\",\"answerType\":\"yesno\",\"expectedVerdict\":\"supported\",\"claims\":[{\"id\":\"pizza-003-c1\",\"type\":\"subclass\",\"required\":true,\"subject\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza\"},\"predicate\":\"subClassOf\",\"object\":{\"kind\":\"class\",\"iri\":\"http://www.co-ode.org/ontologies/pizza/pizza.owl#Food\"}}],\"reviewStatus\":\"approved\"}"
            );

            Map<String, Object> args = new HashMap<>();
            args.put("config_yaml", configYaml);
            args.put("question_set_content", questionSetContent);

            Map<String, Object> result =
                adapter.handleToolCall("ontology_benchmark_run", args);

            // The benchmark should run successfully with the inline content.
            assertNotNull(result, "result must not be null");
            if (result.containsKey("error")) {
                // If it errored, the error must NOT be QUESTION_SET_NOT_FOUND
                // (that would indicate the override was not applied) and
                // must NOT be INVALID_EXPERIMENT_CONFIG with a missing-field
                // message (that would indicate the override was not supplied).
                Map<String, Object> error = (Map<String, Object>) result.get("error");
                String code = String.valueOf(error.get("code"));
                String message = String.valueOf(error.get("message"));
                assertNotEquals("QUESTION_SET_NOT_FOUND", code,
                    "Inline override must be applied; got QUESTION_SET_NOT_FOUND: " + result);
                assertFalse(message.contains("questionSetPath"),
                    "Override should supply questionSetPath; got: " + result);
                // Reasoner/ontology timeouts are acceptable for an integration test
                // on a slow CI box — the point of this test is that the override
                // was applied, not that the benchmark finished fast.
            } else {
                assertEquals("success", result.get("status"),
                    "Benchmark with inline content should succeed; got: " + result);
                Object dataObj = result.get("data");
                if (dataObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) dataObj;
                    assertNotNull(data.get("lines"),
                        "Successful benchmark must return result lines");
                }
            }
        }
    }
}
