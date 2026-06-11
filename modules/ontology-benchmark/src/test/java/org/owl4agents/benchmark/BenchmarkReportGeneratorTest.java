package org.owl4agents.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.model.Verdict;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9.2 unit tests: BenchmarkReportGenerator produces Markdown and JSON
 * reports with all required sections and fields.
 */
@DisplayName("BenchmarkReportGenerator unit tests")
class BenchmarkReportGeneratorTest {

    private BenchmarkReportGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new BenchmarkReportGenerator();
    }

    /** Minimal config for testing. */
    private ExperimentConfig singleReasonerConfig() {
        return new ExperimentConfig(
            "pizza-small",
            "Pizza small benchmark",
            List.of("pizza"),
            "test/fixtures/v0.6/question-sets/pizza-50.jsonl",
            List.of("hermit"),
            "test/fixtures/v0.6/results/pizza-small-results.jsonl",
            30,
            false,
            ExperimentConfig.EdgeCasePolicy.exclude,
            OptionalInt.empty(),
            ExperimentConfig.ReportFormat.markdown,
            Optional.empty()
        );
    }

    /** Multi-reasoner config for testing. */
    private ExperimentConfig multiReasonerConfig() {
        return new ExperimentConfig(
            "pizza-comparison",
            "Pizza reasoner comparison",
            List.of("pizza"),
            "test/fixtures/v0.6/question-sets/pizza-50.jsonl",
            List.of("hermit", "elk"),
            "test/fixtures/v0.6/results/pizza-comparison-results.jsonl",
            30,
            false,
            ExperimentConfig.EdgeCasePolicy.exclude,
            OptionalInt.empty(),
            ExperimentConfig.ReportFormat.both,
            Optional.empty()
        );
    }

    /** Minimal test results. */
    private List<BenchmarkResultLine> singleReasonerResults() {
        return List.of(
            new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 60, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.CONTRADICTED, false, 80, Optional.of("approved"), Optional.empty(), false)
        );
    }

    /** Multi-reasoner test results (disagreement). */
    private List<BenchmarkResultLine> multiReasonerResults() {
        return List.of(
            new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q1", "pizza", "elk", 1,
                Verdict.CONTRADICTED, Verdict.SUPPORTED, false, 55, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 60, Optional.of("approved"), Optional.empty(), false),
            new BenchmarkResultLine("q2", "pizza", "elk", 1,
                Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 65, Optional.of("approved"), Optional.empty(), false)
        );
    }

    private BenchmarkResultSummary summary(int total, double accuracy) {
        Map<Verdict, Integer> perVerdictCounts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) perVerdictCounts.put(v, 0);
        perVerdictCounts.put(Verdict.SUPPORTED, 2);
        perVerdictCounts.put(Verdict.CONTRADICTED, 1);

        Map<String, Long> perReasonerTiming = new LinkedHashMap<>();
        perReasonerTiming.put("hermit", 190L);

        return new BenchmarkResultSummary(BenchmarkResultSummary.TYPE, total, accuracy,
            0.5, 1, 0.333, 1, 1.0, 0.0, 0.333, perVerdictCounts, perReasonerTiming);
    }

    private BenchmarkResultSummary multiSummary() {
        Map<Verdict, Integer> perVerdictCounts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) perVerdictCounts.put(v, 0);
        perVerdictCounts.put(Verdict.SUPPORTED, 2);
        perVerdictCounts.put(Verdict.CONTRADICTED, 2);

        Map<String, Long> perReasonerTiming = new LinkedHashMap<>();
        perReasonerTiming.put("hermit", 110L);
        perReasonerTiming.put("elk", 120L);

        return new BenchmarkResultSummary(BenchmarkResultSummary.TYPE, 4, 0.75,
            0.0, 0, 0.0, 0, 1.0, 0.0, 0.0, perVerdictCounts, perReasonerTiming);
    }

    /** Minimal confusion matrix. */
    private ConfusionMatrix testConfusionMatrix() {
        Map<Verdict, Map<Verdict, Integer>> matrix = new EnumMap<>(Verdict.class);
        for (Verdict expected : Verdict.values()) {
            Map<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict actual : Verdict.values()) row.put(actual, 0);
            matrix.put(expected, row);
        }
        matrix.get(Verdict.SUPPORTED).put(Verdict.SUPPORTED, 1);
        matrix.get(Verdict.SUPPORTED).put(Verdict.CONTRADICTED, 1);
        matrix.get(Verdict.CONTRADICTED).put(Verdict.CONTRADICTED, 1);
        return new ConfusionMatrix(matrix);
    }

    // ── Markdown report tests ──

    @Nested
    @DisplayName("Markdown report contains required sections")
    class MarkdownReportTests {

        @Test
        @DisplayName("Markdown contains False Support Rate as separate section")
        void markdownHasSeparateFalseSupportRateSection() {
            String report = generator.generateMarkdownReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(report.contains("## False Support Rate"),
                "Markdown must have separate False Support Rate section");
            assertTrue(report.contains("## Unresolved Rate"),
                "Markdown must have separate Unresolved Rate section");
            // NOT combined
            assertFalse(report.contains("## Hallucination Rate"),
                "False support and unresolved must NOT be combined as hallucination rate");
        }

        @Test
        @DisplayName("Markdown contains confusion matrix as labeled table")
        void markdownHasConfusionMatrixTable() {
            String report = generator.generateMarkdownReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(report.contains("## Confusion Matrix"),
                "Markdown must have confusion matrix section");
            assertTrue(report.contains("| Expected |"),
                "Confusion matrix must have labeled table header");
            assertTrue(report.contains("out_of_scope"),
                "Matrix table must include verdict labels");
        }

        @Test
        @DisplayName("Markdown has reproducibility metadata with static reasoner versions")
        void markdownReproducibilityMetadata() {
            String report = generator.generateMarkdownReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(report.contains("## Reproducibility"),
                "Markdown must have reproducibility section");
            assertTrue(report.contains("owl4agents version"),
                "Reproducibility must include owl4agents version");
            assertTrue(report.contains("Java version"),
                "Reproducibility must include Java version");
            assertTrue(report.contains("OS"),
                "Reproducibility must include OS");
            assertTrue(report.contains("hermit version"),
                "Reproducibility must include hermit version from REASONER_VERSIONS");
            assertTrue(report.contains("1.4.5.519"),
                "Static hermit version must be 1.4.5.519");
            assertTrue(report.contains("elk version"),
                "Reproducibility must include elk version from REASONER_VERSIONS");
            assertTrue(report.contains("0.6.0"),
                "Static elk version must be 0.6.0");
            assertTrue(report.contains("openllet version"),
                "Reproducibility must include openllet version from REASONER_VERSIONS");
            assertTrue(report.contains("2.6.5"),
                "Static openllet version must be 2.6.5");
        }

        @Test
        @DisplayName("Single reasoner: no reasoner comparison section")
        void singleReasonerNoComparison() {
            String report = generator.generateMarkdownReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertFalse(report.contains("## Reasoner Comparison"),
                "Single reasoner must NOT have Reasoner Comparison section");
        }

        @Test
        @DisplayName("Multi-reasoner: has reasoner comparison with timing and versions")
        void multiReasonerHasComparison() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiSummary(), testConfusionMatrix());

            assertTrue(report.contains("## Reasoner Comparison"),
                "Multi-reasoner must have Reasoner Comparison section");
            assertTrue(report.contains("hermit"),
                "Comparison must include hermit");
            assertTrue(report.contains("elk"),
                "Comparison must include elk");
            assertTrue(report.contains("1.4.5.519"),
                "Comparison must include hermit version from REASONER_VERSIONS");
            assertTrue(report.contains("0.6.0"),
                "Comparison must include elk version from REASONER_VERSIONS");
        }

        @Test
        @DisplayName("Multi-reasoner disagreement: reasoner_difference classification")
        void multiReasonerDisagreementClassification() {
            String report = generator.generateMarkdownReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiSummary(), testConfusionMatrix());

            // q1: hermit=supported (matches expected), elk=contradicted (doesn't match)
            // → at least one matches expected → "reasoner_difference"
            assertTrue(report.contains("q1"),
                "Disagreement must list question q1");
            assertTrue(report.contains("reasoner_difference"),
                "At least one matches expected → reasoner_difference");
        }

        @Test
        @DisplayName("Per-question verdicts have verdictMatch as field")
        void perQuestionVerdictsTable() {
            String report = generator.generateMarkdownReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(report.contains("## Per-Question Verdicts"),
                "Markdown must have per-question verdicts section");
            assertTrue(report.contains("| Match |"),
                "Table must have Match column");
        }
    }

    // ── JSON report tests ──

    @Nested
    @DisplayName("JSON report contains required fields")
    class JsonReportTests {

        @Test
        @DisplayName("JSON contains all required metric fields")
        void jsonRequiredFields() {
            String json = generator.generateJsonReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(json.contains("\"experimentName\""),
                "JSON must have experimentName");
            assertTrue(json.contains("\"description\""),
                "JSON must have description");
            assertTrue(json.contains("\"accuracy\""),
                "JSON must have accuracy");
            assertTrue(json.contains("\"falseSupportRate\""),
                "JSON must have falseSupportRate as separate field");
            assertTrue(json.contains("\"unresolvedRate\""),
                "JSON must have unresolvedRate as separate field");
            assertTrue(json.contains("\"falseSupportedCount\""),
                "JSON must have falseSupportedCount");
            assertTrue(json.contains("\"falseUnknownCount\""),
                "JSON must have falseUnknownCount");
            assertTrue(json.contains("\"verificationCoverage\""),
                "JSON must have verificationCoverage");
        }

        @Test
        @DisplayName("JSON contains 4x4 confusion matrix as nested object")
        void jsonConfusionMatrix() {
            String json = generator.generateJsonReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(json.contains("\"confusionMatrix\""),
                "JSON must have confusionMatrix");
            assertTrue(json.contains("\"supported\""),
                "Matrix must have supported verdict label");
            assertTrue(json.contains("\"contradicted\""),
                "Matrix must have contradicted verdict label");
            assertTrue(json.contains("\"out_of_scope\""),
                "Matrix must have out_of_scope verdict label");
            assertTrue(json.contains("\"unknown\""),
                "Matrix must have unknown verdict label");
        }

        @Test
        @DisplayName("verdictMatch is JSON boolean true/false in per-question results")
        void verdictMatchIsBoolean() {
            String json = generator.generateJsonReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            // JSON boolean: true (not "true" string)
            assertTrue(json.contains("\"match\": true"),
                "verdictMatch must be JSON boolean true, NOT string \"true\"");
            assertTrue(json.contains("\"match\": false"),
                "verdictMatch must be JSON boolean false, NOT string \"false\"");
            assertFalse(json.contains("\"match\": \"true\""),
                "verdictMatch must NOT be a string \"true\"");
            assertFalse(json.contains("\"match\": \"false\""),
                "verdictMatch must NOT be a string \"false\"");
        }

        @Test
        @DisplayName("JSON has reproducibility metadata with static reasoner versions")
        void jsonReproducibility() {
            String json = generator.generateJsonReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());

            assertTrue(json.contains("\"reproducibility\""),
                "JSON must have reproducibility section");
            assertTrue(json.contains("\"owl4agentsVersion\""),
                "Must include owl4agentsVersion");
            assertTrue(json.contains("\"javaVersion\""),
                "Must include javaVersion");
            assertTrue(json.contains("\"reasonerVersions\""),
                "Must include reasonerVersions");
            assertTrue(json.contains("\"hermit\""),
                "Must include hermit in reasoner versions");
            assertTrue(json.contains("\"1.4.5.519\""),
                "Static hermit version must be 1.4.5.519");
        }

        @Test
        @DisplayName("Multi-reasoner: has reasonerComparison; single: absent")
        void reasonerComparisonPresenceRules() {
            // Multi-reasoner
            String multiJson = generator.generateJsonReport(
                multiReasonerConfig(), multiReasonerResults(),
                multiSummary(), testConfusionMatrix());
            assertTrue(multiJson.contains("\"reasonerComparison\""),
                "Multi-reasoner must have reasonerComparison");

            // Single-reasoner
            String singleJson = generator.generateJsonReport(
                singleReasonerConfig(), singleReasonerResults(),
                summary(3, 0.667), testConfusionMatrix());
            assertFalse(singleJson.contains("\"reasonerComparison\""),
                "Single-reasoner must NOT have reasonerComparison");
        }
    }

    // ── Static version mapping tests ──

    @Nested
    @DisplayName("Static reasoner version mapping")
    class ReasonerVersionTests {

        @Test
        @DisplayName("REASONER_VERSIONS contains all three reasoner mappings")
        void staticVersionsComplete() {
            Map<String, String> versions = BenchmarkReportGenerator.REASONER_VERSIONS;
            assertEquals(3, versions.size(), "Must have 3 reasoner version entries");
            assertEquals("1.4.5.519", versions.get("hermit"));
            assertEquals("0.6.0", versions.get("elk"));
            assertEquals("2.6.5", versions.get("openllet"));
        }
    }
}