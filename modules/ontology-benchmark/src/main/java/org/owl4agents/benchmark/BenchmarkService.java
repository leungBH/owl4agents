package org.owl4agents.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimBatchInput;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.model.WorkflowOptions;
import org.owl4agents.validation.ClaimWorkflowService;

/**
 * Runs a benchmark experiment: iterates question set lines, delegates
 * verification to ClaimWorkflowService, applies two-tier validation,
 * handles timeouts, and produces JSONL results.
 *
 * reviewStatus: Optional<String> — absent (null) for Tier-1 blocked lines;
 * "pending" for Tier-2 reviewable; "approved" for normal; "reviewed" for edge-case.
 * edgeCase: boolean — true when the question is flagged edge-case and excluded
 * from primary metrics.
 * verificationCoverage = (supported + contradicted) / totalClaims per spec.
 * unknownRate and outOfScopeRate reported separately per spec.
 */
public class BenchmarkService {

    private final ClaimWorkflowService workflowService;
    private final BenchmarkQuestionSetValidator validator;

    public BenchmarkService(ClaimWorkflowService workflowService,
                            BenchmarkQuestionSetValidator validator) {
        this.workflowService = workflowService;
        this.validator = validator;
    }

    /** Result of a benchmark run: per-question result lines + summary. */
    public record BenchmarkRunResult(
        List<BenchmarkResultLine> lines,
        BenchmarkResultSummary summary
    ) {}

    /**
     * Execute a benchmark run with the given experiment configuration.
     * Returns per-question result lines and a summary.
     */
    public BenchmarkRunResult run(ExperimentConfig config) {
        List<BenchmarkResultLine> lines = new ArrayList<>();
        Map<Verdict, Integer> perVerdictCounts = new HashMap<>();
        for (Verdict v : Verdict.values()) perVerdictCounts.put(v, 0);
        Map<String, Long> perReasonerTiming = new LinkedHashMap<>();

        // Read question set
        Path qsPath = Path.of(config.questionSetPath());
        List<String> questionLines;
        try {
            questionLines = Files.readAllLines(qsPath);
        } catch (IOException e) {
            // Return empty result with error summary
            return new BenchmarkRunResult(List.of(), makeErrorSummary(
                "Cannot read question set: " + e.getMessage()));
        }

        int totalQuestions = 0;
        int correctVerdicts = 0;
        int falseSupportedCount = 0;
        int falseUnknownCount = 0;
        int totalContradictedExpected = 0;
        int totalEvaluated = 0;
        // verificationCoverage counters: (supported + contradicted) / totalClaims
        int supportedOrContradicted = 0;
        int totalClaims = 0;

        for (String line : questionLines) {
            if (line == null || line.isBlank()) continue;

            // Validate line
            BenchmarkQuestionSetValidator.LineValidationResult validation = validator.validateLine(line);

            if (validation.isBlocked()) {
                // Tier 1: structural error — produce error result line with specific error code
                String questionId = validation.errors().stream()
                    .map(e -> e.questionId()).filter(id -> id != null).findFirst().orElse("unknown");
                // Use primary validation error code as the error field
                String primaryErrorCode = validation.errors().get(0).code();
                String primaryDiagnostic = validation.errors().stream()
                    .map(e -> e.code() + ": " + e.diagnostic())
                    .reduce((a, b) -> a + "; " + b).orElse(primaryErrorCode);
                lines.add(new BenchmarkResultLine(
                    questionId, "", "", 0, Verdict.UNKNOWN, Verdict.UNKNOWN,
                    false, 0, Optional.empty(),
                    Optional.of(primaryDiagnostic),
                    false
                ));
                // Tier-1 blocked lines count toward totalClaims but NOT toward supportedOrContradicted
                totalClaims++;
                continue;
            }

            BenchmarkQuestion question = validation.question();
            if (question == null) continue;

            String questionId = question.questionId();

            // Skip edgeCase questions from primary metrics when policy is exclude
            boolean excludeFromMetrics = question.edgeCase() &&
                config.edgeCasePolicy() == ExperimentConfig.EdgeCasePolicy.exclude;
            boolean excludeReviewPending = "pending".equals(validation.reviewStatus());

            // For each reasoner
            for (String reasoner : config.reasoners()) {
                long startMs = System.currentTimeMillis();

                // Convert claims to ClaimBatchInput
                List<Claim> claims = new ArrayList<>();
                for (BenchmarkQuestion.DecomposedClaim dc : question.claims()) {
                    String ontologyId = question.ontologyIds().isEmpty()
                        ? config.ontologyIds().get(0)
                        : question.ontologyIds().get(0);
                    claims.add(dc.toClaim(ontologyId));
                }

                ClaimBatchInput batch = new ClaimBatchInput(
                    "bench-" + questionId,
                    Optional.of(question.question()),
                    Optional.empty(),
                    claims.stream().map(c -> claimToBatchClaim(c)).toList(),
                    Optional.of(new WorkflowOptions(Optional.of(reasoner), Optional.empty(), Optional.empty(), Optional.empty()))
                );

                // Execute with timeout
                Verdict actualVerdict = Verdict.UNKNOWN;
                String errorStr = null;
                int claimsVerified = claims.size();

                String ontologyId = question.ontologyIds().isEmpty()
                    ? config.ontologyIds().get(0)
                    : question.ontologyIds().get(0);

                try {
                    org.owl4agents.core.ServiceResult<AnswerVerificationReport> result =
                        workflowService.verifyBatch(batch, ontologyId);
                    if (result.isSuccess()) {
                        AnswerVerificationReport report = ((org.owl4agents.core.ServiceResult.Success<AnswerVerificationReport>) result).data();
                        actualVerdict = aggregateVerdict(report);
                    } else {
                        org.owl4agents.core.ServiceResult.Error<AnswerVerificationReport> err =
                            (org.owl4agents.core.ServiceResult.Error<AnswerVerificationReport>) result;
                        errorStr = err.error().message();
                    }
                } catch (Exception e) {
                    errorStr = e.getMessage();
                }

                long elapsedMs = System.currentTimeMillis() - startMs;

                // Timeout check
                if (elapsedMs > config.timeoutPerQuestion() * 1000L) {
                    actualVerdict = Verdict.UNKNOWN;
                    errorStr = "TIMEOUT";
                    elapsedMs = config.timeoutPerQuestion() * 1000L;
                }

                // Determine verdict match
                boolean verdictMatch = actualVerdict == question.expectedVerdict();

                // Build result line — reviewStatus spec values: pending/reviewed/approved
                Optional<String> reviewStatus;
                boolean edgeCase;
                if (excludeFromMetrics) {
                    reviewStatus = Optional.of("reviewed");
                    edgeCase = true;
                } else if (excludeReviewPending) {
                    reviewStatus = Optional.of("pending");
                    edgeCase = false;
                } else {
                    reviewStatus = Optional.of("approved");
                    edgeCase = false;
                }

                lines.add(new BenchmarkResultLine(
                    questionId, ontologyId, reasoner, claimsVerified,
                    actualVerdict, question.expectedVerdict(),
                    verdictMatch, elapsedMs, reviewStatus,
                    Optional.ofNullable(errorStr),
                    edgeCase
                ));

                // Update counts
                perVerdictCounts.merge(actualVerdict, 1, Integer::sum);
                perReasonerTiming.merge(reasoner, elapsedMs, Long::sum);

                // verificationCoverage: (supported + contradicted) / totalClaims
                totalClaims++;
                if (actualVerdict == Verdict.SUPPORTED || actualVerdict == Verdict.CONTRADICTED) {
                    supportedOrContradicted++;
                }

                // Metrics computation (excluding edgeCase and pending)
                if (!excludeFromMetrics && !excludeReviewPending) {
                    totalQuestions++;
                    if (verdictMatch) correctVerdicts++;

                    if (question.expectedVerdict() == Verdict.CONTRADICTED) {
                        totalContradictedExpected++;
                        if (actualVerdict == Verdict.SUPPORTED) falseSupportedCount++;
                        if (actualVerdict == Verdict.UNKNOWN) falseUnknownCount++;
                    }
                    totalEvaluated++;
                }
            }
        }

        // Build summary
        double accuracy = totalEvaluated > 0 ? (double) correctVerdicts / totalEvaluated : 0.0;
        double falseSupportRate = totalContradictedExpected > 0
            ? (double) falseSupportedCount / totalContradictedExpected : 0.0;
        double unresolvedRate = totalContradictedExpected > 0
            ? (double) falseUnknownCount / totalContradictedExpected : 0.0;
        double verificationCoverage = totalClaims > 0
            ? (double) supportedOrContradicted / totalClaims : 0.0;

        // Compute unknownRate and outOfScopeRate from perVerdictCounts
        int unknownCount = perVerdictCounts.getOrDefault(Verdict.UNKNOWN, 0);
        int outOfScopeCount = perVerdictCounts.getOrDefault(Verdict.OUT_OF_SCOPE, 0);
        double unknownRate = totalClaims > 0 ? (double) unknownCount / totalClaims : 0.0;
        double outOfScopeRate = totalClaims > 0 ? (double) outOfScopeCount / totalClaims : 0.0;

        BenchmarkResultSummary summary = new BenchmarkResultSummary(
            BenchmarkResultSummary.TYPE,
            totalEvaluated,
            accuracy,
            falseSupportRate,
            falseSupportedCount,
            unresolvedRate,
            falseUnknownCount,
            verificationCoverage,
            unknownRate,
            outOfScopeRate,
            perVerdictCounts,
            perReasonerTiming
        );

        return new BenchmarkRunResult(lines, summary);
    }

