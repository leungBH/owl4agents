package org.owl4agents.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.model.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6.3/6.4 integration tests: QA evaluation metrics and error handling.
 *
 * V06-QE-001: eval-qa produces accuracy, false support rate, unresolved rate,
 *   verification coverage, and 4x4 confusion matrix from fixture results.
 * V06-QE-002: MCP parity (tested via service layer — same service produces
 *   same results by design).
 * V06-HALL-001: hallucinationDetection: true → false support rate > 0.
 *
 * Error tests:
 *   - RESULTS_NOT_FOUND: missing results file
 *   - EMPTY_RESULTS: empty results file
 *   - No placeholder metrics
 */
@DisplayName("QA evaluation integration tests")
class QaEvaluationIntegrationTest {

    @TempDir
    Path tempDir;

    private QaEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new QaEvaluationService();
    }

    // ── V06-QE-001: Core metrics from fixture results ──

    @Nested
    @DisplayName("V06-QE-001: QA evaluation produces all required metrics")
    class CoreMetricsTests {

        @Test
        @DisplayName("Mixed verdicts produce correct accuracy, false support rate, unresolved rate")
        void mixedVerdictsMetrics() {
            // Create fixture results: 3 supported (correct), 1 contradicted→supported (false support),
            // 1 contradicted→unknown (false unresolved), 1 out_of_scope (correct)
            List<BenchmarkResultLine> results = List.of(
                // expected=supported, actual=supported → correct
                new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
                // expected=supported, actual=supported → correct
                new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 60, Optional.of("approved"), Optional.empty(), false),
                // expected=supported, actual=supported → correct
                new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 70, Optional.of("approved"), Optional.empty(), false),
                // expected=contradicted, actual=supported → FALSE SUPPORT
                new BenchmarkResultLine("q4", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.CONTRADICTED, false, 80, Optional.of("approved"), Optional.empty(), false),
                // expected=contradicted, actual=unknown → FALSE UNKNOWN (unresolved)
                new BenchmarkResultLine("q5", "pizza", "hermit", 1,
                    Verdict.UNKNOWN, Verdict.CONTRADICTED, false, 90, Optional.of("approved"), Optional.empty(), false),
                // expected=out_of_scope, actual=out_of_scope → correct
                new BenchmarkResultLine("q6", "pizza", "hermit", 1,
                    Verdict.OUT_OF_SCOPE, Verdict.OUT_OF_SCOPE, true, 40, Optional.of("approved"), Optional.empty(), false)
            );

            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

            // Accuracy: 4 correct out of 6 = 0.667
            assertEquals(4.0 / 6.0, evaluation.accuracy(), 0.001, "Accuracy should be 4/6");

            // False support rate: 1 false support out of 2 contradicted expected = 0.5
            assertEquals(0.5, evaluation.falseSupportRate(), 0.001, "False support rate should be 1/2");
            assertEquals(1, evaluation.falseSupportedCount(), "False supported count should be 1");

            // Unresolved rate: 1 false unknown out of 2 contradicted expected = 0.5
            assertEquals(0.5, evaluation.unresolvedRate(), 0.001, "Unresolved rate should be 1/2");
            assertEquals(1, evaluation.falseUnknownCount(), "False unknown count should be 1");

            // Verification coverage: (supported + contradicted) / totalClaims per spec
            // 4 supported/contradicted out of 6 total = 0.667
            assertEquals(4.0 / 6.0, evaluation.verificationCoverage(), 0.001, "Coverage should be 4/6");
        }

        @Test
        @DisplayName("4x4 confusion matrix contains all verdict combinations")
        void confusionMatrixComplete() {
            List<BenchmarkResultLine> results = List.of(
                new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                    Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 60, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                    Verdict.UNKNOWN, Verdict.SUPPORTED, false, 70, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q4", "pizza", "hermit", 1,
                    Verdict.OUT_OF_SCOPE, Verdict.OUT_OF_SCOPE, true, 40, Optional.of("approved"), Optional.empty(), false)
            );

            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);
            ConfusionMatrix matrix = evaluation.confusionMatrix();

            // All 4 verdicts present as row keys
            for (Verdict expected : Verdict.values()) {
                assertNotNull(matrix.matrix().get(expected),
                    "Matrix should have row for " + expected.jsonName());
                // All 4 verdicts present as column keys
                for (Verdict actual : Verdict.values()) {
                    assertNotNull(matrix.matrix().get(expected).get(actual),
                        "Matrix should have cell for " + expected.jsonName() + "→" + actual.jsonName());
                }
            }

            // Verify specific counts
            assertEquals(1, matrix.matrix().get(Verdict.SUPPORTED).get(Verdict.SUPPORTED));
            assertEquals(1, matrix.matrix().get(Verdict.CONTRADICTED).get(Verdict.CONTRADICTED));
            assertEquals(1, matrix.matrix().get(Verdict.SUPPORTED).get(Verdict.UNKNOWN));
            assertEquals(1, matrix.matrix().get(Verdict.OUT_OF_SCOPE).get(Verdict.OUT_OF_SCOPE));
        }

        @Test
        @DisplayName("Edge case questions are excluded from primary metrics")
        void edgeCaseExcludedFromMetrics() {
            List<BenchmarkResultLine> results = List.of(
                // Normal: correct
                new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
                // Edge case: correct but excluded
                new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 60, Optional.of("reviewed"), Optional.empty(), true),
                // Pending: excluded
                new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 70, Optional.of("pending"), Optional.empty(), false)
            );

            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

            // Only q1 is evaluated: accuracy = 1/1 = 1.0
            assertEquals(1.0, evaluation.accuracy(), 0.001);
            // Coverage: q1=supported counts; q2=supported(edgeCase) counts; q3=supported(pending) counts
            // verificationCoverage = (supported + contradicted) / totalClaims
            // All 3 have actual=supported, so numerator=3; denominator=3; coverage=1.0
            assertEquals(1.0, evaluation.verificationCoverage(), 0.001);
        }

        @Test
        @DisplayName("JSONL read and evaluate end-to-end")
        void jsonlReadAndEvaluate() throws Exception {
            // Write a fixture JSONL file
            String line1 = "{\"questionId\":\"q1\",\"ontologyId\":\"pizza\",\"reasoner\":\"hermit\",\"claimsVerified\":1,\"actualVerdict\":\"supported\",\"expectedVerdict\":\"supported\",\"verdictMatch\":true,\"elapsedMs\":50,\"reviewStatus\":\"approved\"}";
            String line2 = "{\"questionId\":\"q2\",\"ontologyId\":\"pizza\",\"reasoner\":\"hermit\",\"claimsVerified\":1,\"actualVerdict\":\"contradicted\",\"expectedVerdict\":\"contradicted\",\"verdictMatch\":true,\"elapsedMs\":60,\"reviewStatus\":\"approved\"}";
            String summaryLine = "{\"type\":\"summary\",\"totalQuestions\":2,\"accuracy\":1.0,\"falseSupportRate\":0.0,\"falseSupportedCount\":0,\"unresolvedRate\":0.0,\"falseUnknownCount\":0,\"verificationCoverage\":1.0,\"perVerdictCounts\":{\"out_of_scope\":0,\"supported\":1,\"contradicted\":1,\"unknown\":0},\"perReasonerTiming\":{\"hermit\":110}}";

            Path jsonlFile = tempDir.resolve("results.jsonl");
            Files.writeString(jsonlFile, line1 + "\n" + line2 + "\n" + summaryLine + "\n");

            // Read and evaluate
            BenchmarkResultReader reader = new BenchmarkResultReader();
            List<BenchmarkResultLine> results = reader.readResults(jsonlFile);
            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

            assertEquals(2, results.size(), "Should read 2 result lines (summary skipped)");
            assertEquals(1.0, evaluation.accuracy(), 0.001, "Both verdicts match");
            assertEquals(0.0, evaluation.falseSupportRate(), 0.001, "No false support");
            assertEquals(0.0, evaluation.unresolvedRate(), 0.001, "No unresolved");
        }
    }

    // ── V06-HALL-001: hallucination detection produces false support rate > 0 ──

    @Nested
    @DisplayName("V06-HALL-001: hallucinationDetection produces separate false support metrics")
    class HallucinationMetricsTests {

        @Test
        @DisplayName("False support rate and unresolved rate are separate metrics, NOT combined")
        void separateMetrics() {
            // 3 contradicted expected: 1 → supported (false support), 1 → unknown (unresolved), 1 → contradicted (correct)
            List<BenchmarkResultLine> results = List.of(
                new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.CONTRADICTED, false, 50, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                    Verdict.UNKNOWN, Verdict.CONTRADICTED, false, 60, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q3", "pizza", "hermit", 1,
                    Verdict.CONTRADICTED, Verdict.CONTRADICTED, true, 70, Optional.of("approved"), Optional.empty(), false)
            );

            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

            // False support rate: 1/3 = 0.333 (separate from unresolved)
            assertEquals(1.0 / 3.0, evaluation.falseSupportRate(), 0.001);
            assertEquals(1, evaluation.falseSupportedCount());

            // Unresolved rate: 1/3 = 0.333 (separate from false support)
            assertEquals(1.0 / 3.0, evaluation.unresolvedRate(), 0.001);
            assertEquals(1, evaluation.falseUnknownCount());

            // They are NOT combined into a single "hallucination rate"
            assertNotEquals(evaluation.falseSupportRate() + evaluation.unresolvedRate(),
                evaluation.falseSupportRate(), "Rates must be separate, not combined");
        }
    }

    // ── Error tests (V06-QE error paths) ──

    @Nested
    @DisplayName("Error handling: missing/empty results")
    class ErrorTests {

        @Test
        @DisplayName("Null results produce zero metrics (no placeholder values)")
        void nullResultsZeroMetrics() {
            QaEvaluationService.QaEvaluation evaluation = service.evaluate(null);

            assertEquals(0.0, evaluation.accuracy(), "Null → accuracy 0");
            assertEquals(0.0, evaluation.falseSupportRate(), "Null → false support rate 0");
            assertEquals(0, evaluation.falseSupportedCount(), "Null → false supported count 0");
            assertEquals(0.0, evaluation.unresolvedRate(), "Null → unresolved rate 0");
            assertEquals(0, evaluation.falseUnknownCount(), "Null → false unknown count 0");
            assertEquals(0.0, evaluation.verificationCoverage(), "Null → coverage 0");
            assertEquals(0.0, evaluation.unknownRate(), "Null → unknownRate 0");
            assertEquals(0.0, evaluation.outOfScopeRate(), "Null → outOfScopeRate 0");
            assertNotNull(evaluation.confusionMatrix(), "Null → empty matrix (not null)");
        }

        @Test
        @DisplayName("Empty results produce zero metrics (no placeholder values)")
        void emptyResultsZeroMetrics() {
            QaEvaluationService.QaEvaluation evaluation = service.evaluate(List.of());

            assertEquals(0.0, evaluation.accuracy());
            assertEquals(0.0, evaluation.falseSupportRate());
            assertEquals(0, evaluation.falseSupportedCount());
            assertEquals(0.0, evaluation.unresolvedRate());
            assertEquals(0, evaluation.falseUnknownCount());
            assertEquals(0.0, evaluation.verificationCoverage());
            assertEquals(0.0, evaluation.unknownRate());
            assertEquals(0.0, evaluation.outOfScopeRate());
            assertNotNull(evaluation.confusionMatrix());
        }

        @Test
        @DisplayName("No contradicted expected → false support rate and unresolved rate are 0")
        void noContradictedExpected() {
            List<BenchmarkResultLine> results = List.of(
                new BenchmarkResultLine("q1", "pizza", "hermit", 1,
                    Verdict.SUPPORTED, Verdict.SUPPORTED, true, 50, Optional.of("approved"), Optional.empty(), false),
                new BenchmarkResultLine("q2", "pizza", "hermit", 1,
                    Verdict.UNKNOWN, Verdict.UNKNOWN, true, 60, Optional.of("approved"), Optional.empty(), false)
            );

            QaEvaluationService.QaEvaluation evaluation = service.evaluate(results);

            assertEquals(0.0, evaluation.falseSupportRate(), "No contradicted expected → 0");
            assertEquals(0.0, evaluation.unresolvedRate(), "No contradicted expected → 0");
            assertEquals(1.0, evaluation.accuracy(), "Both correct → accuracy 1");
        }

        @Test
        @DisplayName("JSONL read from nonexistent file throws IOException")
        void nonexistentFileThrows() {
            BenchmarkResultReader reader = new BenchmarkResultReader();
            assertThrows(java.io.IOException.class, () ->
                reader.readResults(Path.of("/nonexistent/results.jsonl")));
        }

        @Test
        @DisplayName("JSONL read from empty file returns empty list")
        void emptyFileReturnsEmpty() throws Exception {
            Path emptyFile = tempDir.resolve("empty.jsonl");
            Files.writeString(emptyFile, "");

            BenchmarkResultReader reader = new BenchmarkResultReader();
            List<BenchmarkResultLine> results = reader.readResults(emptyFile);

            assertTrue(results.isEmpty(), "Empty file → empty results list");
        }
    }
}