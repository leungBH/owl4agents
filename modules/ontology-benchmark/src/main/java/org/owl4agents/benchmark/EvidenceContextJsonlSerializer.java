package org.owl4agents.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.model.EvidenceContext;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.model.WorkflowEvidenceEntry;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;

/**
 * Serializes EvidenceContext as a single JSONL line with all existing
 * compact fields plus character-based truncation metadata.
 *
 * Streamable, independently parseable, truncation metadata present
 * even with zero values.
 */
public class EvidenceContextJsonlSerializer {

    private final Gson gson = GsonFactory.createGson();

    /**
     * Serialize an EvidenceContext to a JSONL line with truncation metadata.
     *
     * @param context           The evidence context to serialize
     * @param budgetCharsUsed   Character budget used for truncation
     * @param totalAvailableChars  Total available evidence chars (before truncation)
     * @return A single JSONL line (no trailing newline)
     */
    public String serializeToJsonl(EvidenceContext context,
                                     int budgetCharsUsed,
                                     int totalAvailableChars) {
        Map<String, Object> line = new LinkedHashMap<>();

        // Core fields from EvidenceContext
        line.put("answerId", context.answerId());
        line.put("status", context.status().jsonName());

        // Per-claim entries
        for (EvidenceContext.ClaimContextEntry entry : context.claims()) {
            Map<String, Object> claimMap = new LinkedHashMap<>();
            claimMap.put("id", entry.id());
            claimMap.put("verdict", entry.verdict().jsonName());
            claimMap.put("claimText", entry.claimText());
            claimMap.put("omittedEvidenceCount", entry.omittedEvidenceCount());
            entry.unknownReason().ifPresent(r -> claimMap.put("unknownReason", r));
            entry.scopeDiagnostic().ifPresent(d -> claimMap.put("scopeDiagnostic", d));

            // Evidence entries
            for (WorkflowEvidenceEntry ev : entry.evidence()) {
                Map<String, Object> evMap = new LinkedHashMap<>();
                evMap.put("kind", ev.kind());
                evMap.put("summary", ev.summary());
                evMap.put("source", ev.source());
                ev.reasoner().ifPresent(r -> evMap.put("reasoner", r));
                ev.provenance().ifPresent(p -> evMap.put("provenance", p));
            }
        }

        // Claims as list
        line.put("claims", context.claims().stream().map(entry -> {
            Map<String, Object> claimMap = new LinkedHashMap<>();
            claimMap.put("id", entry.id());
            claimMap.put("verdict", entry.verdict().jsonName());
            claimMap.put("claimText", entry.claimText());
            claimMap.put("omittedEvidenceCount", entry.omittedEvidenceCount());
            entry.unknownReason().ifPresent(r -> claimMap.put("unknownReason", r));
            entry.scopeDiagnostic().ifPresent(d -> claimMap.put("scopeDiagnostic", d));

            // Evidence entries
            claimMap.put("evidence", entry.evidence().stream().map(ev -> {
                Map<String, Object> evMap = new LinkedHashMap<>();
                evMap.put("kind", ev.kind());
                evMap.put("summary", ev.summary());
                evMap.put("source", ev.source());
                ev.reasoner().ifPresent(r -> evMap.put("reasoner", r));
                ev.provenance().ifPresent(p -> evMap.put("provenance", p));
                return evMap;
            }).toList());

            return claimMap;
        }).toList());

        line.put("omittedClaimCount", context.omittedClaimCount());
        line.put("agentInstructions", context.agentInstructions());

        // Character-based truncation metadata (present even with zero values)
        line.put("budgetCharsUsed", budgetCharsUsed);
        line.put("totalAvailableEvidenceChars", totalAvailableChars);

        return gson.toJson(line);
    }

    /**
     * Single-claim fallback: serialize a context with one claim as a single JSONL line.
     */
    public String serializeSingleClaimJsonl(EvidenceContext context,
                                              int budgetCharsUsed,
                                              int totalAvailableChars) {
        return serializeToJsonl(context, budgetCharsUsed, totalAvailableChars);
    }
}