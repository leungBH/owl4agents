package org.owl4agents.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimBatchInput;
import org.owl4agents.core.model.EvidenceContext;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.model.WorkflowOptions;
import org.owl4agents.validation.ClaimWorkflowService;
import org.owl4agents.validation.EvidenceContextBuilder;

/**
 * Context-batch processing service: reads a JSONL question set,
 * verifies each question's claims, builds per-question EvidenceContext
 * with character-based truncation metadata, and produces JSONL output.
 *
 * Pizza-only in v0.6; extensible to other ontologies in v0.7.
 */
public class ContextBatchService {

    private final ClaimWorkflowService workflowService;
    private final EvidenceContextBuilder contextBuilder;
    private final BenchmarkQuestionSetValidator validator;

    public ContextBatchService(ClaimWorkflowService workflowService,
                               EvidenceContextBuilder contextBuilder,
                               BenchmarkQuestionSetValidator validator) {
        this.workflowService = workflowService;
        this.contextBuilder = contextBuilder;
        this.validator = validator;
    }

    /** Per-question batch context result with truncation metadata. */
    public record ContextBatchEntry(
        String questionId,
        String ontologyId,
        EvidenceContext evidenceContext,
        int budgetCharsUsed,
        int totalAvailableEvidenceChars,
        int omittedEvidenceCount,
        int omittedClaimCount,
        String error
    ) {}

    /** Batch result: all entries + any validation errors. */
    public record ContextBatchResult(
        List<ContextBatchEntry> entries,
        List<String> errors
    ) {}

    /**
     * Process a context batch from a question set JSONL file.
     *
     * @param questionSetPath  Path to the JSONL question set file
     * @param ontologyId       Ontology ID to use for verification
     * @param maxContextTokens Maximum context tokens budget (0 = no truncation)
     * @return ContextBatchResult with per-question entries and errors
     */
    public ContextBatchResult processBatch(String questionSetPath,
                                           String ontologyId,
                                           int maxContextTokens) {
        List<ContextBatchEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Read question set
        Path qsPath = Path.of(questionSetPath);
        List<String> questionLines;
        try {
            questionLines = Files.readAllLines(qsPath);
        } catch (IOException e) {
            errors.add("Cannot read question set: " + e.getMessage());
            return new ContextBatchResult(entries, errors);
        }

        int charBudget = maxContextTokens > 0 ? 4 * maxContextTokens : Integer.MAX_VALUE;

        for (String line : questionLines) {
            if (line == null || line.isBlank()) continue;

            // Validate line
            BenchmarkQuestionSetValidator.LineValidationResult validation = validator.validateLine(line);

            if (validation.isBlocked()) {
                String questionId = validation.errors().stream()
                    .map(e -> e.questionId()).filter(id -> id != null).findFirst().orElse("unknown");
                String errorStr = validation.errors().stream()
                    .map(e -> e.code() + ": " + e.diagnostic())
                    .reduce((a, b) -> a + "; " + b).orElse("validation error");
                errors.add(questionId + ": " + errorStr);
                continue;
            }

            BenchmarkQuestion question = validation.question();
            if (question == null) continue;

            String questionId = question.questionId();

            // Convert claims to ClaimBatchInput
            List<Claim> claims = new ArrayList<>();
            for (BenchmarkQuestion.DecomposedClaim dc : question.claims()) {
                claims.add(dc.toClaim(ontologyId));
            }

            String reasoner = question.options()
                .map(o -> o.reasoner()).orElse("hermit");

            ClaimBatchInput batch = new ClaimBatchInput(
                "ctx-" + questionId,
                Optional.of(question.question()),
                Optional.empty(),
                claims.stream().map(c -> claimToBatchClaim(c)).toList(),
                Optional.of(new WorkflowOptions(Optional.of(reasoner), Optional.empty(), Optional.empty(), Optional.empty()))
            );

            // Verify batch
            ServiceResult<AnswerVerificationReport> result = workflowService.verifyBatch(batch, ontologyId);

            if (!result.isSuccess()) {
                org.owl4agents.core.ServiceResult.Error<AnswerVerificationReport> err =
                    (org.owl4agents.core.ServiceResult.Error<AnswerVerificationReport>) result;
                entries.add(new ContextBatchEntry(
                    questionId, ontologyId, null,
                    0, 0, 0, 0, err.error().message()));
                continue;
            }

            AnswerVerificationReport report = ((org.owl4agents.core.ServiceResult.Success<AnswerVerificationReport>) result).data();

            // Build evidence context with character budget
            EvidenceContext context = contextBuilder.buildContext(report, maxContextTokens);

            // Compute truncation metadata
            int budgetCharsUsed = computeBudgetCharsUsed(context, charBudget);
            int totalAvailableEvidenceChars = computeTotalAvailableChars(report);

            // Aggregate omittedEvidenceCount from per-claim entries
            int totalOmittedEvidence = context.claims().stream()
                .mapToInt(c -> c.omittedEvidenceCount()).sum();

            entries.add(new ContextBatchEntry(
                questionId, ontologyId, context,
                budgetCharsUsed,
                totalAvailableEvidenceChars,
                totalOmittedEvidence,
                context.omittedClaimCount(),
                null
            ));
        }

        return new ContextBatchResult(entries, errors);
    }

    private int computeBudgetCharsUsed(EvidenceContext context, int charBudget) {
        // Estimate: sum all visible claim text + evidence summaries
        int total = 0;
        for (EvidenceContext.ClaimContextEntry entry : context.claims()) {
            total += entry.id().length() + entry.claimText().length();
            for (var ev : entry.evidence()) {
                total += ev.summary().length() + ev.kind().length() + 40;
            }
            total += 30; // structural overhead per claim
        }
        return Math.min(total, charBudget);
    }

    private int computeTotalAvailableChars(AnswerVerificationReport report) {
        // Sum all claim evidence characters without truncation
        int total = 0;
        for (var claimResult : report.claimResults()) {
            total += claimResult.claimId().length() + 30;
            for (var ev : claimResult.evidence()) {
                total += ev.summary().length() + ev.kind().length() + ev.source().length() + 40;
            }
        }
        return total;
    }

    private ClaimBatchInput.BatchClaim claimToBatchClaim(Claim claim) {
        return new ClaimBatchInput.BatchClaim(
            claim.claimId(),
            claim.type(),
            true,
            Optional.of(claim.subject()),
            Optional.of(claim.predicate()),
            Optional.of(claim.object()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}