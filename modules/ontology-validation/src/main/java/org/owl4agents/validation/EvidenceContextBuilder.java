package org.owl4agents.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.owl4agents.core.model.*;

/**
 * Builds compact evidence context from workflow reports for LLM agent prompts.
 * Applies deterministic budget/truncation using 4 * maxContextTokens characters,
 * preserves all claim IDs and verdicts under truncation, and never fabricates evidence.
 */
public class EvidenceContextBuilder {

    /** The mandatory agent instruction for citation policy. */
    private static final String CITATION_INSTRUCTION = "Cite only evidence returned in this context.";

    /**
     * Build an evidence context from a workflow report with optional token budget.
     *
     * @param report  The workflow report to project into a compact context
     * @param maxContextTokens  Maximum context tokens budget (each token ≈ 4 characters).
     *                          0 or negative → no truncation limit.
     * @return EvidenceContext ready for injection into agent prompts
     */
    public EvidenceContext buildContext(AnswerVerificationReport report, int maxContextTokens) {
        if (report == null) {
            // Invalid input — return minimal context with invalid status
            return new EvidenceContext(
                "",
                AggregateAnswerStatus.INVALID_INPUT,
                List.of(),
                0,
                List.of(CITATION_INSTRUCTION, "Input report was invalid — no evidence available.")
            );
        }

        // Compute character budget from token budget
        // 4 * maxContextTokens approximation per contract
        int charBudget = maxContextTokens > 0 ? 4 * maxContextTokens : Integer.MAX_VALUE;

        // Build per-claim context entries with budget-aware evidence truncation
        List<EvidenceContext.ClaimContextEntry> claimEntries = new ArrayList<>();
        int omittedClaimCount = 0;
        int charsRemaining = charBudget;

        // Per spec: contradicted claim summaries MUST appear before lower-priority
        // supported evidence when truncation is necessary. Sort contradicted claims
        // to the front, preserving source order within the same verdict group.
        List<ClaimWorkflowResult> sortedClaims = report.claimResults().stream()
            .sorted((a, b) -> {
                boolean aContra = a.verdict() == Verdict.CONTRADICTED;
                boolean bContra = b.verdict() == Verdict.CONTRADICTED;
                if (aContra != bContra) return aContra ? -1 : 1;
                return 0;
            })
            .toList();

        for (ClaimWorkflowResult claimResult : sortedClaims) {
            // Claim ID + verdict must always be visible under truncation per contract.
            // When budget is exhausted, create a minimal entry (id + verdict only,
            // empty evidence, empty claimText) rather than skipping the claim entirely.
            String claimText = buildClaimText(claimResult);
            int headerChars = estimateHeaderChars(claimResult, claimText);

            boolean budgetCanAffordHeader = charsRemaining > headerChars || charsRemaining == charBudget;
            List<WorkflowEvidenceEntry> truncatedEvidence = new ArrayList<>();
            int omittedEvidenceCount = 0;

            if (budgetCanAffordHeader) {
                // Full header fits — reserve header chars and include evidence
                charsRemaining -= headerChars;

                for (WorkflowEvidenceEntry entry : claimResult.evidence()) {
                    int entryChars = estimateEntryChars(entry);
                    if (charsRemaining >= entryChars) {
                        truncatedEvidence.add(entry);
                        charsRemaining -= entryChars;
                    } else {
                        omittedEvidenceCount++;
                    }
                }
            } else {
                // Budget exhausted — create minimal entry with id + verdict only,
                // empty claimText and empty evidence. All evidence counts as omitted.
                // Per spec: this claim's evidence detail was omitted entirely → increment omittedClaimCount.
                claimText = "";
                omittedEvidenceCount = claimResult.evidence().size();
                omittedClaimCount++;
            }

            Optional<String> unknownReason = claimResult.unknownReason();
            Optional<String> scopeDiagnostic = Optional.empty();
            if (claimResult.verdict() == Verdict.OUT_OF_SCOPE) {
                scopeDiagnostic = Optional.of("Entities not declared in ontology: "
                    + (claimResult.missingEntities().isPresent()
                        ? claimResult.missingEntities().get().stream()
                            .map(m -> m.searchTerm())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("unknown")
                        : "unknown"));
            }

            claimEntries.add(new EvidenceContext.ClaimContextEntry(
                claimResult.claimId(),
                claimResult.verdict(),
                claimText,
                truncatedEvidence,
                omittedEvidenceCount,
                unknownReason,
                scopeDiagnostic
            ));
        }

        // Build agent instructions based on aggregate status
        List<String> agentInstructions = buildAgentInstructions(report.aggregateStatus());

        return new EvidenceContext(
            report.answerId(),
            report.aggregateStatus(),
            claimEntries,
            omittedClaimCount,
            agentInstructions
        );
    }

    /**
     * Build a human-readable claim summary text from the workflow result.
     */
    private String buildClaimText(ClaimWorkflowResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.claimType().jsonName());

        // Add subject/predicate/object info if available from evidence or diagnostics
        if (result.diagnostics().isPresent()) {
            sb.append(": ").append(result.diagnostics().get());
        } else if (!result.evidence().isEmpty()) {
            sb.append(": ").append(result.evidence().get(0).summary());
        } else {
            sb.append(" claim ").append(result.claimId());
        }

        return sb.toString();
    }

    /**
     * Estimate character count for a claim context entry header.
     * Includes id, verdict, claimText, and structural overhead.
     */
    private int estimateHeaderChars(ClaimWorkflowResult result, String claimText) {
        // Rough estimate: id (~20) + verdict (~15) + claimText + overhead (~30)
        return result.claimId().length() + 15 + claimText.length() + 30;
    }

    /**
     * Estimate character count for a single evidence entry.
     */
    private int estimateEntryChars(WorkflowEvidenceEntry entry) {
        int chars = entry.kind().length() + entry.summary().length() + entry.source().length();
        chars += entry.reasoner().map(r -> r.length()).orElse(0);
        chars += entry.provenance().map(p -> p.length()).orElse(0);
        // Add overhead for JSON structure (~40 chars)
        chars += 40;
        return chars;
    }

    /**
     * Build agent instructions based on aggregate answer status.
     * Always includes the citation policy instruction.
     */
    private List<String> buildAgentInstructions(AggregateAnswerStatus status) {
        List<String> instructions = new ArrayList<>();
        instructions.add(CITATION_INSTRUCTION);

        switch (status) {
            case CONTRADICTED -> instructions.add(
                "The answer is contradicted by at least one required claim. Do not present it as fact.");
            case INSUFFICIENT_EVIDENCE -> instructions.add(
                "Evidence is insufficient to verify at least one required claim. State clearly what is unverified.");
            case OUT_OF_SCOPE -> instructions.add(
                "All required claims reference entities outside the ontology scope. Do not cite ontology support for these claims.");
            case PARTIALLY_VERIFIED -> instructions.add(
                "Some required claims are supported but others are outside ontology scope. Clearly distinguish verified vs. unscoped claims.");
            case VERIFIED -> instructions.add(
                "All required claims are supported by the ontology evidence.");
            case INVALID_INPUT -> instructions.add(
                "The input was invalid. Do not make claims about ontology verification from this context.");
        }

        return instructions;
    }
}