package org.owl4agents.reasoner.isolated;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * v0.8.7 REL-001 / D15: Parent-side API for isolated reasoner execution.
 *
 * <p>Spawns a child JVM running {@link ReasonerWorkerMain} and
 * communicates via JSON-RPC over stdin/stdout. The child JVM provides
 * process-level isolation so that reasoner timeouts can be enforced
 * via {@link Process#destroyForcibly()} — Java's {@code Thread.interrupt()}
 * cannot reliably terminate HermiT/ELK native tableau computations
 * (see design D15).</p>
 *
 * <h2>Opt-in via system property</h2>
 *
 * <p>Isolated reasoner execution is opt-in via the
 * {@code owl4agents.reasoner.isolation.enabled} system property (default
 * {@code false}). When disabled (the default), the existing in-process
 * {@code ReasonerCallWrapper} path is used unchanged; no child JVM is
 * spawned. When enabled, calls route through this worker.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li><b>Lazy startup</b>: the child JVM is spawned on the first
 *       {@link #submit(IsolatedReasonerRequest)} call, not at
 *       construction. Subsequent calls reuse the existing child JVM
 *       (if healthy).</li>
 *   <li><b>Health check</b>: after spawning the child JVM, the parent
 *       sends a {@code ping} request and expects a {@code pong=true}
 *       response within 5 seconds. If the ping times out, the child
 *       is terminated via {@code destroyForcibly()} and a
 *       {@link ErrorCode#REASONER_WORKER_CRASHED} error is returned.</li>
 *   <li><b>Request serialization</b>: concurrent calls to
 *       {@link #submit} are serialized via a {@link ReentrantLock} —
 *       only one reasoner call runs in the child JVM at a time. Queued
 *       calls wait on the lock and are processed in submission order.</li>
 *   <li><b>Timeout</b>: per-call timeout via
 *       {@link CompletableFuture#get(long, TimeUnit)}. On timeout the
 *       child JVM is {@code destroyForcibly()}-ed and a
 *       {@link ErrorCode#REASONER_TIMEOUT} error is returned.</li>
 *   <li><b>Crash detection</b>: a watchdog thread monitors
 *       {@link Process#waitFor()}; on non-zero exit it returns
 *       {@link ErrorCode#REASONER_WORKER_CRASHED} with exit code and
 *       captured stderr tail.</li>
 *   <li><b>Malformed JSON</b>: if the child outputs invalid JSON on
 *       stdout, returns {@link ErrorCode#REASONER_WORKER_PROTOCOL_ERROR}.</li>
 *   <li><b>Graceful shutdown</b>: a JVM shutdown hook calls
 *       {@link Process#destroy()}; if not exited in 5 seconds, calls
 *       {@code destroyForcibly()}.</li>
 *   <li><b>Crash recovery</b>: if the child crashes, the next
 *       {@code submit()} spawns a new child JVM.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances are thread-safe. All public methods are synchronized
 * via an internal {@link ReentrantLock}.</p>
 */
public final class IsolatedReasonerWorker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("org.owl4agents.reasoner.isolated");

    /** System property enabling isolated reasoner execution (default: false). */
    public static final String ISOLATION_ENABLED_PROPERTY = "owl4agents.reasoner.isolation.enabled";

    /** Default ping timeout in milliseconds. */
    public static final long PING_TIMEOUT_MS = 5_000L;

    /** Default graceful shutdown timeout in milliseconds. */
    public static final long SHUTDOWN_TIMEOUT_MS = 5_000L;

    private static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    private final ReentrantLock submitLock = new ReentrantLock();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "owl4agents-isolated-worker-io");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<ChildProcess> childRef = new AtomicReference<>();
    private final String childJvmMainClass;
    private final String javaLauncher;
    private final String classpath;
    private final String[] childJvmArgs;
    private volatile boolean closed = false;

    /**
     * Construct an {@code IsolatedReasonerWorker} using the default
     * child JVM launch parameters: {@code java -cp <current classpath>
     * -Xmx2g -XX:+ExitOnOutOfMemoryError org.owl4agents.reasoner.isolated.ReasonerWorkerMain}.
     */
    public IsolatedReasonerWorker() {
        this(
            detectJavaLauncher(),
            System.getProperty("java.class.path"),
            "org.owl4agents.reasoner.isolated.ReasonerWorkerMain",
            defaultChildJvmArgs()
        );
    }

    /**
     * Construct an {@code IsolatedReasonerWorker} with explicit launch
     * parameters (used by tests to spawn a stub child JVM).
     *
     * @param javaLauncher  path to the {@code java} executable
     * @param classpath     classpath for the child JVM
     * @param childJvmMainClass fully-qualified main class name
     * @param childJvmArgs  extra JVM args (e.g. {@code -Xmx2g})
     */
    public IsolatedReasonerWorker(String javaLauncher,
                                   String classpath,
                                   String childJvmMainClass,
                                   String[] childJvmArgs) {
        this.javaLauncher = javaLauncher != null ? javaLauncher : detectJavaLauncher();
        this.classpath = classpath != null ? classpath : System.getProperty("java.class.path");
        this.childJvmMainClass = childJvmMainClass != null
            ? childJvmMainClass
            : "org.owl4agents.reasoner.isolated.ReasonerWorkerMain";
        this.childJvmArgs = childJvmArgs != null ? childJvmArgs.clone() : defaultChildJvmArgs();

        // Register a JVM shutdown hook for graceful child termination.
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownHook, "owl4agents-isolated-worker-shutdown"));
    }

    /**
     * Check whether isolated reasoner execution is enabled via the
     * {@value #ISOLATION_ENABLED_PROPERTY} system property.
     *
     * @return {@code true} if isolation is enabled; {@code false} otherwise
     */
    public static boolean isolationEnabled() {
        String raw = System.getProperty(ISOLATION_ENABLED_PROPERTY, "false");
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    /**
     * Submit a reasoner request to the isolated worker JVM.
     *
     * <p>If the child JVM is not yet started, this method lazily
     * spawns it and performs the initial ping health check. If the
     * child JVM has crashed since the last call, a new one is spawned.</p>
     *
     * <p>Concurrency: this method acquires an internal
     * {@link ReentrantLock} to serialize concurrent calls — only one
     * reasoner call runs in the child JVM at a time.</p>
     *
     * @param request the reasoner request (non-null)
     * @return a {@link ServiceResult} containing the
     *         {@link IsolatedReasonerResponse} on success, or an error
     *         with one of: {@link ErrorCode#REASONER_TIMEOUT},
     *         {@link ErrorCode#REASONER_WORKER_CRASHED},
     *         {@link ErrorCode#REASONER_WORKER_PROTOCOL_ERROR},
     *         {@link ErrorCode#REASONER_INTERNAL_ERROR}
     */
    public ServiceResult<IsolatedReasonerResponse> submit(IsolatedReasonerRequest request) {
        if (closed) {
            return ServiceResult.error(ErrorCode.REASONER_INTERNAL_ERROR,
                "IsolatedReasonerWorker has been closed");
        }
        if (request == null) {
            return ServiceResult.error(ErrorCode.INVALID_ARGUMENTS,
                "request must not be null");
        }

        submitLock.lock();
        try {
            // Ensure the child is alive (lazy spawn + crash recovery).
            ChildProcess child;
            try {
                child = ensureChild();
            } catch (WorkerException e) {
                return ServiceResult.error(e.error, e.message);
            }

            // Send the request and wait for the response.
            return sendRequest(child, request);
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * Ensure a healthy child JVM is running. If none exists, spawn one
     * and ping it. If the existing child has died, spawn a new one.
     */
    private ChildProcess ensureChild() throws WorkerException {
        ChildProcess existing = childRef.get();
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        // Either no child or it died — spawn a new one.
        if (existing != null) {
            // Capture the exit info for the crash log before discarding.
            logChildExit(existing);
            childRef.compareAndSet(existing, null);
        }
        ChildProcess child = spawnChild();
        // Health check via ping.
        ServiceResult<IsolatedReasonerResponse> pingResult = sendRequest(child, IsolatedReasonerRequest.ping());
        if (pingResult.isSuccess()) {
            IsolatedReasonerResponse resp = ((ServiceResult.Success<IsolatedReasonerResponse>) pingResult).data();
            if (resp != null && resp.result() != null) {
                LOG.info("isolated reasoner worker healthy (ping ok)");
                return child;
            }
        }
        // Ping failed — destroy the child and return a crash error.
        String errMessage = pingResult.isSuccess()
            ? "ping returned null result"
            : ((ServiceResult.Error<IsolatedReasonerResponse>) pingResult).error().message();
        destroyForcibly(child);
        childRef.compareAndSet(child, null);
        throw new WorkerException(ErrorCode.REASONER_WORKER_CRASHED,
            "Health check (ping) failed: " + errMessage);
    }

    /**
     * Send a single request to the child JVM and wait for the response
     * (with timeout).
     */
    private ServiceResult<IsolatedReasonerResponse> sendRequest(ChildProcess child,
                                                                 IsolatedReasonerRequest request) {
        long timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 30_000L;
        String requestJson = serializeRequest(request);

        // Submit the I/O work to a background thread so we can enforce a timeout.
        CompletableFuture<ServiceResult<IsolatedReasonerResponse>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return doRequest(child, requestJson);
            } catch (IOException e) {
                // The child may have crashed mid-request — check exit.
                if (!child.isAlive()) {
                    int exitCode = child.waitForQuietly();
                    String stderrTail = child.captureStderrTail(2048);
                    return ServiceResult.error(ErrorCode.REASONER_WORKER_CRASHED,
                        "Child JVM exited during request: exitCode=" + exitCode
                            + ", stderr=" + stderrTail);
                }
                return ServiceResult.error(ErrorCode.REASONER_INTERNAL_ERROR,
                    "I/O error communicating with child: " + e.getMessage());
            }
        }, ioExecutor);

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            // Per spec: destroyForcibly on timeout.
            destroyForcibly(child);
            childRef.compareAndSet(child, null);
            LOG.warning("isolated reasoner call timed out after " + timeoutMs + "ms; child destroyed");
            return ServiceResult.error(ErrorCode.REASONER_TIMEOUT,
                "Reasoner call exceeded timeout of " + timeoutMs + "ms; child JVM forcibly terminated");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ServiceResult.error(ErrorCode.REASONER_INTERNAL_ERROR,
                "Interrupted while waiting for reasoner response");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            String msg = "Unexpected error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            LOG.log(Level.WARNING, msg, cause);
            return ServiceResult.error(ErrorCode.REASONER_INTERNAL_ERROR, msg);
        }
    }

    /**
     * Synchronously send a JSON request line and read the JSON response
     * line from the child JVM.
     */
    private ServiceResult<IsolatedReasonerResponse> doRequest(ChildProcess child, String requestJson)
            throws IOException {
        // Write the request line.
        child.writer().print(requestJson);
        child.writer().print('\n');
        child.writer().flush();

        // Read the response line.
        String responseLine = child.reader().readLine();
        if (responseLine == null) {
            // EOF — child exited without responding.
            int exitCode = child.waitForQuietly();
            String stderrTail = child.captureStderrTail(2048);
            return ServiceResult.error(ErrorCode.REASONER_WORKER_CRASHED,
                "Child JVM closed stdout without responding: exitCode=" + exitCode
                    + ", stderr=" + stderrTail);
        }
        if (responseLine.isBlank()) {
            return ServiceResult.error(ErrorCode.REASONER_WORKER_PROTOCOL_ERROR,
                "Child JVM produced a blank line on stdout");
        }
        try {
            JsonObject json = JsonParser.parseString(responseLine).getAsJsonObject();
            IsolatedReasonerResponse response = deserializeResponse(json);
            return ServiceResult.success(response, org.owl4agents.core.ResultMetadata.empty());
        } catch (JsonSyntaxException | IllegalStateException e) {
            String truncated = responseLine.length() > 1024
                ? responseLine.substring(0, 1024) + "...(truncated)"
                : responseLine;
            return ServiceResult.error(ErrorCode.REASONER_WORKER_PROTOCOL_ERROR,
                "Malformed JSON from child JVM: " + e.getMessage() + " — line: " + truncated);
        }
    }

    /**
     * Spawn a fresh child JVM.
     */
    private ChildProcess spawnChild() throws WorkerException {
        ProcessBuilder pb = new ProcessBuilder();
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaLauncher);
        for (String arg : childJvmArgs) {
            cmd.add(arg);
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(childJvmMainClass);
        pb.command(cmd);
        pb.redirectErrorStream(false); // stderr separate from stdout

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new WorkerException(ErrorCode.REASONER_INTERNAL_ERROR,
                "Failed to spawn child JVM: " + e.getMessage());
        }
        ChildProcess child = new ChildProcess(process);
        childRef.set(child);
        LOG.info("spawned isolated reasoner worker: cmd=" + String.join(" ", cmd));
        return child;
    }

    /**
     * Forcibly destroy the child JVM.
     */
    private void destroyForcibly(ChildProcess child) {
        try {
            Process p = child.process();
            p.destroyForcibly().waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Log the exit code and stderr of a dead child JVM (for crash
     * recovery diagnostics).
     */
    private void logChildExit(ChildProcess child) {
        try {
            int exit = child.waitForQuietly();
            String stderr = child.captureStderrTail(2048);
            LOG.warning("child JVM exited: exitCode=" + exit + ", stderr=" + stderr);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "failed to log child exit", e);
        }
    }

    /**
     * Gracefully shut down the worker: destroy the child JVM and close
     * internal thread pools. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        shutdownHook();
        ioExecutor.shutdownNow();
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.warning("I/O executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * JVM shutdown hook: gracefully terminate the child JVM. Calls
     * {@code Process.destroy()}; if the child does not exit within
     * {@link #SHUTDOWN_TIMEOUT_MS}, calls {@code destroyForcibly()}.
     */
    private void shutdownHook() {
        ChildProcess child = childRef.getAndSet(null);
        if (child == null) {
            return;
        }
        try {
            child.process().destroy();
            if (!child.process().waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                child.process().destroyForcibly().waitFor(1, TimeUnit.SECONDS);
                LOG.warning("child JVM forcibly terminated after " + SHUTDOWN_TIMEOUT_MS + "ms");
            } else {
                LOG.info("child JVM exited gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.process().destroyForcibly();
        }
    }

    /**
     * Serialize a request to a single-line JSON string.
     */
    private String serializeRequest(IsolatedReasonerRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("ontologyPath", request.ontologyPath());
        root.addProperty("reasonerName", request.reasonerName());
        root.addProperty("operation", request.operation());
        if (request.axiom() != null) {
            root.addProperty("axiom", request.axiom());
        } else {
            root.add("axiom", null);
        }
        root.addProperty("timeoutMs", request.timeoutMs());
        return GSON.toJson(root);
    }

    /**
     * Deserialize a JSON response into an {@link IsolatedReasonerResponse}.
     */
    static IsolatedReasonerResponse deserializeResponse(JsonObject json) {
        long elapsedMs = json.has("elapsedMs") && json.get("elapsedMs").isJsonPrimitive()
            ? json.get("elapsedMs").getAsLong()
            : 0L;
        Object result = null;
        if (json.has("result") && !json.get("result").isJsonNull()) {
            // Convert to a Java object via Gson's tree-to-object conversion.
            result = GSON.fromJson(json.get("result"), Object.class);
        }
        IsolatedReasonerResponse.Error error = null;
        if (json.has("error") && !json.get("error").isJsonNull()) {
            JsonObject errObj = json.getAsJsonObject("error");
            String code = errObj.has("code") ? errObj.get("code").getAsString() : "REASONER_INTERNAL_ERROR";
            String message = errObj.has("message") ? errObj.get("message").getAsString() : "";
            error = new IsolatedReasonerResponse.Error(code, message);
        }
        return new IsolatedReasonerResponse(result, elapsedMs, error);
    }

    /**
     * Detect the {@code java} launcher path from {@code java.home}.
     */
    private static String detectJavaLauncher() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(javaHome, "bin", "java");
            if (java.nio.file.Files.exists(p)) {
                return p.toString();
            }
            p = java.nio.file.Path.of(javaHome, "bin", "java.exe");
            if (java.nio.file.Files.exists(p)) {
                return p.toString();
            }
        }
        return "java";
    }

    /**
     * Default child JVM args: 2 GB heap + ExitOnOutOfMemoryError.
     */
    private static String[] defaultChildJvmArgs() {
        return new String[] {
            "-Xmx2g",
            "-XX:+ExitOnOutOfMemoryError"
        };
    }

    /**
     * Internal wrapper around a {@link Process} and its I/O streams.
     * Drains stderr in a background thread so it does not fill the OS
     * pipe buffer and deadlock the child.
     */
    private static final class ChildProcess {
        private final Process process;
        private final PrintWriter writer;
        private final BufferedReader reader;
        private final java.util.concurrent.ConcurrentLinkedQueue<String> stderrLines =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final Thread stderrDrainThread;

        ChildProcess(Process process) {
            this.process = process;
            OutputStream os = process.getOutputStream();
            InputStream is = process.getInputStream();
            this.writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(os, StandardCharsets.UTF_8)), false);
            this.reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            // Drain stderr in a background thread to prevent the child
            // from blocking on a full stderr pipe buffer.
            this.stderrDrainThread = new Thread(this::drainStderr, "owl4agents-isolated-worker-stderr");
            this.stderrDrainThread.setDaemon(true);
            this.stderrDrainThread.start();
        }

        Process process() {
            return process;
        }

        PrintWriter writer() {
            return writer;
        }

        BufferedReader reader() {
            return reader;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        int waitForQuietly() {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        /**
         * Capture the last {@code maxChars} characters of stderr output.
         */
        String captureStderrTail(int maxChars) {
            StringBuilder sb = new StringBuilder();
            for (String line : stderrLines) {
                sb.append(line).append('\n');
            }
            String all = sb.toString();
            if (all.length() <= maxChars) {
                return all;
            }
            return "..." + all.substring(all.length() - maxChars);
        }

        private void drainStderr() {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    stderrLines.add(line);
                    // Keep the buffer bounded — drop old lines beyond 1000.
                    while (stderrLines.size() > 1000) {
                        stderrLines.poll();
                    }
                }
            } catch (IOException e) {
                // Pipe closed — child exited.
            }
        }
    }

    /** Internal exception carrying a structured error to the caller. */
    private static final class WorkerException extends Exception {
        final ErrorCode error;
        final String message;
        WorkerException(ErrorCode error, String message) {
            super(message);
            this.error = error;
            this.message = message;
        }
    }
}
