package org.owl4agents.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.validation.ClaimWorkflowService;

/**
 * C1 coverage: direct unit tests for BenchmarkService.run() with mocked
 * ClaimWorkflowService. Verifies verdict mapping, edge-case exclusion,
 * timeout detection, and I/O error handling.
 */
@DisplayName("BenchmarkService.run() unit tests")
class BenchmarkServiceTest {

    private ClaimWorkflowService mockWorkflow;
    private BenchmarkQuestionSetValidator validator;
    private BenchmarkService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockWorkflow = mock(ClaimWorkflowService.class);
        validator = new BenchmarkQuestionSetValidator();
        service = new BenchmarkService(mockWorkflow, validator);
    }

    // ── JSONL fixtures ──

    private static final String JSONL_SUPPORTED = """
        {"questionId":"q1","source":"test","ontologyIds":["pizza"],"question":"Is X a Y?","answerType":"yesno","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org#X"},"predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#Y"}}]}
        """.strip();

    private static final String JSONL_CONTRADICTED = """
        {"questionId":"q2","source":"test","ontologyIds":["pizza"],"question":"Is A a B?","answerType":"yesno","expectedVerdict":"contradicted","claims":[{"id":"c2","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org#A"},"predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#B"}}]}
        """.strip();

    private static final String JSONL_OUT_OF_SCOPE = """
        {"questionId":"q3","source":"test","ontologyIds":["pizza"],"question":"Is C a D?","answerType":"yesno","expectedVerdict":"out_of_scope","claims":[{"id":"c3","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org#C"},"predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#D"}}]}
        """.strip();

    private static final String JSONL_UNKNOWN = """
        {"questionId":"q4","source":"test","ontologyIds":["pizza"],"question":"Is E a F?","answerType":"yesno","expectedVerdict":"unknown","claims":[{"id":"c4","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org#E"},"predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#F"}}]}
        """.strip();

    private static final String JSONL_EDGE_CASE = """
        {"questionId":"q5","source":"test","ontologyIds":["pizza"],"question":"Is G a H?","answerType":"yesno","expectedVerdict":"supported","claims":[{"id":"c5","type":"subclass","required":true,"subject":{"kind":"class","iri":"http://example.org#G"},"predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#H"}}],"edgeCase":true}
        """.strip();

    // ── Helpers ──

    private Path writeQuestionSet(String... lines) throws Exception {
        Path file = tempDir.resolve("questions.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private ExperimentConfig config(Path questionSetPath, int timeoutSeconds) {
        return new ExperimentConfig(
            "test-run",
            "Unit test",
            List.of("pizza"),
            questionSetPath.toString(),
            List.of("hermit"),
            tempDir.resolve("results.jsonl").toString(),
            timeoutSeconds,
            false,
            ExperimentConfig.EdgeCasePolicy.exclude,
            OptionalInt.empty(),
            ExperimentConfig.ReportFormat.markdown,
            Optional.empty()
        );
    }

    private ExperimentConfig config(Path questionSetPath) {
        return config(questionSetPath, ExperimentConfig.DEFAULT_TIMEOUT_PER_QUESTION);
    }

    private ExperimentConfig config(Path questionSetPath, ExperimentConfig.EdgeCasePolicy policy) {
        return new ExperimentConfig(
            "test-run",
            "Unit test",
            List.of("pizza"),
            questionSetPath.toString(),
            List.of("hermit"),
            tempDir.resolve("results.jsonl").toString(),
            ExperimentConfig.DEFAULT_TIMEOUT_PER_QUESTION,
            false,
            policy,
            OptionalInt.empty(),
            ExperimentConfig.ReportFormat.markdown,
            Optional.empty()
        );
    }

    private AnswerVerificationReport report(AggregateAnswerStatus status) {
        return new AnswerVerificationReport(
            "bench-q1", "pizza", status,
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private void mockVerifySuccess(AggregateAnswerStatus status) {
        AnswerVerificationReport r = report(status);
        when(mockWorkflow.verifyBatch(any(), anyString()))
            .thenReturn(ServiceResult.success(r, ResultMetadata.empty()));
    }

    // ── Verdict mapping tests ──

    @Nested
    @DisplayName("AggregateAnswerStatus → Verdict mapping")
    class VerdictMappingTests {

        @Test
        @DisplayName("VERIFIED maps to SUPPORTED")
        void verifiedStatusMapsToSupported() throws Exception {
            mockVerifySuccess(AggregateAnswerStatus.VERIFIED);
            Path qs = writeQuestionSet(JSONL_SUPPORTED);

            BenchmarkService.BenchmarkRunResult result = service.run(config(qs));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            assertEquals(Verdict.SUPPORTED, result.lines().get(0).actualVerdict(),
                "VERIFIED status should map to SUPPORTED verdict");
        }

        @Test
        @DisplayName("CONTRADICTED maps to CONTRADICTED")
        void contradictedStatusMapsToContradicted() throws Exception {
            mockVerifySuccess(AggregateAnswerStatus.CONTRADICTED);
            Path qs = writeQuestionSet(JSONL_CONTRADICTED);

            BenchmarkService.BenchmarkRunResult result = service.run(config(qs));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            assertEquals(Verdict.CONTRADICTED, result.lines().get(0).actualVerdict(),
                "CONTRADICTED status should map to CONTRADICTED verdict");
        }

        @Test
        @DisplayName("OUT_OF_SCOPE maps to OUT_OF_SCOPE")
        void outOfScopeStatusMapsToOutOfScope() throws Exception {
            mockVerifySuccess(AggregateAnswerStatus.OUT_OF_SCOPE);
            Path qs = writeQuestionSet(JSONL_OUT_OF_SCOPE);

            BenchmarkService.BenchmarkRunResult result = service.run(config(qs));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            assertEquals(Verdict.OUT_OF_SCOPE, result.lines().get(0).actualVerdict(),
                "OUT_OF_SCOPE status should map to OUT_OF_SCOPE verdict");
        }

        @Test
        @DisplayName("INSUFFICIENT_EVIDENCE maps to UNKNOWN")
        void insufficientEvidenceMapsToUnknown() throws Exception {
            mockVerifySuccess(AggregateAnswerStatus.INSUFFICIENT_EVIDENCE);
            Path qs = writeQuestionSet(JSONL_UNKNOWN);

            BenchmarkService.BenchmarkRunResult result = service.run(config(qs));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            assertEquals(Verdict.UNKNOWN, result.lines().get(0).actualVerdict(),
                "INSUFFICIENT_EVIDENCE status should map to UNKNOWN verdict");
        }
    }

    // ── Error and edge-case tests ──

    @Nested
    @DisplayName("Error paths and edge cases")
    class ErrorAndEdgeCaseTests {

        @Test
        @DisplayName("Nonexistent question set path → error summary with 0 questions")
        void errorSummaryOnIoException() throws Exception {
            Path nonexistent = tempDir.resolve("does-not-exist.jsonl");

            BenchmarkService.BenchmarkRunResult result = service.run(config(nonexistent));

            assertTrue(result.lines().isEmpty(), "Should have no result lines");
            assertEquals(0, result.summary().totalQuestions(),
                "Error summary should report 0 total questions");
        }

        @Test
        @DisplayName("Edge-case question excluded from metrics when policy is exclude")
        void edgeCaseExcludedFromMetrics() throws Exception {
            mockVerifySuccess(AggregateAnswerStatus.VERIFIED);
            Path qs = writeQuestionSet(JSONL_EDGE_CASE);

            BenchmarkService.BenchmarkRunResult result = service.run(
                config(qs, ExperimentConfig.EdgeCasePolicy.exclude));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            BenchmarkResultLine line = result.lines().get(0);
            assertTrue(line.edgeCase(), "Line should be flagged as edge case");
            assertEquals(Optional.of("reviewed"), line.reviewStatus(),
                "Edge-case line should have 'reviewed' status");
            assertEquals(0, result.summary().totalQuestions(),
                "Edge-case question excluded from totalQuestions");
        }
    }

    // ── Timeout detection ──

    @Nested
    @DisplayName("Timeout detection")
    class TimeoutTests {

        @Test
        @DisplayName("Zero-second timeout triggers TIMEOUT error")
        void timeoutDetection() throws Exception {
            // The timeout check is based on elapsed real time vs configured timeout.
            // With timeoutPerQuestion = 0, any elapsed time (> 0 ms) exceeds the limit.
            mockVerifySuccess(AggregateAnswerStatus.VERIFIED);
            Path qs = writeQuestionSet(JSONL_SUPPORTED);

            BenchmarkService.BenchmarkRunResult result = service.run(config(qs, 0));

            assertEquals(1, result.lines().size(), "Should produce one result line");
            BenchmarkResultLine line = result.lines().get(0);
            assertEquals(Verdict.UNKNOWN, line.actualVerdict(),
                "Timeout should force verdict to UNKNOWN");
            assertTrue(line.error().isPresent(), "Timeout should produce an error");
            assertEquals("TIMEOUT", line.error().get(),
                "Error should be TIMEOUT");
        }
    }
}
