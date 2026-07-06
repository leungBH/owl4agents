package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8 unit tests for {@link SseFrame}. Covers the spec.md scenarios
 * for SSE frame byte-level formatting: event frames (id, event, data),
 * heartbeat comment frames, resume-ack frames, and ready frames.
 */
@DisplayName("SSE frame formatting tests")
class SseFrameTest {

    private static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── 1. event() ──

    @Nested
    @DisplayName("event()")
    class EventTests {

        @Test
        @DisplayName("event() emits a frame in the order id → event → data, terminated by a blank line")
        void eventEmitsOrderedFields() {
            byte[] frame = SseFrame.event("message", 7, "{\"hello\":\"world\"}");
            String s = decode(frame);
            // Order matters: id first, then event, then data, then blank line
            int idIdx = s.indexOf("id: 7");
            int eventIdx = s.indexOf("event: message");
            int dataIdx = s.indexOf("data: {\"hello\":\"world\"}");
            int blankIdx = s.indexOf("\n\n");
            assertTrue(idIdx >= 0, "missing id field: " + s);
            assertTrue(eventIdx > idIdx, "event must come after id: " + s);
            assertTrue(dataIdx > eventIdx, "data must come after event: " + s);
            assertTrue(blankIdx > dataIdx, "frame must end with blank line: " + s);
            assertEquals(s.length() - 2, blankIdx,
                "blank line must be at the very end of the frame");
        }

        @Test
        @DisplayName("event() with null data emits an empty data field")
        void eventWithNullDataEmitsEmptyData() {
            byte[] frame = SseFrame.event("message", 1, null);
            String s = decode(frame);
            assertTrue(s.contains("data: \n"), "null data must become empty data field: " + s);
        }

        @Test
        @DisplayName("event() uses \\n line endings (not \\r\\n) per SSE spec")
        void eventUsesLfLineEndings() {
            byte[] frame = SseFrame.event("message", 0, "{}");
            String s = decode(frame);
            assertFalse(s.contains("\r"), "SSE frames must not contain CR: " + s);
        }

        @Test
        @DisplayName("event() is UTF-8 encoded")
        void eventIsUtf8Encoded() {
            byte[] frame = SseFrame.event("message", 0, "{\"key\":\"中文\"}");
            String s = decode(frame);
            assertTrue(s.contains("中文"), "UTF-8 data must round-trip: " + s);
        }
    }

    // ── 2. comment() ──

    @Nested
    @DisplayName("comment()")
    class CommentTests {

        @Test
        @DisplayName("comment() emits ':<marker>\\n\\n' (RFC 8895 keep-alive)")
        void commentEmitsKeepAliveFrame() {
            byte[] frame = SseFrame.comment("ping");
            String s = decode(frame);
            assertEquals(": ping\n\n", s);
        }

        @Test
        @DisplayName("comment() with null or empty marker uses 'ping' as the default")
        void commentDefaultsToPing() {
            assertEquals(": ping\n\n", decode(SseFrame.comment(null)));
            assertEquals(": ping\n\n", decode(SseFrame.comment("")));
        }

        @Test
        @DisplayName("comment() rejects markers containing newline or colon")
        void commentRejectsInvalidMarkers() {
            assertThrows(IllegalArgumentException.class, () -> SseFrame.comment("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> SseFrame.comment("a:b"));
        }
    }

    // ── 3. resumeAck() ──

    @Nested
    @DisplayName("resumeAck()")
    class ResumeAckTests {

        @Test
        @DisplayName("resumeAck(0) emits {\"resumed\":true,\"lastEventId\":0}")
        void resumeAckZero() {
            byte[] frame = SseFrame.resumeAck(0);
            String s = decode(frame);
            assertTrue(s.contains("\"resumed\":true"), "must include resumed:true: " + s);
            assertTrue(s.contains("\"lastEventId\":0"), "must echo lastEventId=0: " + s);
        }

        @Test
        @DisplayName("resumeAck(42) echoes the supplied id")
        void resumeAckEchoesId() {
            byte[] frame = SseFrame.resumeAck(42);
            String s = decode(frame);
            assertTrue(s.contains("\"lastEventId\":42"), "must echo lastEventId=42: " + s);
        }

        @Test
        @DisplayName("resumeAck(-1) throws IllegalArgumentException")
        void resumeAckNegativeRejected() {
            assertThrows(IllegalArgumentException.class, () -> SseFrame.resumeAck(-1));
        }
    }

    // ── 4. ready() ──

    @Nested
    @DisplayName("ready()")
    class ReadyTests {

        @Test
        @DisplayName("ready() emits {\"type\":\"ready\",\"sessionId\":\"<uuid>\"} as the first event")
        void readyEmitsSessionId() {
            String sessionId = UUID.randomUUID().toString();
            byte[] frame = SseFrame.ready(sessionId);
            String s = decode(frame);
            assertTrue(s.contains("\"type\":\"ready\""), "must include type:ready: " + s);
            assertTrue(s.contains("\"sessionId\":\"" + sessionId + "\""),
                "must echo the sessionId: " + s);
            // The ready frame is the first event — id MUST be 0 so the
            // client's Last-Event-ID counter can start at 0.
            assertTrue(s.startsWith("id: 0\n"), "ready frame must have id 0: " + s);
        }
    }
}
