package org.owl4agents.benchmark;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.owl4agents.core.model.Verdict;

/**
 * Computes QA evaluation metrics from benchmark result JSONL:
 * accuracy, false support rate, unresolved rate, verification coverage,
 * unknown rate, out-of-scope rate, and 4x4 expected-vs-actual verdict
 * confusion matrix.
 *
 * verificationCoverage = (supported + contradicted) / totalClaims per spec.
 * Excludes edgeCase and reviewStatus: pending questions from primary metrics.
 */
public class QaEvaluationService {

    /** Complete evaluation metrics. */
    public record QaEvaluation(
        double accuracy,
        double falseSupportRate,
        int falseSupportedCount,
        double unresolvedRate,
        int falseUnknownCount,
        double verificationCoverage,
        double unknownRate,
        double outOfScopeRate,
        ConfusionMatrix confusionMatrix
    ) {}

    /**
     * Evaluate benchmark results and compute metrics.
     * Excludes edgeCase and reviewStatus: pending questions from primary metrics.
     * verificationCoverage = (supported + contradicted) / totalClaims per spec,
     * where totalClaims includes ALL result lines (edge_case, pending, blocked).
     */
    public QaEvaluation evaluate(List<BenchmarkResultLine> results) {
        if (results == null || results.isEmpty()) {
            return new QaEvaluation(0.0, 0.0, 0, 0.0, 0, 0.0, 0.0, 0.0,
                buildEmptyMatrix());
        }

        // Build 4x4 confusion matrix
        EnumMap<Verdict, EnumMap<Verdict, Integer>> matrix = new EnumMap<>(Verdict.class);
        for (Verdict expected : Verdict.values()) {
            EnumMap<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict actual : Verdict.values()) {
                row.put(actual, 0);
            }
            matrix.put(expected, row);
        }

        int totalEvaluated = 0;
        int correctVerdicts = 0;
        int falseSupportedCount = 0;
        int falseUnknownCount = 0;
        int totalContradictedExpected = 0;
        // verificationCoverage: (supported + contradicted) / totalClaims
        int supportedOrContradicted = 0;
        int totalClaims = results.size();
        // unknownRate and outOfScopeRate
        int unknownCount = 0;
        int outOfScopeCount = 0;

        for (BenchmarkResultLine line : results) {
            // Skip edgeCase and reviewStatus: pending from primary metrics
            if (line.edgeCase() || "pending".equals(line.reviewStatus().orElse(null))) {
                // These lines still count toward totalClaims and unknownRate/outOfScopeRate
                if (line.actualVerdict() == Verdict.UNKNOWN) unknownCount++;
                if (line.actualVerdict() == Verdict.OUT_OF_SCOPE) outOfScopeCount++;
                if (line.actualVerdict() == Verdict.SUPPORTED || line.actualVerdict() == Verdict.CONTRADICTED) {
                    supportedOrContradicted++;
                }
                continue;
            }

            // Increment confusion matrix
            matrix.get(line.expectedVerdict()).merge(line.actualVerdict(), 1, Integer::sum);

            totalEvaluated++;
            if (line.verdictMatch()) correctVerdicts++;

            // verificationCoverage numerator
            if (line.actualVerdict() == Verdict.SUPPORTED || line.actualVerdict() == Verdict.CONTRADICTED) {
                supportedOrContradicted++;
            }
            // unknownRate / outOfScopeRate counters
            if (line.actualVerdict() == Verdict.UNKNOWN) unknownCount++;
            if (line.actualVerdict() == Verdict.OUT_OF_SCOPE) outOfScopeCount++;

            // False support: expected=contradicted, actual=supported
            if (line.expectedVerdict() == Verdict.CONTRADICTED) {
                totalContradictedExpected++;
                if (line.actualVerdict() == Verdict.SUPPORTED) falseSupportedCount++;
                if (line.actualVerdict() == Verdict.UNKNOWN) falseUnknownCount++;
            }
        }

        // Compute metrics
        double accuracy = totalEvaluated > 0 ? (double) correctVerdicts / totalEvaluated : 0.0;
        double falseSupportRate = totalContradictedExpected > 0
            ? (double) falseSupportedCount / totalContradictedExpected : 0.0;
        double unresolvedRate = totalContradictedExpected > 0
            ? (double) falseUnknownCount / totalContradictedExpected : 0.0;
        // verificationCoverage per spec: (supported + contradicted) / totalClaims
        double verificationCoverage = totalClaims > 0
            ? (double) supportedOrContradicted / totalClaims : 0.0;
        double unknownRate = totalClaims > 0 ? (double) unknownCount / totalClaims : 0.0;
        double outOfScopeRate = totalClaims > 0 ? (double) outOfScopeCount / totalClaims : 0.0;

        // Convert matrix to nested Map
        Map<Verdict, Map<Verdict, Integer>> matrixMap = new EnumMap<>(Verdict.class);
        for (Map.Entry<Verdict, EnumMap<Verdict, Integer>> entry : matrix.entrySet()) {
            matrixMap.put(entry.getKey(), new EnumMap<>(entry.getValue()));
        }

        return new QaEvaluation(
            accuracy,
            falseSupportRate,
            falseSupportedCount,
            unresolvedRate,
            falseUnknownCount,
            verificationCoverage,
            unknownRate,
            outOfScopeRate,
            new ConfusionMatrix(matrixMap)
        );
    }

    private ConfusionMatrix buildEmptyMatrix() {
        Map<Verdict, Map<Verdict, Integer>> matrixMap = new EnumMap<>(Verdict.class);
        for (Verdict expected : Verdict.values()) {
            EnumMap<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict actual : Verdict.values()) {
                row.put(actual, 0);
            }
            matrixMap.put(expected, new EnumMap<>(row));
        }
        return new ConfusionMatrix(matrixMap);
    }
}