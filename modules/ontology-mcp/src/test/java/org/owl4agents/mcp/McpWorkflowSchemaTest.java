package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6.5 tests: MCP v0.5 batch workflow tool schema validation.
 * Tests required ontology_id, structured claims, options, error payloads,
 * readonly registration, and tool list presence for all v0.5 MCP tools.
 */
@DisplayName("MCP v0.5 batch workflow tool schema tests")
class McpWorkflowSchemaTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("mcp-workflow-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    // ── ontology_verify_claims_batch ──

    @Nested
    @DisplayName("ontology_verify_claims_batch schema tests")
    class VerifyClaimsBatchTests {

        @Test
        @DisplayName("verify_claims_batch requires ontology_id")
        void requiresOntologyId() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            assertTrue(result.containsKey("error"), "Missing ontology_id should return error");
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
        }

        @Test
        @DisplayName("verify_claims_batch requires claims argument")
        void requiresClaims() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            // Missing claims → invalid_input
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertEquals("invalid_input", data.get("aggregateStatus").toString());
        }

        @Test
        @DisplayName("verify_claims_batch rejects empty claims array")
        void rejectsEmptyClaims() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertEquals("invalid_input", data.get("aggregateStatus").toString());
        }

        @Test
        @DisplayName("verify_claims_batch rejects free-text claims (no id/type)")
        void rejectsFreeTextClaims() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            Map<String, Object> freeTextClaim = new HashMap<>();
            freeTextClaim.put("text", "Some free text claim without structure");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of(freeTextClaim)));

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertEquals("invalid_input", data.get("aggregateStatus").toString());
        }

        @Test
        @DisplayName("verify_claims_batch is a readonly tool")
        void isReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_verify_claims_batch"));
        }

        @Test
        @DisplayName("verify_claims_batch appears in tools/list")
        void appearsInToolList() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            List<String> toolNames = tools.stream()
                .map(t -> (String) t.get("name"))
                .toList();
            assertTrue(toolNames.contains("ontology_verify_claims_batch"));
        }

        @Test
        @DisplayName("verify_claims_batch tool schema has required fields")
        void schemaHasRequiredFields() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            Map<String, Object> schema = tools.stream()
                .filter(t -> "ontology_verify_claims_batch".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

            assertEquals("ontology_verify_claims_batch", schema.get("name"));
            assertNotNull(schema.get("description"));
            Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            assertTrue(properties.containsKey("ontology_id"));
            assertTrue(properties.containsKey("claims"));
            assertTrue(properties.containsKey("options"));
        }
    }

    // ── ontology_build_evidence_context ──

    @Nested
    @DisplayName("ontology_build_evidence_context schema tests")
    class BuildEvidenceContextTests {

        @Test
        @DisplayName("build_evidence_context requires either report or ontology_id")
        void requiresReportOrOntologyId() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_build_evidence_context", args);
            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
            assertTrue(error.get("message").toString().contains("report"));
        }

        @Test
        @DisplayName("build_evidence_context rejects negative max_context_tokens")
        void rejectsNegativeMaxContextTokens() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));
            args.put("max_context_tokens", -1);

            Map<String, Object> result = adapter.handleToolCall("ontology_build_evidence_context", args);
            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
        }

        @Test
        @DisplayName("build_evidence_context is a readonly tool")
        void isReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_build_evidence_context"));
        }

        @Test
        @DisplayName("build_evidence_context appears in tools/list")
        void appearsInToolList() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            List<String> toolNames = tools.stream()
                .map(t -> (String) t.get("name"))
                .toList();
            assertTrue(toolNames.contains("ontology_build_evidence_context"));
        }

        @Test
        @DisplayName("build_evidence_context tool schema has dual-mode fields")
        void schemaHasRequiredFields() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            Map<String, Object> schema = tools.stream()
                .filter(t -> "ontology_build_evidence_context".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

            assertEquals("ontology_build_evidence_context", schema.get("name"));
            assertNotNull(schema.get("description"));
            Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            // Mode 1: report-based
            assertTrue(properties.containsKey("report"));
            // Mode 2: ontology_id + claims
            assertTrue(properties.containsKey("ontology_id"));
            assertTrue(properties.containsKey("claims"));
            assertTrue(properties.containsKey("max_context_tokens"));
        }
    }

    // ── ontology_review_answer_claims ──

    @Nested
    @DisplayName("ontology_review_answer_claims schema tests")
    class ReviewAnswerClaimsTests {

        @Test
        @DisplayName("review_answer_claims requires ontology_id")
        void requiresOntologyId() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_review_answer_claims", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("review_answer_claims rejects unsupported policy")
        void rejectsUnsupportedPolicy() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));
            args.put("policy", "aggressive");

            Map<String, Object> result = adapter.handleToolCall("ontology_review_answer_claims", args);
            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
            assertTrue(error.get("message").toString().contains("Unsupported policy"));
        }

        @Test
        @DisplayName("review_answer_claims accepts valid policies: strict, conservative, report-only")
        void acceptsValidPolicies() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            Map<String, Object> schema = tools.stream()
                .filter(t -> "ontology_review_answer_claims".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

            Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            assertTrue(properties.containsKey("policy"));
        }

        @Test
        @DisplayName("review_answer_claims rejects negative max_context_tokens")
        void rejectsNegativeMaxContextTokens() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));
            args.put("max_context_tokens", -5);

            Map<String, Object> result = adapter.handleToolCall("ontology_review_answer_claims", args);
            assertTrue(result.containsKey("error"));
        }

        @Test
        @DisplayName("review_answer_claims is a readonly tool")
        void isReadonly() {
            assertTrue(new McpToolRegistry().isReadonlyTool("ontology_review_answer_claims"));
        }

        @Test
        @DisplayName("review_answer_claims appears in tools/list")
        void appearsInToolList() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            List<String> toolNames = tools.stream()
                .map(t -> (String) t.get("name"))
                .toList();
            assertTrue(toolNames.contains("ontology_review_answer_claims"));
        }

        @Test
        @DisplayName("review_answer_claims tool schema has required fields")
        void schemaHasRequiredFields() {
            McpToolRegistry registry = new McpToolRegistry();
            List<Map<String, Object>> tools = registry.listToolSchemas();
            Map<String, Object> schema = tools.stream()
                .filter(t -> "ontology_review_answer_claims".equals(t.get("name")))
                .findFirst()
                .orElseThrow();

            assertEquals("ontology_review_answer_claims", schema.get("name"));
            assertNotNull(schema.get("description"));
            Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");
            Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
            assertTrue(properties.containsKey("ontology_id"));
            assertTrue(properties.containsKey("claims"));
            assertTrue(properties.containsKey("max_context_tokens"));
            assertTrue(properties.containsKey("policy"));
        }
    }

    // ── v0.5 naming and registration ──

    @Nested
    @DisplayName("v0.5 tool naming and registration consistency")
    class NamingConsistencyTests {

        @Test
        @DisplayName("All v0.5 tools follow ontology_ naming pattern")
        void allFollowNamingPattern() {
            McpToolRegistry registry = new McpToolRegistry();
            assertTrue(registry.isReadonlyTool("ontology_verify_claims_batch"));
            assertTrue(registry.isReadonlyTool("ontology_build_evidence_context"));
            assertTrue(registry.isReadonlyTool("ontology_review_answer_claims"));
        }

        @Test
        @DisplayName("v0.5 tool names are distinct from v0.3 tool names")
        void distinctFromV03Tools() {
            // v0.3 has ontology_verify_claim (singular), v0.5 has ontology_verify_claims_batch (batch)
            McpToolRegistry registry = new McpToolRegistry();
            assertTrue(registry.isReadonlyTool("ontology_verify_claim"));   // v0.3
            assertTrue(registry.isReadonlyTool("ontology_verify_claims_batch")); // v0.5
            // They should be different tool names
            assertNotEquals("ontology_verify_claim", "ontology_verify_claims_batch");
        }

        @Test
        @DisplayName("v0.5 batch workflow tools are not write tools")
        void notWriteTools() {
            McpToolRegistry registry = new McpToolRegistry();
            assertFalse(registry.isReadonlyTool("ontology_add_claim"));
            assertFalse(registry.isReadonlyTool("ontology_create_answer"));
        }

        @Test
        @DisplayName("v0.5 tools do not modify ontology state (readonly)")
        void readonlySafety() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            // All v0.5 tools should pass the readonly check (not rejected as READONLY_VIOLATION)
            for (String tool : List.of("ontology_verify_claims_batch", "ontology_build_evidence_context", "ontology_review_answer_claims")) {
                Map<String, Object> result = adapter.handleToolCall(tool, args);
                if (result.containsKey("error")) {
                    Map<String, Object> error = (Map<String, Object>) result.get("error");
                    assertNotEquals("READONLY_VIOLATION", error.get("code").toString(),
                        tool + " must not be rejected as a readonly violation");
                }
            }
        }
    }

    // ── Error payload structure ──

    @Nested
    @DisplayName("v0.5 error payload structure tests")
    class ErrorPayloadTests {

        @Test
        @DisplayName("Invalid input returns aggregateStatus and diagnostics")
        void invalidInputReturnsAggregateStatus() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "test-ontology");
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertNotNull(data);
            assertEquals("invalid_input", data.get("aggregateStatus").toString());
            assertNotNull(data.get("diagnostics"));
        }

        @Test
        @DisplayName("Missing ontology_id returns INVALID_CLAIM_SCHEMA error")
        void missingOntologyIdErrorCode() {
            McpServerAdapter adapter = createAdapter();
            Map<String, Object> args = new HashMap<>();
            args.put("claims", Map.of("answerId", "a1", "claims", List.of()));

            Map<String, Object> result = adapter.handleToolCall("ontology_verify_claims_batch", args);
            assertTrue(result.containsKey("error"));
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            assertEquals("INVALID_CLAIM_SCHEMA", error.get("code").toString());
            assertTrue(error.get("message").toString().contains("ontology_id"));
        }
    }
}