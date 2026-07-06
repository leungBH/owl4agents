package org.owl4agents.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8 unit tests for {@link McpSession}. Covers the spec.md scenarios
 * for session-id validation, last-access tracking, expiry semantics,
 * and close idempotency.
 *
 * <p>Note: stream register/unregister paths that require a real
 * {@link McpSseStream} (and therefore a real {@link com.sun.net.httpserver.HttpExchange})
 * are exercised in {@link HttpMcpServerSseTest} as integration tests.
 * This file focuses on the session-only paths that can be unit-tested
 * without a running HTTP exchange.</p>
 */
@DisplayName("MCP session tests")
class McpSessionTest {

    private McpSessionManager manager;
    private McpSession session;

    @BeforeEach
    void setUp() {
        manager = new McpSessionManager(60L);
        session = new McpSession(UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.shutdown();
    }

    // ── 1. Constructor validation ──

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor rejects null sessionId")
        void constructorRejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> new McpSession(null));
        }

        @Test
        @DisplayName("Constructor rejects empty sessionId")
        void constructorRejectsEmpty() {
            assertThrows(IllegalArgumentException.class, () -> new McpSession(""));
        }

        @Test
        @DisplayName("Constructor rejects non-UUID sessionId")
        void constructorRejectsNonUuid() {
            assertThrows(IllegalArgumentException.class, () -> new McpSession("not-a-uuid"));
        }

        @Test
        @DisplayName("Constructor rejects UUID v1 (non-v4) sessionId")
        void constructorRejectsUuidV1() {
            // Build a UUID v1 manually: the version nibble is bits 12-15 of
            // the most-significant long (RFC 4122 §4.1.2). We set those bits
            // to 0x1 and the variant nibble (top 2 bits of least-significant
            // long) to 0b10, which is what `UUID.fromString` expects.
            java.util.UUID v1 = new java.util.UUID(
                0x0000_0000_0000_1000L, // time_hi_and_version = 0x1000 → version=1
                0x8000_0000_0000_0000L); // top 2 bits = 10 → variant=10xx
            assertEquals(1, v1.version(),
                "Sanity check: the hand-built UUID must be version 1");
            assertThrows(IllegalArgumentException.class, () -> new McpSession(v1.toString()));
        }

        @Test
        @DisplayName("Constructor accepts a valid UUID v4 string")
        void constructorAcceptsUuidV4() {
            String id = UUID.randomUUID().toString();
            assertEquals(4, UUID.fromString(id).version());
            McpSession s = new McpSession(id);
            assertEquals(id, s.sessionId());
        }
    }

    // ── 2. touch() / lastAccessAt() ──

    @Nested
    @DisplayName("touch() / lastAccessAt()")
    class TouchTests {

        @Test
        @DisplayName("lastAccessAt is initialized to 'now' at construction")
        void lastAccessAtInitializedToNow() {
            Instant before = Instant.now();
            McpSession s = new McpSession(UUID.randomUUID().toString());
            Instant after = Instant.now();
            assertTrue(!s.lastAccessAt().isBefore(before),
                "lastAccessAt must be >= construction start");
            assertTrue(!s.lastAccessAt().isAfter(after),
                "lastAccessAt must be <= construction end");
        }

        @Test
        @DisplayName("touch() updates lastAccessAt to a strictly later instant")
        void touchUpdatesLastAccess() throws Exception {
            Instant before = session.lastAccessAt();
            Thread.sleep(5);
            session.touch();
            assertTrue(session.lastAccessAt().isAfter(before),
                "touch() must advance lastAccessAt");
        }
    }

    // ── 3. isExpired() ──

    @Nested
    @DisplayName("isExpired()")
    class IsExpiredTests {

        @Test
        @DisplayName("A fresh session is NOT expired under a positive TTL")
        void freshSessionNotExpired() {
            assertFalse(session.isExpired(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("A session whose lastAccessAt is older than the TTL IS expired")
        void oldSessionIsExpired() throws Exception {
            java.lang.reflect.Field f = McpSession.class.getDeclaredField("lastAccessAt");
            f.setAccessible(true);
            f.set(session, Instant.now().minus(Duration.ofMinutes(5)));
            assertTrue(session.isExpired(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("isExpired(null/zero/negative) returns true (defensive: invalid TTL = expired)")
        void isExpiredWithInvalidTtlReturnsTrue() {
            assertTrue(session.isExpired(null));
            assertTrue(session.isExpired(Duration.ZERO));
            assertTrue(session.isExpired(Duration.ofSeconds(-1)));
        }
    }

    // ── 4. openStreams() ──

    @Nested
    @DisplayName("openStreams()")
    class OpenStreamsTests {

        @Test
        @DisplayName("A newly-constructed session has an empty openStreams set")
        void openStreamsStartsEmpty() {
            assertNotNull(session.openStreams());
            assertTrue(session.openStreams().isEmpty());
        }
    }

    // ── 5. close() ──

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @DisplayName("close() marks the session as closed")
        void closeMarksSessionClosed() {
            assertFalse(session.isClosed());
            session.close();
            assertTrue(session.isClosed());
        }

        @Test
        @DisplayName("close() is idempotent (safe to call twice)")
        void closeIsIdempotent() {
            session.close();
            session.close();
            assertTrue(session.isClosed());
        }
    }
}
