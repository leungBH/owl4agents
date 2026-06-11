package org.owl4agents.benchmark;

import java.util.Map;

import org.owl4agents.core.model.Verdict;

/**
 * 4x4 expected-vs-actual verdict confusion matrix.
 * Rows (expected) and columns (actual) for supported/contradicted/unknown/out_of_scope.
 * JSON representation uses nested objects keyed by expected verdict.
 */
public record ConfusionMatrix(
    Map<Verdict, Map<Verdict, Integer>> matrix
) {

    /** Get count for a specific expected-vs-actual pair. */
    public int getCount(Verdict expected, Verdict actual) {
        Map<Verdict, Integer> row = matrix.get(expected);
        if (row == null) return 0;
        Integer count = row.get(actual);
        return count != null ? count : 0;
    }
}