package org.owl4agents.benchmark;

import org.owl4agents.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EvidenceContextJsonlSerializer.
 * Validates: all compact fields present, character-based truncation metadata
 * included, single-claim fallback produces single-line JSONL.
 */
class EvidenceContextJsonlSerializerTest {

    private final EvidenceContextJsonlSerializer serializer = new EvidenceContextJsonlSerializer();
    private final Gson gson = new Gson();

    @Test
    @DisplayName("All compact fields present in JSONL output")
    void allCompactFieldsPresent() {
        EvidenceContext context = buildSampleContext(2);
        String jsonl = serializer.serializeToJsonl(context, 0, 1000);

        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());

        // Core fields
        assertEquals("answer-1", parsed.get("answerId"));
        assertEquals("verified", parsed.get("status"));

        // Per-claim entries
        List<Map<String, Object>> claims = (List<Map<String, Object>>) parsed.get("claims");
        assertNotNull(claims);
        assertEquals(2, claims.size());

        Map<String, Object> claim0 = claims.get(0);
        assertEquals("c1", claim0.get("id"));
        assertEquals("supported", claim0.get("verdict"));
        assertEquals("Pizza is a food subclass", claim0.get("claimText"));
        assertEquals(0, ((Number) claim0.get("omittedEvidenceCount")).intValue());

        // Evidence entries within each claim
        List<Map<String, Object>> evidence0 = (List<Map<String, Object>>) claim0.get("evidence");
        assertNotNull(evidence0);
        assertEquals(1, evidence0.size());
        assertEquals("axiom_derivation", evidence0.get(0).get("kind"));
        assertEquals("SubClassOf(Pizza Food)", evidence0.get(0).get("summary"));
        assertEquals("hermit", evidence0.get(0).get("source"));

        // Top-level omitted claim count
        assertEquals(0, ((Number) parsed.get("omittedClaimCount")).intValue());

