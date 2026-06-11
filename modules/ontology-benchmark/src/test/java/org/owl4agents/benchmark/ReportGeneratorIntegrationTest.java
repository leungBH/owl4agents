package org.owl4agents.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.model.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9.3 integration test: V06-RC-001
 * Pizza-small with hermit+elk → reasoner comparison with timing delta,
 * static version mapping, 4x4 confusion matrix.
 *
 * Tests the full report generation pipeline using synthetic benchmark
 * results with multi-reasoner data, verifying:
 * - Reasoner comparison section present with classification rules
 * - Timing delta between reasoners
 * - Static REASONER_VERSIONS mapping in reproducibility
 * - 4x4 confusion matrix in both Markdown and JSON
 */
@DisplayName("V06-RC-001: Report generation with multi-reasoner data")
class ReportGeneratorIntegrationTest {

    @TempDir
    Path tempDir;

    private BenchmarkReportGenerator generator = new BenchmarkReportGenerator();

    /** Multi-reasoner config matching pizza-small.yaml but with hermit+elk. */
    private ExperimentConfig multiReasonerConfig() {
        return new ExperimentConfig(
            "pizza-small",
            "Pizza ontology benchmark with hermit+elk — CI smoke test",
            List.of("pizza"),
            "test/fixtures/v0.6/question-sets/pizza-50.jsonl",
            List.of("hermit", "elk"),
            tempDir.resolve("results.jsonl").toString(),
            30,
            false,
            ExperimentConfig.EdgeCasePolicy.exclude,
            OptionalInt.empty(),
            ExperimentConfig.ReportFormat.both,
            Optional.empty()
        );
    }

