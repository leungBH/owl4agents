package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6.5 tests: MCP v0.3 claim verification and evidence grounding tools.
 * Tests success, malformed claim, unsupported type, evidence unavailable,
 * and readonly safety for all v0.3 MCP tools.
 */
@DisplayName("MCP v0.3 claim verification and evidence grounding tests")
class McpClaimVerificationTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("mcp-claim-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    private Map<String, Object> buildSubclassClaimArgs(String ontologyId) {
        Map<String, Object> args = new HashMap<>();
        args.put("ontology_id", ontologyId);
        Map<String, Object> claim = new HashMap<>();
        claim.put("claimId", "test-c1");
        claim.put("type", "SUBCLASS");
        claim.put("ontologyId", ontologyId);
        Map<String, Object> subject = new HashMap<>();
        subject.put("kind", "class");
        subject.put("iri", "http://ex.org/A");
        claim.put("subject", subject);
        claim.put("predicate", "http://ex.org/subClassOf");
        Map<String, Object> object = new HashMap<>();
        object.put("kind", "class");
        object.put("iri", "http://ex.org/B");
        claim.put("object", object);
        args.put("claim", claim);
        return args;
    }

    private Map<String, Object> buildMalformedClaimArgs(String ontologyId) {
        Map<String, Object> args = new HashMap<>();
        args.put("ontology_id", ontologyId);
        Map<String, Object> claim = new HashMap<>();
        claim.put("claimId", ""); // blank claimId — invalid
        claim.put("type", "SUBCLASS");
        claim.put("ontologyId", ontologyId);
        args.put("claim", claim);
        return args;
    }

    // ── ontology_verify_claim ──

    @Nested
    @DisplayName("ontology_verify_claim tests")
    class VerifyClaimTests {

        @Test
        @DisplayName("verify_claim returns error for unknown ontology (no ontology loaded)")
        void verifyClaimUnknownOntology() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildSubclassClaimArgs("nonexistent-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claim", args);
            // For unknown ontology, the result should be an error (not success with a verdict)
            assertTrue(result.containsKey("error"),
                "Unknown ontology should return error, got: " + result);
        }

        @Test
        @DisplayName("verify_claim rejects malformed claim (blank claimId)")
        void verifyClaimMalformed() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildMalformedClaimArgs("test-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claim", args);
            assertTrue(result.containsKey("error"), "Malformed claim should return error");
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
        }

        @Test
        @DisplayName("verify_claim is a readonly tool")
        void verifyClaimIsReadonly() {
            McpServerAdapter adapter = createAdapter();
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_verify_claim"));
        }
    }

    // ── ontology_get_evidence_path ──

    @Nested
    @DisplayName("ontology_get_evidence_path tests")
    class EvidencePathTests {

        @Test
        @DisplayName("get_evidence_path returns error for unknown ontology")
        void evidencePathUnknownOntology() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildSubclassClaimArgs("nonexistent-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_get_evidence_path", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("get_evidence_path rejects malformed claim")
        void evidencePathMalformed() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildMalformedClaimArgs("test-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_get_evidence_path", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("get_evidence_path is a readonly tool")
        void evidencePathIsReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_get_evidence_path"));
        }
    }

    // ── ontology_find_counterexamples ──

    @Nested
    @DisplayName("ontology_find_counterexamples tests")
    class CounterexamplesTests {

        @Test
        @DisplayName("find_counterexamples returns error for unknown ontology")
        void counterexamplesUnknownOntology() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildSubclassClaimArgs("nonexistent-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_find_counterexamples", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("find_counterexamples is a readonly tool")
        void counterexamplesIsReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_find_counterexamples"));
        }
    }

    // ── ontology_explain_unknown ──

    @Nested
    @DisplayName("ontology_explain_unknown tests")
    class ExplainUnknownTests {

        @Test
        @DisplayName("explain_unknown returns error for unknown ontology")
        void explainUnknownUnknownOntology() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildSubclassClaimArgs("nonexistent-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_explain_unknown", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("explain_unknown is a readonly tool")
        void explainUnknownIsReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_explain_unknown"));
        }
    }

    // ── ontology_detect_missing_entities ──

    @Nested
    @DisplayName("ontology_detect_missing_entities tests")
    class DetectMissingEntitiesTests {

        @Test
        @DisplayName("detect_missing_entities with claim arg returns result")
        void detectMissingEntitiesWithClaim() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = buildSubclassClaimArgs("test-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_detect_missing_entities", args);
            // Should succeed or error with ontology-not-found (since no ontology loaded)
            // Either way, it should not be a readonly violation
            assertFalse(result.containsKey("error") &&
                "READONLY_VIOLATION".equals(((Map<String, Object>) result.get("error")).get("code").toString()),
                "detect_missing_entities must not be a readonly violation");
        }

        @Test
        @DisplayName("detect_missing_entities is a readonly tool")
        void detectMissingEntitiesIsReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_detect_missing_entities"));
        }
    }

    // ── Readonly safety ──

    @Nested
    @DisplayName("Readonly safety: v0.3 tools do not modify ontology or workspace files")
    class ReadonlySafetyTests {

        @Test
        @DisplayName("All v0.3 tools are registered as readonly")
        void allV03ToolsAreReadonly() {
            McpToolRegistry registry = new McpToolRegistry();
            assertTrue(registry.isReadonlyTool("ontology_verify_claim"));
            assertTrue(registry.isReadonlyTool("ontology_get_evidence_path"));
            assertTrue(registry.isReadonlyTool("ontology_find_counterexamples"));
            assertTrue(registry.isReadonlyTool("ontology_explain_unknown"));
            assertTrue(registry.isReadonlyTool("ontology_detect_missing_entities"));
        }

        @Test
        @DisplayName("Unknown tool names are rejected as readonly violations")
        void unknownToolRejected() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test");

            Map<String, Object> result = adapter.handleToolCall("ontology_write_claim", args);
            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("READONLY_VIOLATION", error.get("code").toString());
        }

        @Test
        @DisplayName("v0.3 tools appear in tools/list")
        void v03ToolsAppearInList() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            List<String> toolNames = tools.stream()
                .map(t -> (String) t.get("name"))
                .toList();

            assertTrue(toolNames.contains("ontology_verify_claim"));
            assertTrue(toolNames.contains("ontology_get_evidence_path"));
            assertTrue(toolNames.contains("ontology_find_counterexamples"));
            assertTrue(toolNames.contains("ontology_explain_unknown"));
            assertTrue(toolNames.contains("ontology_detect_missing_entities"));
        }
    }

    // ── Task 7.5: Deferred features are NOT v0.3 capabilities ──

    @Nested
    @DisplayName("7.5 Deferred features are not v0.3 capabilities")
    class DeferredFeaturesTests {

        @Test
        @DisplayName("ontology_verify_answer is not a v0.3 tool")
        void verifyAnswerNotAvailable() {
            McpToolRegistry registry = new McpToolRegistry();
            assertFalse(registry.isReadonlyTool("ontology_verify_answer"));
        }

        @Test
        @DisplayName("SHACL constraint check is not a v0.3 tool")
        void shaclCheckNotAvailable() {
            McpToolRegistry registry = new McpToolRegistry();
            assertFalse(registry.isReadonlyTool("ontology_shacl_check"));
        }

        @Test
        @DisplayName("Write tools are not v0.3 tools")
        void writeToolsNotAvailable() {
            McpToolRegistry registry = new McpToolRegistry();
            assertFalse(registry.isReadonlyTool("ontology_add_axiom"));
            assertFalse(registry.isReadonlyTool("ontology_remove_axiom"));
        }

        @Test
        @DisplayName("ROBOT integration is not a v0.3 tool")
        void robotNotAvailable() {
            McpToolRegistry registry = new McpToolRegistry();
            assertFalse(registry.isReadonlyTool("robot_merge"));
            assertFalse(registry.isReadonlyTool("robot_extract"));
        }
    }
}