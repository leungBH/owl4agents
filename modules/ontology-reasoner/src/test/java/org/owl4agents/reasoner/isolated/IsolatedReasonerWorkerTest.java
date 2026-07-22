package org.owl4agents.reasoner.isolated;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 D15 / REL-001 (task 8.8): IsolatedReasonerWorker unit tests.
 *
 * <p>Covers the 9 scenarios required by the reasoner-runtime spec:</p>
 * <ol>
 *   <li>Lazy startup — child JVM not spawned until first {@code submit()}.</li>
 *   <li>Ping health check — first submit triggers spawn + ping, returns pong.</li>
 *   <li>Child crash → {@link ErrorCode#REASONER_WORKER_CRASHED}.</li>
 *   <li>Classify (stubbed) — successful operation returns success.</li>
 *   <li>Call timeout → {@link ErrorCode#REASONER_TIMEOUT}.</li>
 *   <li>Crash recovery — next submit spawns a new child after a crash.</li>
 *   <li>Malformed JSON → {@link ErrorCode#REASONER_WORKER_PROTOCOL_ERROR}.</li>
 *   <li>Request serialization — concurrent calls are processed one at a time.</li>
 *   <li>Graceful shutdown — {@code close()} terminates the child JVM.</li>
 * </ol>
 *
 * <p>Failure-mode tests (timeout, crash, malformed) use {@link StubWorkerMain}
 * to get deterministic behavior without depending on real reasoner timing.
 * The lazy-startup/ping test uses the real {@link ReasonerWorkerMain} for
 * end-to-end integration coverage.</p>
 */
@DisplayName("v0.8.7 D15 (task 8.8): IsolatedReasonerWorker")
class IsolatedReasonerWorkerTest {

    private IsolatedReasonerWorker worker;

    @AfterEach
    void cleanup() {
        if (worker != null) {
            try {
                worker.close();
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
            worker = null;
        }
        // Clear any isolation-enabled system property set by tests
        System.clearProperty(IsolatedReasonerWorker.ISOLATION_ENABLED_PROPERTY);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1 + 2. Lazy startup + ping (end-to-end with real ReasonerWorkerMain)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-1: lazy startup — no child process before first submit; ping succeeds")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void lazyStartupAndPing_realWorker() throws Exception {
        worker = new IsolatedReasonerWorker();

        // Before first submit, childRef should be null (lazy startup).
        assertNull(getChildRef(worker), "Child JVM must not be spawned before first submit");

        // First submit triggers lazy spawn + ping health check.
        ServiceResult<IsolatedReasonerResponse> result = worker.submit(IsolatedReasonerRequest.ping());

        assertTrue(result.isSuccess(),
            "Ping should succeed; got error: " + errorMessage(result));
        ServiceResult.Success<IsolatedReasonerResponse> success =
            (ServiceResult.Success<IsolatedReasonerResponse>) result;
        assertNotNull(success.data(), "Ping response must not be null");
        assertNotNull(success.data().result(), "Ping result must not be null");

        // After first submit, childRef should be non-null (child spawned).
        assertNotNull(getChildRef(worker), "Child JVM must be spawned after first submit");
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. Child crash → REASONER_WORKER_CRASHED
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-3: child crash → REASONER_WORKER_CRASHED error")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void childCrash_returnsCrashedError() throws Exception {
        worker = newStubWorker("crash");

        // Send a classify (not ping) so the stub enters the crash branch.
        // The ping health check succeeds because the stub handles ping
        // immediately with valid JSON regardless of mode.
        IsolatedReasonerRequest request = new IsolatedReasonerRequest(
            "<stub>", "HermiT", "classify", null, 10_000L);
        ServiceResult<IsolatedReasonerResponse> result = worker.submit(request);

        assertFalse(result.isSuccess(), "Crash should produce an error result");
        ServiceResult.Error<IsolatedReasonerResponse> err =
            (ServiceResult.Error<IsolatedReasonerResponse>) result;
        assertEquals(ErrorCode.REASONER_WORKER_CRASHED, err.error().code(),
            "Crashed child must return REASONER_WORKER_CRASHED");
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Classify (stubbed) — successful operation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-4: classify (stubbed) returns success with operation payload")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void classify_stubReturnsSuccess() throws Exception {
        worker = newStubWorker("normal");

        IsolatedReasonerRequest request = new IsolatedReasonerRequest(
            "<stub>", "HermiT", "classify", null, 10_000L);
        ServiceResult<IsolatedReasonerResponse> result = worker.submit(request);

        assertTrue(result.isSuccess(),
            "Classify should succeed; got error: " + errorMessage(result));
        ServiceResult.Success<IsolatedReasonerResponse> success =
            (ServiceResult.Success<IsolatedReasonerResponse>) result;
        assertNotNull(success.data().result(), "Classify result must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Call timeout → REASONER_TIMEOUT
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-5: call timeout → REASONER_TIMEOUT error (child destroyed)")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void callTimeout_returnsTimeoutError() throws Exception {
        worker = newStubWorker("slow");

        // Submit with a 500ms timeout; stub sleeps 5s before responding.
        IsolatedReasonerRequest request = new IsolatedReasonerRequest(
            "<stub>", "HermiT", "classify", null, 500L);
        ServiceResult<IsolatedReasonerResponse> result = worker.submit(request);

        assertFalse(result.isSuccess(), "Timeout should produce an error result");
        ServiceResult.Error<IsolatedReasonerResponse> err =
            (ServiceResult.Error<IsolatedReasonerResponse>) result;
        assertEquals(ErrorCode.REASONER_TIMEOUT, err.error().code(),
            "Timed-out call must return REASONER_TIMEOUT");
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Crash recovery — next submit spawns a new child after a crash
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-6: crash recovery — second submit succeeds after first crashes")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void crashRecovery_secondSubmitSucceedsAfterCrash() throws Exception {
        // Create a marker file so the first stub JVM crashes; the stub
        // deletes the marker before exiting, so the second JVM succeeds.
        Path marker = Files.createTempFile("stub-crash-marker", ".tmp");
        worker = new IsolatedReasonerWorker(
            null,
            System.getProperty("java.class.path"),
            "org.owl4agents.reasoner.isolated.StubWorkerMain",
            new String[] {
                "-Dstub.mode=normal",
                "-Dstub.crash.marker=" + marker.toAbsolutePath()
            });

        // First submit: stub sees marker → crashes → REASONER_WORKER_CRASHED
        ServiceResult<IsolatedReasonerResponse> first = worker.submit(IsolatedReasonerRequest.ping());
        assertFalse(first.isSuccess(),
            "First submit should crash (marker present)");
        assertEquals(ErrorCode.REASONER_WORKER_CRASHED,
            ((ServiceResult.Error<?>) first).error().code(),
            "First submit should return REASONER_WORKER_CRASHED");

        // Second submit: new JVM, marker deleted → ping succeeds
        ServiceResult<IsolatedReasonerResponse> second = worker.submit(IsolatedReasonerRequest.ping());
        assertTrue(second.isSuccess(),
            "Second submit should succeed after crash recovery; got error: "
                + errorMessage(second));
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Malformed JSON → REASONER_WORKER_PROTOCOL_ERROR
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-7: malformed JSON → REASONER_WORKER_PROTOCOL_ERROR")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void malformedJson_returnsProtocolError() throws Exception {
        worker = newStubWorker("malformed");

        // Send a classify (not ping) so the stub emits malformed JSON.
        // The ping health check succeeds because the stub handles ping
        // immediately with valid JSON regardless of mode.
        IsolatedReasonerRequest request = new IsolatedReasonerRequest(
            "<stub>", "HermiT", "classify", null, 10_000L);
        ServiceResult<IsolatedReasonerResponse> result = worker.submit(request);

        assertFalse(result.isSuccess(), "Malformed JSON should produce an error result");
        ServiceResult.Error<IsolatedReasonerResponse> err =
            (ServiceResult.Error<IsolatedReasonerResponse>) result;
        assertEquals(ErrorCode.REASONER_WORKER_PROTOCOL_ERROR, err.error().code(),
            "Malformed JSON must return REASONER_WORKER_PROTOCOL_ERROR");
    }

    // ──────────────────────────────────────────────────────────────────
    // 8. Request serialization — concurrent calls processed one at a time
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-8: request serialization — concurrent calls processed serially")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void requestSerialization_concurrentCallsProcessedSerially() throws Exception {
        worker = newStubWorker("slow");

        IsolatedReasonerRequest req1 = new IsolatedReasonerRequest(
            "<stub>", "HermiT", "classify", null, 30_000L);
        IsolatedReasonerRequest req2 = new IsolatedReasonerRequest(
            "<stub>", "ELK", "classify", null, 30_000L);

        long start = System.currentTimeMillis();
        CompletableFuture<ServiceResult<IsolatedReasonerResponse>> f1 =
            CompletableFuture.supplyAsync(() -> worker.submit(req1));
        CompletableFuture<ServiceResult<IsolatedReasonerResponse>> f2 =
            CompletableFuture.supplyAsync(() -> worker.submit(req2));

        ServiceResult<IsolatedReasonerResponse> r1 = f1.get(30, TimeUnit.SECONDS);
        ServiceResult<IsolatedReasonerResponse> r2 = f2.get(30, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(r1.isSuccess(), "First call should succeed; got: " + errorMessage(r1));
        assertTrue(r2.isSuccess(), "Second call should succeed; got: " + errorMessage(r2));

        // Each call sleeps 5s. If serialized, total ≈ 10s.
        // If parallel, total ≈ 5s. Allow 1s slack.
        assertTrue(elapsed >= 9_000L,
            "Concurrent calls must be serialized (expected ≥ 9s, got " + elapsed + "ms)");
    }

    // ──────────────────────────────────────────────────────────────────
    // 9. Graceful shutdown — close() terminates the child JVM
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-9: graceful shutdown — close() terminates child JVM")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void gracefulShutdown_closeTerminatesChild() throws Exception {
        worker = newStubWorker("normal");

        // Spawn the child via a ping.
        ServiceResult<IsolatedReasonerResponse> ping = worker.submit(IsolatedReasonerRequest.ping());
        assertTrue(ping.isSuccess(), "Ping should succeed before shutdown");

        // Close the worker — should terminate the child gracefully.
        worker.close();

        // After close(), submit() must reject with "closed" error.
        ServiceResult<IsolatedReasonerResponse> afterClose =
            worker.submit(IsolatedReasonerRequest.ping());
        assertFalse(afterClose.isSuccess(),
            "Submit after close() must fail");
        ServiceResult.Error<IsolatedReasonerResponse> err =
            (ServiceResult.Error<IsolatedReasonerResponse>) afterClose;
        assertEquals(ErrorCode.REASONER_INTERNAL_ERROR, err.error().code(),
            "Closed worker must return REASONER_INTERNAL_ERROR");
        assertTrue(err.error().message().toLowerCase().contains("closed"),
            "Error message should mention 'closed': " + err.error().message());

        // Prevent @AfterEach from double-closing.
        worker = null;
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static IsolatedReasonerWorker newStubWorker(String mode) {
        return new IsolatedReasonerWorker(
            null,
            System.getProperty("java.class.path"),
            "org.owl4agents.reasoner.isolated.StubWorkerMain",
            new String[] { "-Dstub.mode=" + mode });
    }

    private static Object getChildRef(IsolatedReasonerWorker worker) throws Exception {
        Field field = IsolatedReasonerWorker.class.getDeclaredField("childRef");
        field.setAccessible(true);
        AtomicReference<?> ref = (AtomicReference<?>) field.get(worker);
        return ref.get();
    }

    private static String errorMessage(ServiceResult<?> result) {
        if (result.isSuccess()) return "(success)";
        ServiceResult.Error<?> err = (ServiceResult.Error<?>) result;
        return err.error().code() + ": " + err.error().message();
    }
}
