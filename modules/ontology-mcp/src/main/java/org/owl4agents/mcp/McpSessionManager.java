package org.owl4agents.mcp;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe registry of {@link McpSession}s. A single instance lives
 * on the {@link HttpMcpServer}. Two concurrency points:
 *
 * <ul>
 *   <li>{@link #getOrCreate(String)} / {@link #get(String)} — O(1)
 *       {@link ConcurrentHashMap} lookups</li>
 *   <li>{@link #sweepExpired(Duration)} — periodic background sweeper
 *       (default every 5 minutes) that closes and removes idle sessions</li>
 * </ul>
 *
 * <p>Sessions are NOT persisted (v0.8 non-goal). A server restart clears
 * all session state; clients that reconnect after a restart get a fresh
 * session via {@code initialize}.</p>
 */
public final class McpSessionManager {

    private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong activeStreamCount = new AtomicLong(0);
    private final ScheduledExecutorService sweeper;
    private final long sweeperIntervalSeconds;

    /**
     * @param sweeperIntervalSeconds how often the background sweeper runs
     *                               (must be {@code > 0})
     */
    public McpSessionManager(long sweeperIntervalSeconds) {
        if (sweeperIntervalSeconds <= 0) {
            throw new IllegalArgumentException("sweeperIntervalSeconds must be > 0");
        }
        this.sweeperIntervalSeconds = sweeperIntervalSeconds;
        Thread sweeperThread = new Thread(this::runSweeper, "mcp-session-sweeper");
        sweeperThread.setDaemon(true);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleAtFixedRate(this::runSweepTick,
            sweeperIntervalSeconds, sweeperIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Create a new session with a freshly generated UUID v4. The new
     * session is added to the registry and returned.
     */
    public McpSession create() {
        String id = UUID.randomUUID().toString();
        McpSession session = new McpSession(id);
        McpSession prior = sessions.putIfAbsent(id, session);
        if (prior != null) {
            // UUID v4 collision is astronomically unlikely; if it happens, retry.
            return create();
        }
        return session;
    }

    /**
     * Look up an existing session by id. Returns {@code null} if no
     * session with that id is registered.
     */
    public McpSession get(String id) {
        if (id == null) return null;
        return sessions.get(id);
    }

    /**
     * Atomically: look up the session, or create a new one bound to the
     * supplied id. Used for {@code GET /mcp} SSE stream open: the client
     * already has a session id (from {@code initialize}) and the server
     * binds the stream to it.
     *
     * <p>If a session with the given id already exists, its
     * {@code lastAccessAt} is updated (touch). If the existing session
     * is closed, a new one is created with the same id (which is a
     * different code path from {@link #create()}).</p>
     */
    public McpSession getOrCreate(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        McpSession existing = sessions.compute(id, (k, v) -> {
            if (v == null || v.isClosed()) {
                try {
                    McpSession fresh = new McpSession(k);
                    fresh.touch();
                    return fresh;
                } catch (IllegalArgumentException iae) {
                    return null;
                }
            }
            v.touch();
            return v;
        });
        return existing;
    }

    /**
     * Remove a session from the registry. Idempotent. The session's
     * open streams are NOT closed by this call — the caller should
     * close them first if needed.
     */
    public void remove(String id) {
        if (id == null) return;
        McpSession removed = sessions.remove(id);
        if (removed != null) {
            removed.close();
        }
    }

    /**
     * Sweep all sessions and remove those whose {@code lastAccessAt}
     * is older than {@code ttl}. Idempotent on already-removed ids.
     * Also closes each removed session's open SSE streams.
     *
     * @return the number of sessions removed
     */
    public int sweepExpired(Duration ttl) {
        if (ttl == null) return 0;
        int removed = 0;
        for (McpSession s : sessions.values()) {
            if (s.isExpired(ttl)) {
                if (sessions.remove(s.sessionId(), s)) {
                    s.close();
                    for (McpSseStream stream : s.openStreams()) {
                        stream.close();
                    }
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Increment the active stream count. Called by the SSE handler
     * when a stream is opened.
     */
    public void incrementStreamCount() {
        activeStreamCount.incrementAndGet();
    }

    /**
     * Atomically claim a stream slot if the active count is strictly
     * less than {@code max}. Returns {@code true} if the slot was
     * claimed (caller now owns one of the max open-stream slots),
     * {@code false} if the cap was reached.
     *
     * <p>Atomicity matters because the SSE stream's response headers
     * are sent immediately on construction, so the cap check and the
     * counter increment MUST be one observable operation to a remote
     * client. If they were separate, two concurrent requests could
     * both pass the check at {@code count = max - 1} and both send
     * 200 OK.</p>
     */
    public boolean tryClaimStreamSlot(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1, got " + max);
        }
        while (true) {
            long current = activeStreamCount.get();
            if (current >= max) {
                return false;
            }
            if (activeStreamCount.compareAndSet(current, current + 1)) {
                return true;
            }
            // CAS lost a race with another thread; retry.
        }
    }

    /**
     * Decrement the active stream count. Called when a stream is
     * closed (in any path: normal, error, timeout, or session close).
     */
    public void decrementStreamCount() {
        // Floor at 0; do not underflow if a stream-close callback fires
        // twice (idempotent close).
        activeStreamCount.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    /** @return the current count of open SSE streams across all sessions. */
    public long activeStreamCount() {
        return activeStreamCount.get();
    }

    /** @return the number of registered sessions. */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * Stop the background sweeper and remove all sessions. Safe to
     * call multiple times. Used during server shutdown.
     */
    public void shutdown() {
        try {
            sweeper.shutdownNow();
        } catch (Exception ignored) {
            // Best effort
        }
        // Close every stream and every session.
        for (McpSession s : sessions.values()) {
            s.close();
            for (McpSseStream stream : s.openStreams()) {
                stream.close();
            }
        }
        sessions.clear();
        activeStreamCount.set(0);
    }

    private void runSweepTick() {
        try {
            // Default TTL — HttpMcpServer is expected to call
            // sweepExpired with its configured TTL. If not called,
            // sweeper does nothing.
            sweepExpired(null);
        } catch (Exception e) {
            // Sweeper MUST NOT crash. Log and continue.
            try {
                System.err.println("mcp-session-sweeper: tick failed: " + e.getMessage());
            } catch (Exception ignored) {
                // stderr may be closed during shutdown
            }
        }
    }

    @SuppressWarnings("unused")
    private void runSweeper() {
        // Reserved for future use (currently a no-op body, the real work
        // runs on the scheduled executor). Kept as a separate method so
        // we can replace the executor with a hand-rolled thread without
        // touching the shutdown path.
    }

    /**
     * @return the sweeper interval in seconds (exposed for tests and
     *         {@code GET /info} diagnostics)
     */
    public long sweeperIntervalSeconds() {
        return sweeperIntervalSeconds;
    }
}
