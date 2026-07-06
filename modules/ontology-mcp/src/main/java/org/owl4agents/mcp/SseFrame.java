package org.owl4agents.mcp;

import java.nio.charset.StandardCharsets;

/**
 * Value type that produces a single Server-Sent Events (SSE) frame as a
 * UTF-8 byte array. Three frame kinds are supported per the v0.8
 * Streamable HTTP transport contract:
 *
 * <ul>
 *   <li>{@link #event(String, int, String) event} —
 *       {@code id: <id>\nevent: <name>\ndata: <json>\n\n}</li>
 *   <li>{@link #comment(String) comment} — {@code :<space><marker>\n\n}
 *       (used for SSE heartbeats per RFC 8895)</li>
 *   <li>{@link #resumeAck(int) resumeAck} —
 *       {@code id: 0\nevent: message\ndata: {"resumed":true,"lastEventId":<n>}\n\n}
 *       (emitted on {@code Last-Event-ID} resume; closes the stream after writing)</li>
 *   <li>{@link #ready(String) ready} —
 *       {@code id: 0\nevent: message\ndata: {"type":"ready","sessionId":"<uuid>"}\n\n}
 *       (emitted as the first event when a {@code GET /mcp} stream is opened)</li>
 * </ul>
 *
 * <p>All frames use {@code \n} line endings and end with a blank line
 * ({@code \n\n}), per the SSE specification. Frames are written by a
 * single dedicated thread per stream (see design.md D11) to prevent
 * JDK 22 {@code getResponseBody} multi-write corruption.</p>
 */
public final class SseFrame {

    private SseFrame() {
        // utility
    }

    /**
     * Build a standard SSE event frame.
     *
     * @param eventName the {@code event:} field (e.g. {@code "message"})
     * @param id        the {@code id:} field (a non-negative monotonic integer)
     * @param jsonData  the {@code data:} payload (a JSON string; not double-encoded)
     * @return the frame as a UTF-8 byte array, terminated by {@code \n\n}
     */
    public static byte[] event(String eventName, int id, String jsonData) {
        StringBuilder sb = new StringBuilder(64 + (jsonData == null ? 0 : jsonData.length()));
        sb.append("id: ").append(id).append('\n');
        sb.append("event: ").append(eventName).append('\n');
        sb.append("data: ").append(jsonData == null ? "" : jsonData).append('\n');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build an SSE comment frame (RFC 8895 keep-alive). Clients SHOULD NOT
     * dispatch comment frames as events. Used for heartbeats at
     * {@code --sse-heartbeat-seconds} intervals.
     *
     * @param marker a short marker (e.g. {@code "ping"}); must not contain
     *               {@code \n} or {@code :}
     * @return the frame as a UTF-8 byte array, terminated by {@code \n\n}
     */
    public static byte[] comment(String marker) {
        String safe = (marker == null || marker.isEmpty()) ? "ping" : marker;
        if (safe.indexOf('\n') >= 0 || safe.indexOf(':') >= 0) {
            throw new IllegalArgumentException("SSE comment marker must not contain newline or colon");
        }
        return (": " + safe + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build the {@code resumed:true} ack frame. Emitted on a
     * {@code Last-Event-ID} reconnect; the stream is closed by the caller
     * after writing this frame. The v0.8 contract deliberately does NOT
     * replay history — the client retries any in-flight request from its
     * last acknowledged response (idempotent retry per JSON-RPC 2.0).
     *
     * @param lastEventId the {@code Last-Event-ID} the client sent
     * @return the frame as a UTF-8 byte array, terminated by {@code \n\n}
     */
    public static byte[] resumeAck(int lastEventId) {
        if (lastEventId < 0) {
            throw new IllegalArgumentException("lastEventId must be non-negative");
        }
        String data = "{\"resumed\":true,\"lastEventId\":" + lastEventId + "}";
        return event("message", 0, data);
    }

    /**
     * Build the {@code ready} frame emitted as the first event of a
     * {@code GET /mcp} SSE stream. The {@code sessionId} echoes the
     * {@code Mcp-Session-Id} that the client should use on subsequent
     * {@code POST /mcp} requests.
     *
     * @param sessionId the Mcp-Session-Id (UUID v4) bound to the stream
     * @return the frame as a UTF-8 byte array, terminated by {@code \n\n}
     */
    public static byte[] ready(String sessionId) {
        // Minimal hand-rolled JSON to avoid pulling Gson into this class.
        // sessionId is a UUID v4 string (validated by the caller).
        StringBuilder sb = new StringBuilder(64 + sessionId.length());
        sb.append("{\"type\":\"ready\",\"sessionId\":\"").append(sessionId).append("\"}");
        return event("message", 0, sb.toString());
    }
}