    private Verdict aggregateVerdict(AnswerVerificationReport report) {
        // Use the aggregate status to derive a single verdict
        AggregateAnswerStatus status = report.aggregateStatus();
        if (status == null) return Verdict.UNKNOWN;

        String statusName = status.name().toLowerCase();
        if (statusName.contains("supported") || statusName.contains("verified")) return Verdict.SUPPORTED;
        if (statusName.contains("contradicted") || statusName.contains("refuted")) return Verdict.CONTRADICTED;
        if (statusName.contains("out_of_scope") || statusName.contains("outofscope")) return Verdict.OUT_OF_SCOPE;
        return Verdict.UNKNOWN;
    }

    private ClaimBatchInput.BatchClaim claimToBatchClaim(Claim claim) {
        return new ClaimBatchInput.BatchClaim(
            claim.claimId(),
            claim.type(),
            true, // all benchmark claims are required
            Optional.of(claim.subject()),
            Optional.of(claim.predicate()),
            Optional.of(claim.object()),
            Optional.empty(), // no reasoner override per claim
            Optional.empty(), // no graphScope override
            Optional.empty() // no per-claim options
        );
    }

    private BenchmarkResultSummary makeErrorSummary(String error) {
        return new BenchmarkResultSummary(
            BenchmarkResultSummary.TYPE, 0, 0.0, 0.0, 0, 0.0, 0, 0.0, 0.0, 0.0,
            Map.of(), Map.of()
        );
    }
}