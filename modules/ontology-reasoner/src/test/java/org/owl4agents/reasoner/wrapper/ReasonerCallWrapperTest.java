package org.owl4agents.reasoner.wrapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.ReasonerCallMetadata;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 3 (P0-3): ReasonerCallWrapper unit tests.
 *
 * <p>Covers D1 design decisions:</p>
 * <ul>
 *   <li>Timeout enforcement returns REASONER_TIMEOUT</li>
 *   <li>ELK fallback on primary timeout</li>
 *   <li>Executor recovery after timeout (stuck executor replaced)</li>
 *   <li>REASONER_BUSY on concurrent calls (SynchronousQueue + AbortPolicy)</li>
 *   <li>Metadata propagation through ServiceResult</li>
 * </ul>
 */
@DisplayName("v0.8.6 ReasonerCallWrapper (P0-3): timeout, ELK fallback, executor recovery, busy, metadata")
class ReasonerCallWrapperTest {

    private static final OntologyId ONT_ID = new OntologyId("test-ont");

    private ReasonerCallWrapper newWrapper(long timeoutSec) {
        ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "test-reasoner-wrapper");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
        return new ReasonerCallWrapper(executor, timeoutSec);
    }

    @Test
    @DisplayName("WRAP-1: Successful call returns success with metadata")
    void successfulCallReturnsSuccessWithMetadata() {
        ReasonerCallWrapper wrapper = newWrapper(5);
        ServiceResult<String> result = wrapper.call(
            "HermiT", ONT_ID,
            () -> ServiceResult.success("consistent", org.owl4agents.core.ResultMetadata.empty()),
            5);

        assertTrue(result.isSuccess(), "Call should succeed");
        ServiceResult.Success<String> success = (ServiceResult.Success<String>) result;
        assertEquals("consistent", success.data());
        ReasonerCallMetadata meta = success.reasonerMetadata();
        assertNotNull(meta, "Metadata must be auto-populated by wrapper");
        assertEquals("HermiT", meta.reasonerName());
        assertNull(meta.fallbackFrom(), "No fallback on direct success");
        assertFalse(meta.executorRecovered(), "Executor not recovered on success");
        assertEquals(5000L, meta.timeoutMs());
    }

    @Test
    @DisplayName("WRAP-2: Timeout returns REASONER_TIMEOUT with executorRecovered=true")
    void timeoutReturnsReasonerTimeout() {
        ReasonerCallWrapper wrapper = newWrapper(1);
        ServiceResult<String> result = wrapper.call(
            "HermiT", ONT_ID,
            () -> {
                try {
                    Thread.sleep(5000); // exceed 1s timeout
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return ServiceResult.success("never-reached", org.owl4agents.core.ResultMetadata.empty());
            },
            1);

        assertFalse(result.isSuccess(), "Timed-out call must not be success");
        ServiceResult.Error<String> error = (ServiceResult.Error<String>) result;
        assertEquals(ErrorCode.REASONER_TIMEOUT, error.error().code(),
            "Must return REASONER_TIMEOUT on timeout");
        ReasonerCallMetadata meta = error.reasonerMetadata();
        assertNotNull(meta, "Metadata must be populated on timeout");
        assertEquals("HermiT", meta.reasonerName());
        assertTrue(meta.executorRecovered(), "Executor must be recovered after timeout");
        assertEquals(1000L, meta.timeoutMs());
    }

    @Test
    @DisplayName("WRAP-3: After timeout, wrapper recovers and accepts new calls")
    void executorRecoversAfterTimeout() throws Exception {
        ReasonerCallWrapper wrapper = newWrapper(1);

        // First call times out
        ServiceResult<String> r1 = wrapper.call(
            "HermiT", ONT_ID,
            () -> {
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return ServiceResult.success("r1", org.owl4agents.core.ResultMetadata.empty());
            },
            1);
        assertFalse(r1.isSuccess());

        // Second call should succeed on recovered executor
        ServiceResult<String> r2 = wrapper.call(
            "HermiT", ONT_ID,
            () -> ServiceResult.success("r2", org.owl4agents.core.ResultMetadata.empty()),
            2);
        assertTrue(r2.isSuccess(), "Wrapper must accept new calls after executor recovery");
        assertEquals("r2", ((ServiceResult.Success<String>) r2).data());
    }

    @Test
    @DisplayName("WRAP-4: callWithElkFallback — primary success returns primary result (no fallback)")
    void elkFallbackNotInvokedOnPrimarySuccess() {
        ReasonerCallWrapper wrapper = newWrapper(2);
        AtomicBoolean elkCalled = new AtomicBoolean(false);

        ServiceResult<String> result = wrapper.callWithElkFallback(
            "HermiT", ONT_ID,
            () -> ServiceResult.success("primary-ok", org.owl4agents.core.ResultMetadata.empty()),
            () -> {
                elkCalled.set(true);
                return ServiceResult.success("elk-ok", org.owl4agents.core.ResultMetadata.empty());
            },
            2);

        assertTrue(result.isSuccess());
        assertEquals("primary-ok", ((ServiceResult.Success<String>) result).data());
        assertFalse(elkCalled.get(), "ELK supplier must NOT be called when primary succeeds");
        ServiceResult.Success<String> success = (ServiceResult.Success<String>) result;
        assertNull(success.reasonerMetadata().fallbackFrom(),
            "fallbackFrom must be null when no fallback occurred");
    }

    @Test
    @DisplayName("WRAP-5: callWithElkFallback — primary timeout triggers ELK fallback")
    void elkFallbackOnPrimaryTimeout() {
        ReasonerCallWrapper wrapper = newWrapper(1);

        ServiceResult<String> result = wrapper.callWithElkFallback(
            "HermiT", ONT_ID,
            () -> {
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return ServiceResult.success("never", org.owl4agents.core.ResultMetadata.empty());
            },
            () -> ServiceResult.success("elk-fallback-ok", org.owl4agents.core.ResultMetadata.empty()),
            1);

        assertTrue(result.isSuccess(), "ELK fallback should succeed");
        ServiceResult.Success<String> success = (ServiceResult.Success<String>) result;
        assertEquals("elk-fallback-ok", success.data());
        ReasonerCallMetadata meta = success.reasonerMetadata();
        assertNotNull(meta);
        assertEquals("ELK", meta.reasonerName(), "Metadata reasonerName must be ELK after fallback");
        assertEquals("HermiT", meta.fallbackFrom(), "fallbackFrom must be original reasoner");
        assertTrue(meta.executorRecovered(), "Executor must be recovered (primary timed out)");
    }

    @Test
    @DisplayName("WRAP-6: callWithElkFallback — when reasonerName=ELK, no fallback (delegates to call)")
    void elkFallbackSkippedWhenReasonerIsElk() {
        ReasonerCallWrapper wrapper = newWrapper(2);
        AtomicBoolean elkCalled = new AtomicBoolean(false);

        ServiceResult<String> result = wrapper.callWithElkFallback(
            "ELK", ONT_ID,
            () -> ServiceResult.success("elk-direct", org.owl4agents.core.ResultMetadata.empty()),
            () -> {
                elkCalled.set(true);
                return ServiceResult.success("fallback", org.owl4agents.core.ResultMetadata.empty());
            },
            2);

        assertTrue(result.isSuccess());
        assertEquals("elk-direct", ((ServiceResult.Success<String>) result).data());
        assertFalse(elkCalled.get(), "ELK fallback must not invoke ELK supplier when primary is already ELK");
    }

    @Test
    @DisplayName("WRAP-7: callWithElkFallback — both primary and ELK timeout returns REASONER_TIMEOUT")
    void bothPrimaryAndElkTimeout() {
        ReasonerCallWrapper wrapper = newWrapper(1);

        ServiceResult<String> result = wrapper.callWithElkFallback(
            "HermiT", ONT_ID,
            () -> {
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return ServiceResult.success("never", org.owl4agents.core.ResultMetadata.empty());
            },
            () -> {
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return ServiceResult.success("never-elk", org.owl4agents.core.ResultMetadata.empty());
            },
            1);

        assertFalse(result.isSuccess());
        ServiceResult.Error<String> error = (ServiceResult.Error<String>) result;
        assertEquals(ErrorCode.REASONER_TIMEOUT, error.error().code(),
            "Dual timeout must return REASONER_TIMEOUT");
        ReasonerCallMetadata meta = error.reasonerMetadata();
        assertNotNull(meta);
        assertEquals("ELK", meta.reasonerName(), "Final metadata reasonerName must be ELK");
        assertEquals("HermiT", meta.fallbackFrom(), "fallbackFrom must record original reasoner");
        assertTrue(meta.executorRecovered());
    }

    @Test
    @DisplayName("WRAP-8: REASONER_BUSY when concurrent call arrives while another is running")
    void reasonerBusyOnConcurrentCall() throws Exception {
        ReasonerCallWrapper wrapper = newWrapper(10);
        java.util.concurrent.CountDownLatch inProgress = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        // Submit first call in a separate thread
        ExecutorService testExecutor = Executors.newSingleThreadExecutor();
        try {
            testExecutor.submit(() -> {
                return wrapper.call("HermiT", ONT_ID,
                    () -> {
                        inProgress.countDown();
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        return ServiceResult.success("first", org.owl4agents.core.ResultMetadata.empty());
                    },
                    10);
            });

            // Wait for first call to start
            assertTrue(inProgress.await(2, TimeUnit.SECONDS), "First call must start");

            // Now submit a second concurrent call — must be rejected (REASONER_BUSY)
            ServiceResult<String> second = wrapper.call(
                "HermiT", ONT_ID,
                () -> ServiceResult.success("second", org.owl4agents.core.ResultMetadata.empty()),
                10);

            assertFalse(second.isSuccess(), "Second concurrent call must not succeed");
            ServiceResult.Error<String> error = (ServiceResult.Error<String>) second;
            assertEquals(ErrorCode.REASONER_BUSY, error.error().code(),
                "Concurrent call must return REASONER_BUSY");
            assertNull(error.reasonerMetadata(),
                "REASONER_BUSY metadata must be null per D1 (no reasoner acquired)");

            release.countDown();
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("WRAP-9: Supplier-returned metadata is preserved (not overwritten)")
    void supplierProvidedMetadataIsPreserved() {
        ReasonerCallWrapper wrapper = newWrapper(2);
        ReasonerCallMetadata custom = new ReasonerCallMetadata("CustomReasoner", "ElkFallback", true, 9999L);

        ServiceResult<String> result = wrapper.call(
            "HermiT", ONT_ID,
            () -> ServiceResult.success(
                "ok",
                org.owl4agents.core.ResultMetadata.empty(),
                custom),
            2);

        assertTrue(result.isSuccess());
        ServiceResult.Success<String> success = (ServiceResult.Success<String>) result;
        assertSame(custom, success.reasonerMetadata(),
            "Supplier-provided metadata must be preserved (wrapper must NOT overwrite)");
    }

    @Test
    @DisplayName("WRAP-10: ErrorCode.fromCode() resolves new v0.8.6 error codes (case-insensitive)")
    void errorCodeFromCodeResolvesNewCodes() {
        assertEquals(ErrorCode.REASONER_BUSY, ErrorCode.fromCode("REASONER_BUSY").orElse(null));
        assertEquals(ErrorCode.REASONER_BUSY, ErrorCode.fromCode("reasoner_busy").orElse(null));
        assertEquals(ErrorCode.REASONER_TIMEOUT, ErrorCode.fromCode("reasoner_timeout").orElse(null));
        assertEquals(ErrorCode.REASONER_REJECTED_ONTOLOGY,
            ErrorCode.fromCode("reasoner_rejected_ontology").orElse(null));
        assertEquals(ErrorCode.REASONER_INTERNAL_ERROR,
            ErrorCode.fromCode("Reasoner_Internal_Error").orElse(null));
        assertEquals(ErrorCode.REASONER_INTERRUPTED,
            ErrorCode.fromCode("REASONER_INTERRUPTED").orElse(null));
        assertTrue(ErrorCode.fromCode("NONEXISTENT_CODE").isEmpty(),
            "Unknown code must return empty Optional");
    }
}