    /** Synthetic multi-reasoner results simulating pizza-small run. */
    private List<BenchmarkResultLine> multiReasonerResults() {
        return List.of(
            // q1: both agree, both match expected → no disagreement
            new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true, 45, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q1", "pizza", "elk", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true, 38, Optional.of("approved"), Optional.empty(), false),
            // q2: both agree, both match expected
            new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 55, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q2", "pizza", "elk", 1,
                Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 42, Optional.of("approved"), Optional.empty(), false),
            // q3: disagreement — hermit=supported (matches), elk=contradicted (doesn't match) → reasoner_difference
            new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true, 60, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q3", "pizza", "elk", 1,
                Verdict.CONTRADICTED, Verdict.SUPPORTED, false, 50, Optional.of("approved"), Optional.empty(), false),
            // q4: disagreement — both wrong vs expected → "both_wrong" classification
            new BenchmarkResultLine("q4", "pizza", "hermit", 1,
                Verdict.UNKNOWN, Verdict.CONTRADICTED, false, 70, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q4", "pizza", "elk", 1,
                Verdict.OUT_OF_SCOPE, Verdict.CONTRADICTED, false, 65, Optional.of("approved"), Optional.empty(), false),
            // q5: out_of_scope, both match
            new BenchmarkResultLine("q5", "pizza", "hermit", 1,
                Verdict.OUT_OF_SCOPE, Verdict.OUT_OF_SCOPE, true, 40, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q5", "pizza", "elk", 1,
                Verdict.OUT_OF_SCOPE, Verdict.OUT_OF_SCOPE, true, 35, Optional.of("approved"), Optional.empty(), false)
        );
    }

    private BenchmarkResultSummary multiReasonerSummary() {
        Map<Verdict, Integer> perVerdictCounts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) perVerdictCounts.put(v, 0);
        perVerdictCounts.put(Verdict.SUPPORTED, 3);
        perVerdictCounts.put(Verdict.CONTRADICTED, 3);
        perVerdictCounts.put(Verdict.UNKNOWN, 1);
        perVerdictCounts.put(Verdict.OUT_OF_SCOPE, 3);

        Map<String, Long> perReasonerTiming = new LinkedHashMap<>();
        perReasonerTiming.put("hermit", 270L);
        perReasonerTiming.put("elk", 230L);

        return new BenchmarkResultSummary(BenchmarkResultSummary.TYPE, 10, 0.6,
            0.0, 0, 0.0, 0, 1.0, 0.1, 0.3, perVerdictCounts, perReasonerTiming);
    }

    private ConfusionMatrix multiConfusionMatrix() {
        Map<Verdict, Map<Verdict, Integer>> matrix = new EnumMap<>(Verdict.class);
        for (Verdict expected : Verdict.values()) {
            Map<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict actual : Verdict.values()) row.put(actual, 0);
            matrix.put(expected, row);
        }
        // supported: 2 hermit-supported, 1 elk-contradicted
        matrix.get(Verdict.SUPPORTED).put(Verdict.SUPPORTED, 2);
        matrix.get(Verdict.SUPPORTED).put(Verdict.CONTRADICTED, 1);
        // contradicted: 2 correct, plus q4 hermit-unknown
        matrix.get(Verdict.CONTRADICTED).put(Verdict.CONTRADICTED, 2);
        matrix.get(Verdict.CONTRADICTED).put(Verdict.UNKNOWN, 1);
        // out_of_scope: 2 correct, plus q4 elk-out_of_scope
        matrix.get(Verdict.OUT_OF_SCOPE).put(Verdict.OUT_OF_SCOPE, 2);
        matrix.get(Verdict.OUT_OF_SCOPE).put(Verdict.OUT_OF_SCOPE, 1);
        return new ConfusionMatrix(matrix);
    }

    // ── Markdown report ──

    @Nested
    @DisplayName("Markdown report: multi-reasoner comparison")
    class MarkdownReportTests {

        @Test
        @DisplayName("Reasoner comparison section present with timing delta")
        void reasonerComparisonWithTiming() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(report.contains("## Reasoner Comparison"),
                "Multi-reasoner must have comparison section");

            // Timing delta: hermit total 270ms, elk total 230ms → delta 40ms
            assertTrue(report.contains("hermit"),
                "Must include hermit timing");
            assertTrue(report.contains("elk"),
                "Must include elk timing");
        }

        @Test
        @DisplayName("Reasoner disagreement: reasoner_difference classification")
        void reasonerDifferenceClassification() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            // q3: hermit=supported (matches expected), elk=contradicted (doesn't match)
            // → at least one matches → "reasoner_difference"
            assertTrue(report.contains("reasoner_difference"),
                "At least one matches expected → reasoner_difference classification");
        }

        @Test
        @DisplayName("Static reasoner version mapping in reproducibility")
        void staticVersionMapping() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(report.contains("## Reproducibility"),
                "Must have reproducibility section");
            assertTrue(report.contains("1.4.5.519"),
                "Static hermit version must be present");
            assertTrue(report.contains("0.6.0"),
                "Static elk version must be present");
            assertTrue(report.contains("2.6.5"),
                "Static openllet version must be present (even when not used)");
        }

        @Test
        @DisplayName("4x4 confusion matrix as labeled table")
        void confusionMatrixLabeledTable() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(report.contains("## Confusion Matrix"),
                "Must have confusion matrix section");
            assertTrue(report.contains("| Expected |"),
                "Matrix must have labeled table");
            // All 4 verdict labels
            assertTrue(report.contains("supported"),
                "Matrix must include supported label");
            assertTrue(report.contains("contradicted"),
                "Matrix must include contradicted label");
            assertTrue(report.contains("unknown"),
                "Matrix must include unknown label");
            assertTrue(report.contains("out_of_scope"),
                "Matrix must include out_of_scope label");
        }
    }

    // ── JSON report ──

    @Nested
    @DisplayName("JSON report: multi-reasoner comparison")
    class JsonReportTests {

        @Test
        @DisplayName("JSON has reasonerComparison with timing and disagreement data")
        void jsonReasonerComparison() {
            String json = generator.generateJsonReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(json.contains("\"reasonerComparison\""),
                "Multi-reasoner JSON must have reasonerComparison");
            assertTrue(json.contains("\"hermit\""),
                "Comparison must include hermit");
            assertTrue(json.contains("\"elk\""),
                "Comparison must include elk");
        }

        @Test
        @DisplayName("JSON has 4x4 confusion matrix as nested object")
        void jsonConfusionMatrix() {
            String json = generator.generateJsonReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(json.contains("\"confusionMatrix\""),
                "JSON must have confusionMatrix");
            assertTrue(json.contains("\"supported\""),
                "Matrix must have supported label");
            assertTrue(json.contains("\"contradicted\""),
                "Matrix must have contradicted label");
            assertTrue(json.contains("\"unknown\""),
                "Matrix must have unknown label");
            assertTrue(json.contains("\"out_of_scope\""),
                "Matrix must have out_of_scope label");
        }

        @Test
        @DisplayName("JSON reproducibility has static reasoner versions")
        void jsonReproducibilityWithVersions() {
            String json = generator.generateJsonReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiReasonerSummary(), multiConfusionMatrix());

            assertTrue(json.contains("\"reproducibility\""),
                "JSON must have reproducibility");
            assertTrue(json.contains("\"reasonerVersions\""),
                "Must include reasonerVersions");
            assertTrue(json.contains("\"1.4.5.519\""),
                "Static hermit version present");
            assertTrue(json.contains("\"0.6.0\""),
                "Static elk version present");
        }
    }
}