package org.owl4agents.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.model.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.5 CLI schema v2 output tests (task 10.7).
 *
 * Verifies that:
 * - JSON output includes schemaVersion = "claim-verification-result/2"
 * - Timeout results show REASONER_TIMEOUT with semanticVerdict=null
 * - Axiom build failure shows CLAIM_AXIOM_BUILD_FAILED
 * - Per-stage timing metadata is present in JSON
 * - Errored results have executionStatus != "completed"
 */
@DisplayName("CLI schema v2 output tests")
class CliSchemaV2Test {

    private static final com.google.gson.Gson gson = GsonFactory.createGson();

    @Nested
    @DisplayName("Schema v2 JSON output")
    class SchemaV2JsonTests {

        @Test
        @DisplayName("Completed result JSON includes schemaVersion field")
        void completedResultIncludesSchemaVersion() {
            ClaimVerificationResult result = ClaimVerificationResult.completed(
                "claim-001", "test-onto", ClaimType.SUBCLASS,
                Verdict.SUPPORTED, List.of(),
                Optional.empty(), Optional.empty(),
                Optional.of("HermiT"), Optional.empty(),
                false, 0
            );

            com.google.gson.JsonObject json = gson.toJsonTree(result).getAsJsonObject();
            json.addProperty("schemaVersion", "claim-verification-result/2");
            String output = gson.toJson(json);

            assertTrue(output.contains("\"schemaVersion\":\"claim-verification-result/2\""),
                "schemaVersion field must be present in JSON output");
            assertTrue(output.contains("\"executionStatus\":\"completed\""),
                "executionStatus must be 'completed'");
            assertTrue(output.contains("\"semanticVerdict\":\"supported\""),
                "semanticVerdict must be 'supported'");
        }

        @Test
        @DisplayName("Timeout result JSON shows REASONER_TIMEOUT with null semanticVerdict")
        void timeoutResultShowsReasonerTimeout() {
            ClaimVerificationResult result = ClaimVerificationResult.errored(
                "claim-timeout-001", "test-onto", ClaimType.SUBCLASS,
                ExecutionStatus.TIMEOUT, ErrorCode.REASONER_TIMEOUT,
                Optional.of("HermiT"), Optional.empty(),
                PerStageTiming.empty()
            );

            com.google.gson.JsonObject json = gson.toJsonTree(result).getAsJsonObject();
            json.addProperty("schemaVersion", "claim-verification-result/2");
            String output = gson.toJson(json);

            assertTrue(output.contains("\"executionStatus\":\"timeout\""),
                "executionStatus must be 'timeout'");
            assertTrue(output.contains("\"semanticVerdict\":null"),
                "semanticVerdict must be null for timeout");
            assertTrue(output.contains("\"errorCode\":\"REASONER_TIMEOUT\""),
                "errorCode must be REASONER_TIMEOUT");
        }

        @Test
        @DisplayName("Axiom build failure shows CLAIM_AXIOM_BUILD_FAILED")
        void axiomBuildFailureShowsCorrectErrorCode() {
            ClaimVerificationResult result = ClaimVerificationResult.errored(
                "claim-build-fail-001", "test-onto", ClaimType.SUBCLASS,
                ExecutionStatus.ERROR, ErrorCode.CLAIM_AXIOM_BUILD_FAILED,
                Optional.empty(), Optional.empty(),
                PerStageTiming.empty()
            );

            com.google.gson.JsonObject json = gson.toJsonTree(result).getAsJsonObject();
            json.addProperty("schemaVersion", "claim-verification-result/2");
            String output = gson.toJson(json);

            assertTrue(output.contains("\"executionStatus\":\"error\""),
                "executionStatus must be 'error'");
            assertTrue(output.contains("\"semanticVerdict\":null"),
                "semanticVerdict must be null for error");
            assertTrue(output.contains("\"errorCode\":\"CLAIM_AXIOM_BUILD_FAILED\""),
                "errorCode must be CLAIM_AXIOM_BUILD_FAILED");
        }
    }

    @Nested
    @DisplayName("Per-stage timing metadata")
    class PerStageTimingTests {

        @Test
        @DisplayName("PerStageTiming serializes with all 8 stage fields")
        void perStageTimingSerializes() {
            PerStageTiming timing = new PerStageTiming(
                5L,   // axiomBuildMs
                10L,  // sourceConsistencyMs
                15L,  // entailmentMs
                20L,  // temporaryCopyMs
                25L,  // reasonerInitMs
                30L,  // consistencyCheckMs
                35L,  // explanationMs
                140L  // totalMs
            );

            ClaimVerificationResult result = ClaimVerificationResult.errored(
                "claim-timing-001", "test-onto", ClaimType.SUBCLASS,
                ExecutionStatus.TIMEOUT, ErrorCode.REASONER_TIMEOUT,
                Optional.of("HermiT"), Optional.empty(),
                timing
            );

            String json = gson.toJson(result);

            assertTrue(json.contains("\"axiomBuildMs\":5"), "axiomBuildMs must be present");
            assertTrue(json.contains("\"sourceConsistencyMs\":10"), "sourceConsistencyMs must be present");
            assertTrue(json.contains("\"entailmentMs\":15"), "entailmentMs must be present");
            assertTrue(json.contains("\"temporaryCopyMs\":20"), "temporaryCopyMs must be present");
            assertTrue(json.contains("\"reasonerInitMs\":25"), "reasonerInitMs must be present");
            assertTrue(json.contains("\"consistencyCheckMs\":30"), "consistencyCheckMs must be present");
            assertTrue(json.contains("\"explanationMs\":35"), "explanationMs must be present");
            assertTrue(json.contains("\"totalMs\":140"), "totalMs must be present");
        }

