package org.owl4agents.mcp;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One Server-Sent Events stream backed by a single
 * {@link HttpExchange} response body. The class is intentionally
 * NOT internally thread-safe: all writes ({@link #writeEvent},
 * {@link #writeHeartbeat}, {@link #writeReady},
 * {@link #writeResumeAck}, {@link #close}) MUST be invoked on a
 * single dedicated writer thread per stream. This is the
 * single-writer invariant required by JDK 22's
 * {@code HttpExchange.getResponseBody()} (see design.md D11).
 *
 * <p>The lifecycle is: {@code open} → repeated writes →
 * {@link #close()} on normal exit, IOException during write, or
 * server shutdown. {@link #close()} is idempotent.</p>
 */
public final class McpSseStream implements AutoCloseable {

    private final String streamId;
    private final McpSession session;
    private final HttpExchange exchange;
    private final OutputStream body;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;
    private long eventIdCounter = 0L;

    /**
     * Construct a new SSE stream. Sends the standard response headers
     * ({@code 200 OK}, {@code text/event-stream}, {@code Mcp-Session-Id})
     * and switches the exchange to chunked transfer encoding
     * (response length 0).
     */
    public McpSseStream(McpSession session, HttpExchange exchange) throws IOException {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (exchange == null) {
            throw new IllegalArgumentException("exchange must not be null");
        }
        this.streamId = UUID.randomUUID().toString();
        this.session = session;
        this.exchange = exchange;
        // Standard SSE response headers. Mcp-Session-Id is the same id
        // the client already has; we echo it so the client can recover
        // it from a fresh response.
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.getResponseHeaders().add("Mcp-Session-Id", session.sessionId());
        // 0 length == chunked transfer encoding in com.sun.net.httpserver.
        exchange.sendResponseHeaders(200, 0);
        this.body = exchange.getResponseBody();
    }

    /** @return the stream's UUID v4 (debug-only). */
    public String streamId() {
        return streamId;
    }

    /** @return the session this stream is bound to. */
    public McpSession session() {
        return session;
    }

    /** @return true if {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Allocate the next monotonic event id for this stream. Per-stream
     * counters are fine because clients use the {@code id:} only within
     * a single connection.
     */
    public int nextEventId() {
        return (int) (++eventIdCounter);
    }

    /**
     * Write a standard {@code event: message} frame. MUST be called on
     * the stream's writer thread. The supplied JSON is treated as opaque
     * (the caller is responsible for JSON-escaping).
     */
    public void writeEvent(int id, String jsonData) throws IOException {
        writeBytes(SseFrame.event("message", id, jsonData));
    }

    /**
     * Write the {@code ready} frame as the first event of the stream.
     */
    public void writeReady() throws IOException {
        writeBytes(SseFrame.ready(session.sessionId()));
    }

    /**
     * Write the {@code resumed:true} ack frame. Per the v0.8 contract,
     * the stream is closed by the caller after writing.
     */
    public void writeResumeAck(int lastEventId) throws IOException {
        writeBytes(SseFrame.resumeAck(lastEventId));
    }

    /**
     * Write an SSE comment frame (heartbeat). MUST be called on the
     * stream's writer thread.
     */
    public void writeHeartbeat() throws IOException {
        writeBytes(SseFrame.comment("ping"));
    }

    /**
     * Liveness probe: flushes the response body. The JDK
     * {@code com.sun.net.httpserver} {@code OutputStream.flush()} throws
     * {@link IOException} when the client has disconnected (broken pipe
     * / reset by peer), so this is a cheap way for the writer loop to
     * detect disconnect between heartbeats. Without this probe, the
     * {@code active_sse_connections} counter would stay inflated for
     * up to one heartbeat interval (default 15s) after a client drops
     * the connection.
     *
     * <p>Safe to call on the writer thread; uses the same write lock
     * as {@link #writeBytes(byte[])}.</p>
     */
    public void detectClientDisconnect() throws IOException {
        if (closed) {
            throw new IOException("stream " + streamId + " is closed");
        }
        writeLock.lock();
        try {
            body.flush();
        } catch (IOException ioe) {
            closed = true;
            throw ioe;
        } finally {
            writeLock.unlock();
        }
    }

    private void writeBytes(byte[] frame) throws IOException {
        if (closed) {
            throw new IOException("stream " + streamId + " is closed");
        }
        writeLock.lock();
        try {
            body.write(frame);
            body.flush();
        } catch (IOException ioe) {
            closed = true;
            throw ioe;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Close the stream: flush, close the response body, and mark the
     * stream as closed. Idempotent. Does NOT remove the stream from
     * its session — the caller ({@link McpSessionManager} or the SSE
     * handler) is responsible for that.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        writeLock.lock();
        try {
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // Best effort — stream is already in a closing state.
            }
        } finally {
            writeLock.unlock();
        }
    }
}
