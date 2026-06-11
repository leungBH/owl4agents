package org.owl4agents.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.owl4agents.core.model.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3: Direct tests for BenchmarkResultReader.readResults() and readSummary().
 * Verifies JSONL parsing, summary-line skipping, and VerdictAdapter correctness.
 */
@DisplayName("BenchmarkResultReader tests")
class BenchmarkResultReaderTest {

    @TempDir
    Path tempDir;

    private final BenchmarkResultReader reader = new BenchmarkResultReader();

    // -- Fixtures --

    private static final String RESULT_LINE_1 = """
        {"questionId":"pizza-001","ontologyId":"pizza","reasoner":"hermit","claimsVerified":1,"actualVerdict":"supported","expectedVerdict":"supported","verdictMatch":true,"elapsedMs":120,"reviewStatus":"approved","error":null,"edgeCase":false}""";

    private static final String RESULT_LINE_2 = """
        {"questionId":"pizza-002","ontologyId":"pizza","reasoner":"hermit","claimsVerified":1,"actualVerdict":"unknown","expectedVerdict":"contradicted","verdictMatch":false,"elapsedMs":85,"reviewStatus":"approved","error":null,"edgeCase":false}""";

    private static final String SUMMARY_LINE = """
        {"type":"summary","totalQuestions":2,"accuracy":0.5,"falseSupportRate":0.0,"falseSupportedCount":0,"unresolvedRate":0.5,"falseUnknownCount":1,"verificationCoverage":0.5,"unknownRate":0.5,"outOfScopeRate":0.0,"perVerdictCounts":{"supported":1,"unknown":1},"perReasonerTiming":{"hermit":205}}""";

    private static final String SUMMARY_LINE_COMPACT = """
        {"type":"summary","totalQuestions":0,"accuracy":0.0,"falseSupportRate":0.0,"falseSupportedCount":0,"unresolvedRate":0.0,"falseUnknownCount":0,"verificationCoverage":0.0,"unknownRate":0.0,"outOfScopeRate":0.0,"perVerdictCounts":{},"perReasonerTiming":{}}""";

    @Nested
    @DisplayName("readResults()")
    class ReadResultsTests {

        @Test
        @DisplayName("Returns result lines and skips summary line")
        void readResultsSkipsSummaryLine() throws Exception {
            Path file = tempDir.resolve("results.jsonl");
            Files.writeString(file, RESULT_LINE_1 + "\n" + RESULT_LINE_2 + "\n" + SUMMARY_LINE + "\n");

            List<BenchmarkResultLine> results = reader.readResults(file);
            assertEquals(2, results.size());
            assertEquals("pizza-001", results.get(0).questionId());
            assertEquals("pizza-002", results.get(1).questionId());
        }

        @Test
        @DisplayName("Summary line with compact spacing is also skipped")
        void readResultsSkipsCompactSummary() throws Exception {
            Path file = tempDir.resolve("results.jsonl");
            Files.writeString(file, RESULT_LINE_1 + "\n" + SUMMARY_LINE_COMPACT + "\n");

            List<BenchmarkResultLine> results = reader.readResults(file);
            assertEquals(1, results.size());
            assertEquals("pizza-001", results.get(0).questionId());
        }

        @Test
        @DisplayName("VerdictAdapter correctly deserializes all verdict values")
        void verdictAdapterDeserializesCorrectly() throws Exception {
            Path file = tempDir.resolve("verdicts.jsonl");
            // Test each verdict type
            String line1 = """
                {"questionId":"q1","ontologyId":"o","reasoner":"r","claimsVerified":1,"actualVerdict":"supported","expectedVerdict":"supported","verdictMatch":true,"elapsedMs":1,"reviewStatus":"approved","error":null,"edgeCase":false}""";
            String line2 = """
                {"questionId":"q2","ontologyId":"o","reasoner":"r","claimsVerified":1,"actualVerdict":"contradicted","expectedVerdict":"contradicted","verdictMatch":true,"elapsedMs":1,"reviewStatus":"approved","error":null,"edgeCase":false}""";
            String line3 = """
                {"questionId":"q3","ontologyId":"o","reasoner":"r","claimsVerified":1,"actualVerdict":"unknown","expectedVerdict":"unknown","verdictMatch":true,"elapsedMs":1,"reviewStatus":"approved","error":null,"edgeCase":false}""";
            String line4 = """
                {"questionId":"q4","ontologyId":"o","reasoner":"r","claimsVerified":1,"actualVerdict":"out_of_scope","expectedVerdict":"out_of_scope","verdictMatch":true,"elapsedMs":1,"reviewStatus":"approved","error":null,"edgeCase":false}""";
            Files.writeString(file, String.join("\n", line1, line2, line3, line4) + "\n");

            List<BenchmarkResultLine> results = reader.readResults(file);
            assertEquals(4, results.size());
            assertEquals(Verdict.SUPPORTED, results.get(0).actualVerdict());
            assertEquals(Verdict.CONTRADICTED, results.get(1).actualVerdict());
            assertEquals(Verdict.UNKNOWN, results.get(2).actualVerdict());
            assertEquals(Verdict.OUT_OF_SCOPE, results.get(3).actualVerdict());
        }

        @Test
        @DisplayName("Empty file returns empty list")
        void emptyFileReturnsEmptyList() throws Exception {
            Path file = tempDir.resolve("empty.jsonl");
            Files.writeString(file, "");

            List<BenchmarkResultLine> results = reader.readResults(file);
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("readSummary()")
    class ReadSummaryTests {

        @Test
        @DisplayName("Returns summary when present")
        void readSummaryReturnsSummary() throws Exception {
            Path file = tempDir.resolve("results.jsonl");
            Files.writeString(file, RESULT_LINE_1 + "\n" + SUMMARY_LINE + "\n");

            BenchmarkResultSummary summary = reader.readSummary(file);
            assertNotNull(summary);
            assertEquals("summary", summary.type());
            assertEquals(2, summary.totalQuestions());
            assertEquals(0.5, summary.accuracy(), 0.001);
            assertEquals(0.5, summary.verificationCoverage(), 0.001);
        }

        @Test
        @DisplayName("Returns null when no summary line present")
        void readSummaryReturnsNullWhenNoSummary() throws Exception {
            Path file = tempDir.resolve("results.jsonl");
            Files.writeString(file, RESULT_LINE_1 + "\n" + RESULT_LINE_2 + "\n");

            BenchmarkResultSummary summary = reader.readSummary(file);
            assertNull(summary);
        }
    }
}
