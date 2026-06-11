package org.owl4agents.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.AggregateAnswerStatus;
import org.owl4agents.core.model.AnswerVerificationReport;
import org.owl4agents.core.model.EvidenceContext;
import org.owl4agents.validation.ClaimWorkflowService;
import org.owl4agents.validation.EvidenceContextBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContextBatchService#processBatch} happy path (C2).
 *
 * Uses Mockito mocks for ClaimWorkflowService and EvidenceContextBuilder,
 * with a real BenchmarkQuestionSetValidator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContextBatchService unit tests")
class ContextBatchServiceTest {

    private static final String VALID_LINE =
        """
        {"questionId":"q1","source":"test","ontologyIds":["pizza"],"question":"Is X a Y?",\
        "answerType":"yesno","expectedVerdict":"supported","claims":[{"id":"c1","type":"subclass",\
        "required":true,"subject":{"kind":"class","iri":"http://example.org#X"},\
        "predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#Y"}}]}
        """;

    private static final String VALID_LINE_Q2 =
        """
        {"questionId":"q2","source":"test","ontologyIds":["pizza"],"question":"Is A a B?",\
        "answerType":"yesno","expectedVerdict":"supported","claims":[{"id":"c2","type":"subclass",\
        "required":true,"subject":{"kind":"class","iri":"http://example.org#A"},\
        "predicate":"rdfs:subClassOf","object":{"kind":"class","iri":"http://example.org#B"}}]}
        """;

    @TempDir
    Path tempDir;

    @Mock
    ClaimWorkflowService mockWorkflow;

    @Mock
    EvidenceContextBuilder mockContextBuilder;

    BenchmarkQuestionSetValidator validator;

    ContextBatchService batchService;

    @BeforeEach
    void setUp() {
        validator = new BenchmarkQuestionSetValidator();
        batchService = new ContextBatchService(mockWorkflow, mockContextBuilder, validator);
    }

    @Nested
    @DisplayName("Happy-path processing (C2)")
    class HappyPathTests {

        @Test
        @DisplayName("happyPathProducesEntries: 2 valid questions produce 2 entries with correct questionIds")
        void happyPathProducesEntries() throws Exception {
            // -- arrange: write a 2-line JSONL file
            Path qsFile = tempDir.resolve("two-questions.jsonl");
            Files.writeString(qsFile, VALID_LINE.strip() + "\n" + VALID_LINE_Q2.strip() + "\n");

            // mock: both questions verify successfully
            AnswerVerificationReport report = new AnswerVerificationReport(
                "q1", "pizza", AggregateAnswerStatus.VERIFIED,
                List.of(), Optional.empty(), Optional.empty()
            );
            when(mockWorkflow.verifyBatch(any(), anyString()))
                .thenReturn(ServiceResult.success(report, ResultMetadata.empty()));

            EvidenceContext mockCtx = mock(EvidenceContext.class);
            when(mockCtx.claims()).thenReturn(List.of());
            when(mockCtx.omittedClaimCount()).thenReturn(0);
            when(mockContextBuilder.buildContext(any(), anyInt())).thenReturn(mockCtx);

            // -- act
            ContextBatchService.ContextBatchResult result =
                batchService.processBatch(qsFile.toString(), "pizza", 4096);

            // -- assert
            assertEquals(2, result.entries().size(), "Should produce 2 entries");
            List<String> questionIds = result.entries().stream()
                .map(ContextBatchService.ContextBatchEntry::questionId)
                .toList();
            assertTrue(questionIds.contains("q1"), "First entry should have questionId q1");
            assertTrue(questionIds.contains("q2"), "Second entry should have questionId q2");
        }

        @Test
        @DisplayName("budgetCharsUsedNonNegative: budgetCharsUsed is >= 0 for each entry")
        void budgetCharsUsedNonNegative() throws Exception {
            Path qsFile = tempDir.resolve("budget.jsonl");
            Files.writeString(qsFile, VALID_LINE.strip() + "\n");

            AnswerVerificationReport report = new AnswerVerificationReport(
                "q1", "pizza", AggregateAnswerStatus.VERIFIED,
                List.of(), Optional.empty(), Optional.empty()
            );
            when(mockWorkflow.verifyBatch(any(), anyString()))
                .thenReturn(ServiceResult.success(report, ResultMetadata.empty()));

            EvidenceContext mockCtx = mock(EvidenceContext.class);
            when(mockCtx.claims()).thenReturn(List.of());
            when(mockCtx.omittedClaimCount()).thenReturn(0);
            when(mockContextBuilder.buildContext(any(), anyInt())).thenReturn(mockCtx);

            ContextBatchService.ContextBatchResult result =
                batchService.processBatch(qsFile.toString(), "pizza", 4096);

            for (ContextBatchService.ContextBatchEntry entry : result.entries()) {
                assertTrue(entry.budgetCharsUsed() >= 0,
                    "budgetCharsUsed should be >= 0, was " + entry.budgetCharsUsed());
            }
        }

        @Test
        @DisplayName("errorsListEmptyOnSuccess: errors list is empty when all questions succeed")
        void errorsListEmptyOnSuccess() throws Exception {
            Path qsFile = tempDir.resolve("no-errors.jsonl");
            Files.writeString(qsFile, VALID_LINE.strip() + "\n");

            AnswerVerificationReport report = new AnswerVerificationReport(
                "q1", "pizza", AggregateAnswerStatus.VERIFIED,
                List.of(), Optional.empty(), Optional.empty()
            );
            when(mockWorkflow.verifyBatch(any(), anyString()))
                .thenReturn(ServiceResult.success(report, ResultMetadata.empty()));

            EvidenceContext mockCtx = mock(EvidenceContext.class);
            when(mockCtx.claims()).thenReturn(List.of());
            when(mockCtx.omittedClaimCount()).thenReturn(0);
            when(mockContextBuilder.buildContext(any(), anyInt())).thenReturn(mockCtx);

            ContextBatchService.ContextBatchResult result =
                batchService.processBatch(qsFile.toString(), "pizza", 4096);

            assertTrue(result.errors().isEmpty(),
                "errors list should be empty on success, but was: " + result.errors());
        }

        @Test
        @DisplayName("emptyQuestionSetReturnsEmptyResult: empty JSONL produces 0 entries")
        void emptyQuestionSetReturnsEmptyResult() throws Exception {
            Path qsFile = tempDir.resolve("empty.jsonl");
            Files.writeString(qsFile, "");

            ContextBatchService.ContextBatchResult result =
                batchService.processBatch(qsFile.toString(), "pizza", 4096);

            assertTrue(result.entries().isEmpty(),
                "Empty question set should produce 0 entries");
            assertTrue(result.errors().isEmpty(),
                "Empty question set should produce no errors");
        }
    }
}