        @Test
        @DisplayName("Empty PerStageTiming serializes all fields as null")
        void emptyPerStageTimingSerializesAsNull() {
            PerStageTiming timing = PerStageTiming.empty();

            String json = gson.toJson(timing);

            assertTrue(json.contains("\"axiomBuildMs\":null"), "axiomBuildMs must be null");
            assertTrue(json.contains("\"totalMs\":null"), "totalMs must be null");
        }
    }

    @Nested
    @DisplayName("Evidence kinds v0.8.5")
    class EvidenceKindTests {

        @Test
        @DisplayName("CONSISTENCY_REPORT evidence kind serializes correctly")
        void consistencyReportKindSerializes() {
            EvidenceItem item = new EvidenceItem(
                "ev-001", EvidenceItem.ROLE_COUNTER,
                EvidenceKind.CONSISTENCY_REPORT,
                "Adding the claim axiom makes the ontology inconsistent.",
                "exact-consistency-check", "Openllet", "UNION",
                List.of("http://example.org/A"), EvidenceItem.CONFIDENCE_INFERRED
            );

            String json = gson.toJson(item);

            assertTrue(json.contains("\"kind\":\"consistency_report\""),
                "CONSISTENCY_REPORT must serialize as 'consistency_report'");
        }

        @Test
        @DisplayName("INCONSISTENCY_JUSTIFICATION evidence kind serializes correctly")
        void inconsistencyJustificationKindSerializes() {
            EvidenceItem item = new EvidenceItem(
                "ev-002", EvidenceItem.ROLE_COUNTER,
                EvidenceKind.INCONSISTENCY_JUSTIFICATION,
                "Conflict: A ⊑ B ∧ B ⊓ A ⊑ ⊥",
                "exact-consistency-check", "Openllet", "UNION",
                List.of("http://example.org/A", "http://example.org/B"),
                EvidenceItem.CONFIDENCE_INFERRED
            );

            String json = gson.toJson(item);

            assertTrue(json.contains("\"kind\":\"inconsistency_justification\""),
                "INCONSISTENCY_JUSTIFICATION must serialize as 'inconsistency_justification'");
        }

        @Test
        @DisplayName("STRUCTURAL_CONFLICT_HINT evidence kind serializes correctly")
        void structuralConflictHintKindSerializes() {
            EvidenceItem item = new EvidenceItem(
                "ev-003", EvidenceItem.ROLE_SUPPORTING,
                EvidenceKind.STRUCTURAL_CONFLICT_HINT,
                "Structural proxy detected potential disjointness (not formal evidence).",
                "structural-proxy", "default", "UNION",
                List.of("http://example.org/A"), EvidenceItem.CONFIDENCE_INFERRED
            );

            String json = gson.toJson(item);

            assertTrue(json.contains("\"kind\":\"structural_conflict_hint\""),
                "STRUCTURAL_CONFLICT_HINT must serialize as 'structural_conflict_hint'");
        }
    }

    @Nested
    @DisplayName("Timeout CLI flag parsing")
    class TimeoutParsingTests {

        @Test
        @DisplayName("Null timeout returns null Duration")
        void nullTimeoutReturnsNull() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, (String) null);
            assertNull(result, "Null timeout should return null Duration");
        }

        @Test
        @DisplayName("Blank timeout returns null Duration")
        void blankTimeoutReturnsNull() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, "  ");
            assertNull(result, "Blank timeout should return null Duration");
        }

        @Test
        @DisplayName("30s parses to 30 seconds")
        void thirtySecondsParses() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, "30s");
            assertEquals(Duration.ofSeconds(30), result, "30s should parse to 30 seconds");
        }

        @Test
        @DisplayName("2m parses to 2 minutes")
        void twoMinutesParses() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, "2m");
            assertEquals(Duration.ofMinutes(2), result, "2m should parse to 2 minutes");
        }

        @Test
        @DisplayName("500ms parses to 500 milliseconds")
        void fiveHundredMsParses() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, "500ms");
            assertEquals(Duration.ofMillis(500), result, "500ms should parse to 500 milliseconds");
        }

        @Test
        @DisplayName("PT30S ISO-8601 format parses correctly")
        void iso8601FormatParses() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            Duration result = (Duration) method.invoke(null, "PT30S");
            assertEquals(Duration.ofSeconds(30), result, "PT30S should parse to 30 seconds");
        }

        @Test
        @DisplayName("Invalid format throws IllegalArgumentException")
        void invalidFormatThrows() throws Exception {
            java.lang.reflect.Method method = VerifyClaimCommand.class.getDeclaredMethod("parseTimeout", String.class);
            method.setAccessible(true);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
                method.invoke(null, "invalid");
            }, "Invalid format should throw IllegalArgumentException");
        }
    }
}
