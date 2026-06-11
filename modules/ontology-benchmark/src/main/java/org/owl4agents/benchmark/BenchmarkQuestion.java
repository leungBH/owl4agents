package org.owl4agents.benchmark;

import java.util.List;
import java.util.Optional;

import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.Verdict;

/**
 * A single question in a benchmark question set (JSONL line).
 * Embeds the NL question, source metadata, expected verdict,
 * AND the structured claim decomposition.
 */
public record BenchmarkQuestion(
    String questionId,
    String source,
    String sourceYear,
    String domain,
    List<String> ontologyIds,
    String question,
    String answerType,
    String expectedAnswer,
    Verdict expectedVerdict,
    List<DecomposedClaim> claims,
    String reviewStatus,
    boolean edgeCase,
    Optional<QuestionOptions> options
) {

    /**
     * A structured claim within a benchmark question.
     * Maps to the core Claim model for verification execution.
     */
    public record DecomposedClaim(
        String id,
        ClaimType type,
        boolean required,
        ClaimEntity subject,
        String predicate,
        ClaimEntity object
    ) {
        /** Convert to a core Claim for verification. */
        public Claim toClaim(String ontologyId) {
            return new Claim(
                id,
                type,
                ontologyId,
                subject,
                predicate,
                object,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }
    }

    /** Per-question execution options. */
    public record QuestionOptions(
        String reasoner,
        boolean requireReasoning
    ) {}
}