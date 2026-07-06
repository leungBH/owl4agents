package org.owl4agents.mcp;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One MCP session bound to a {@code Mcp-Session-Id} (UUID v4). Each
 * session may have zero or more concurrent SSE streams open against it
 * (fan-out delivery — see design.md D3 / D5).
 *
 * <p>Lifecycle: created lazily by the first request that carries the
 * session id (typically a {@code POST /mcp} {@code initialize}), updated
 * on every access via {@link #touch()}, and removed by
 * {@link McpSessionManager} when (a) the last open stream closes, or
 * (b) {@link #isExpired} returns true at the moment of access.</p>
 *
 * <p>Concurrency: a single {@link ReentrantLock} guards mutations of
 * {@link #openStreams}. Reads of the {@code lastAccessAt} field are
 * lock-free via {@code volatile}.</p>
 */
public final class McpSession {

    private final String sessionId;
    private volatile Instant lastAccessAt;
    private final Set<McpSseStream> openStreams = ConcurrentHashMap.newKeySet();
    private final ReentrantLock streamsLock = new ReentrantLock();
    private volatile boolean closed;

    /**
     * Construct a new session.
     *
     * @param sessionId the Mcp-Session-Id (caller validates it is a UUID v4)
     */
    public McpSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        // Parse to validate UUID v4 format; ignore the parsed value.
        try {
            UUID parsed = UUID.fromString(sessionId);
            if (parsed.version() != 4) {
                throw new IllegalArgumentException("sessionId must be UUID v4");
            }
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException("sessionId must be a valid UUID v4: " + iae.getMessage());
        }
        this.sessionId = sessionId;
        this.lastAccessAt = Instant.now();
    }

    /** @return the opaque Mcp-Session-Id handle. */
    public String sessionId() {
        return sessionId;
    }

    /** @return the last-access instant. Updated by {@link #touch()}. */
    public Instant lastAccessAt() {
        return lastAccessAt;
    }

    /** Update {@code lastAccessAt} to {@link Instant#now()}. */
    public void touch() {
        lastAccessAt = Instant.now();
    }

    /**
     * @param ttl the session TTL
     * @return true if the session has been idle for at least the TTL
     */
    public boolean isExpired(java.time.Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return true;
        }
        Instant cutoff = Instant.now().minus(ttl);
        return lastAccessAt.isBefore(cutoff);
    }

    /** @return a thread-safe view of the currently-open SSE streams. */
    public Set<McpSseStream> openStreams() {
        return openStreams;
    }

    /**
     * Register an open stream. The caller is responsible for the matching
     * {@link #unregisterStream(McpSseStream)} call.
     *
     * @return true if the stream was added, false if the session is already
     *         closed
     */
    public boolean registerStream(McpSseStream stream) {
        if (closed) {
            return false;
        }
        streamsLock.lock();
        try {
            if (closed) {
                return false;
            }
            return openStreams.add(stream);
        } finally {
            streamsLock.unlock();
        }
    }

    /**
     * Unregister a previously-registered stream. Idempotent: a second
     * call with the same stream is a no-op.
     */
    public void unregisterStream(McpSseStream stream) {
        openStreams.remove(stream);
    }

    /**
     * Mark the session as closed. Idempotent. Open streams are NOT
     * closed by this call; the caller (typically
     * {@link McpSessionManager}) is responsible for closing them.
     */
    public void close() {
        closed = true;
    }

    /** @return true if {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }
}