        // Agent instructions
        List<String> instructions = (List<String>) parsed.get("agentInstructions");
        assertNotNull(instructions);
        assertTrue(instructions.size() > 0);
    }

    @Test
    @DisplayName("Character-based truncation metadata included even with zero values")
    void truncationMetadataPresentEvenWithZeroValues() {
        EvidenceContext context = buildSampleContext(1);
        String jsonl = serializer.serializeToJsonl(context, 0, 0);

        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());

        // Truncation metadata must be present even with zero values
        assertTrue(parsed.containsKey("budgetCharsUsed"));
        assertTrue(parsed.containsKey("totalAvailableEvidenceChars"));
        assertEquals(0, ((Number) parsed.get("budgetCharsUsed")).intValue());
        assertEquals(0, ((Number) parsed.get("totalAvailableEvidenceChars")).intValue());
    }

    @Test
    @DisplayName("Character-based truncation metadata with nonzero values")
    void truncationMetadataWithNonzeroValues() {
        EvidenceContext context = buildSampleContext(1);
        String jsonl = serializer.serializeToJsonl(context, 800, 2000);

        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());

        assertEquals(800, ((Number) parsed.get("budgetCharsUsed")).intValue());
        assertEquals(2000, ((Number) parsed.get("totalAvailableEvidenceChars")).intValue());
    }

    @Test
    @DisplayName("Unknown reason and scope diagnostic included when present")
    void optionalFieldsIncludedWhenPresent() {
        WorkflowEvidenceEntry ev = new WorkflowEvidenceEntry(
            "axiom_derivation", "No entailment found", "hermit",
            Optional.of("hermit"), Optional.of("Pizza.owl"));

        EvidenceContext.ClaimContextEntry entry = new EvidenceContext.ClaimContextEntry(
            "c1", Verdict.UNKNOWN, "Unknown claim text",
            List.of(ev), 2,
            Optional.of("No relevant axioms"), Optional.of("out_of_scope"));

        EvidenceContext context = new EvidenceContext(
            "answer-1", AggregateAnswerStatus.PARTIALLY_VERIFIED,
            List.of(entry), 1, List.of("Verify remaining claims manually"));

        String jsonl = serializer.serializeToJsonl(context, 500, 1500);
        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());

        List<Map<String, Object>> claims = (List<Map<String, Object>>) parsed.get("claims");
        Map<String, Object> claim = claims.get(0);

        assertEquals("No relevant axioms", claim.get("unknownReason"));
        assertEquals("out_of_scope", claim.get("scopeDiagnostic"));
        assertEquals(2, ((Number) claim.get("omittedEvidenceCount")).intValue());

        // Evidence with reasoner and provenance
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) claim.get("evidence");
        assertEquals("hermit", evidence.get(0).get("reasoner"));
        assertEquals("Pizza.owl", evidence.get(0).get("provenance"));
    }

    @Test
    @DisplayName("Single-claim fallback produces single-line JSONL")
    void singleClaimFallbackProducesSingleLine() {
        WorkflowEvidenceEntry ev = new WorkflowEvidenceEntry(
            "axiom_derivation", "SubClassOf(Pizza Food)", "hermit",
            Optional.empty(), Optional.empty());

        EvidenceContext.ClaimContextEntry entry = new EvidenceContext.ClaimContextEntry(
            "c1", Verdict.SUPPORTED, "Pizza is a food subclass",
            List.of(ev), 0, Optional.empty(), Optional.empty());

        EvidenceContext context = new EvidenceContext(
            "answer-1", AggregateAnswerStatus.VERIFIED,
            List.of(entry), 0, List.of("Answer is fully supported by ontology"));

        String jsonl = serializer.serializeSingleClaimJsonl(context, 400, 1200);

        // Single line: no newline characters in the output
        assertFalse(jsonl.contains("\n"));

        // Parseable as single JSON object
        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());
        assertEquals("answer-1", parsed.get("answerId"));
        assertEquals(1, ((List<?>) parsed.get("claims")).size());
        assertEquals(400, ((Number) parsed.get("budgetCharsUsed")).intValue());
        assertEquals(1200, ((Number) parsed.get("totalAvailableEvidenceChars")).intValue());
    }

    @Test
    @DisplayName("JSONL output is independently parseable — single JSON object per line")
    void jsonlIndependentlyParseable() {
        EvidenceContext context = buildSampleContext(3);
        String jsonl = serializer.serializeToJsonl(context, 0, 3000);

        // No newline characters — single line, independently parseable
        assertFalse(jsonl.contains("\n"));

        // Must parse as a valid JSON object
        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());
        assertNotNull(parsed);
        assertEquals("answer-1", parsed.get("answerId"));
    }

    @Test
    @DisplayName("Optional fields omitted when empty (no null values in JSONL)")
    void optionalFieldsOmittedWhenEmpty() {
        WorkflowEvidenceEntry ev = new WorkflowEvidenceEntry(
            "axiom_derivation", "SubClassOf(Pizza Food)", "hermit",
            Optional.empty(), Optional.empty());

        EvidenceContext.ClaimContextEntry entry = new EvidenceContext.ClaimContextEntry(
            "c1", Verdict.SUPPORTED, "Pizza is a food subclass",
            List.of(ev), 0, Optional.empty(), Optional.empty());

        EvidenceContext context = new EvidenceContext(
            "answer-1", AggregateAnswerStatus.VERIFIED,
            List.of(entry), 0, List.of("Answer is fully supported"));

        String jsonl = serializer.serializeToJsonl(context, 0, 500);
        Map<String, Object> parsed = gson.fromJson(jsonl, new TypeToken<Map<String, Object>>(){}.getType());

        List<Map<String, Object>> claims = (List<Map<String, Object>>) parsed.get("claims");
        Map<String, Object> claim = claims.get(0);

        // unknownReason and scopeDiagnostic should NOT appear when empty
        assertFalse(claim.containsKey("unknownReason"));
        assertFalse(claim.containsKey("scopeDiagnostic"));

        // Evidence: reasoner and provenance should NOT appear when empty
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) claim.get("evidence");
        assertFalse(evidence.get(0).containsKey("reasoner"));
        assertFalse(evidence.get(0).containsKey("provenance"));
    }

    // Helper: build a sample EvidenceContext with N claims
    private EvidenceContext buildSampleContext(int numClaims) {
        List<EvidenceContext.ClaimContextEntry> entries = new java.util.ArrayList<>();
        for (int i = 1; i <= numClaims; i++) {
            WorkflowEvidenceEntry ev = new WorkflowEvidenceEntry(
                "axiom_derivation", "SubClassOf(Pizza Food)", "hermit",
                Optional.empty(), Optional.empty());
            entries.add(new EvidenceContext.ClaimContextEntry(
                "c" + i, Verdict.SUPPORTED, "Pizza is a food subclass",
                List.of(ev), 0, Optional.empty(), Optional.empty()));
        }
        return new EvidenceContext(
            "answer-1", AggregateAnswerStatus.VERIFIED,
            entries, 0, List.of("Answer is fully supported by ontology"));
    }
}
