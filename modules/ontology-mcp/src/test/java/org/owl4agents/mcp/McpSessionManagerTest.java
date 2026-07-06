package org.owl4agents.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8 unit tests for {@link McpSessionManager}. Covers the spec.md
 * scenarios for session lifecycle, expiry sweeping, UUID v4 collision
 * handling, active stream count tracking, and idempotent shutdown.
 *
 * <p>These tests use a long {@code sweeperIntervalSeconds} (e.g. 60s)
 * so the background sweeper does not interfere with the assertions.
 * The tests call {@link McpSessionManager#sweepExpired} explicitly to
 * exercise the sweep path deterministically.
 */
@DisplayName("MCP session manager tests")
class McpSessionManagerTest {

    private McpSessionManager manager;

    @BeforeEach
    void setUp() {
        // 60s sweeper interval — we drive the sweeper manually in tests
        // to keep them deterministic.
        manager = new McpSessionManager(60L);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    // ── 1. create() ──

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("create() generates a UUID v4 session id and registers the session")
        void createGeneratesUuidV4() {
            McpSession session = manager.create();
            assertNotNull(session);
            // Parses as UUID v4 (version nibble = 4)
            UUID parsed = UUID.fromString(session.sessionId());
            assertEquals(4, parsed.version(),
                "create() must generate UUID v4 (version nibble 4)");
            // The session is registered
            assertSame(session, manager.get(session.sessionId()));
        }

        @Test
        @DisplayName("create() returns distinct ids across N invocations")
        void createReturnsDistinctIds() {
            int n = 1000;
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < n; i++) {
                ids.add(manager.create().sessionId());
            }
            assertEquals(n, ids.size(),
                "create() must generate distinct ids for " + n + " invocations");
        }
    }

    // ── 2. get() ──

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("get() returns null for an unknown id")
        void getReturnsNullForUnknown() {
            assertNull(manager.get(UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("get() returns null for null input")
        void getReturnsNullForNull() {
            assertNull(manager.get(null));
        }

        @Test
        @DisplayName("get() returns the registered session for a known id")
        void getReturnsRegisteredSession() {
            McpSession created = manager.create();
            McpSession looked = manager.get(created.sessionId());
            assertSame(created, looked);
        }
    }

    // ── 3. getOrCreate() ──

    @Nested
    @DisplayName("getOrCreate()")
    class GetOrCreateTests {

        @Test
        @DisplayName("getOrCreate() with a new id creates and registers a session")
        void getOrCreateWithNewIdCreates() {
            String id = UUID.randomUUID().toString();
            McpSession s = manager.getOrCreate(id);
            assertNotNull(s);
            assertEquals(id, s.sessionId());
            assertSame(s, manager.get(id));
        }

        @Test
        @DisplayName("getOrCreate() with a known id returns the existing session and updates lastAccessAt")
        void getOrCreateWithKnownIdReturnsExisting() throws Exception {
            McpSession s = manager.create();
            String id = s.sessionId();
            // Force lastAccessAt into the past
            java.lang.reflect.Field f = McpSession.class.getDeclaredField("lastAccessAt");
            f.setAccessible(true);
            f.set(s, java.time.Instant.now().minusSeconds(120));
            java.time.Instant before = s.lastAccessAt();
            Thread.sleep(5);

            McpSession s2 = manager.getOrCreate(id);

            assertSame(s, s2, "getOrCreate() must return the existing session, not a new one");
            assertTrue(s2.lastAccessAt().isAfter(before),
                "getOrCreate() must touch() the existing session");
        }

        @Test
        @DisplayName("getOrCreate() with a closed session id creates a fresh session bound to the same id")
        void getOrCreateWithClosedIdCreatesFresh() {
            McpSession s = manager.create();
            String id = s.sessionId();
            s.close();
            McpSession s2 = manager.getOrCreate(id);
            assertNotNull(s2);
            assertEquals(id, s2.sessionId());
            assertFalse(s2.isClosed());
        }

        @Test
        @DisplayName("getOrCreate() with null or empty returns null")
        void getOrCreateWithNullOrEmptyReturnsNull() {
            assertNull(manager.getOrCreate(null));
            assertNull(manager.getOrCreate(""));
        }

        @Test
        @DisplayName("getOrCreate() with a non-UUID-v4 id returns null (does not register a session)")
        void getOrCreateWithInvalidUuidReturnsNull() {
            assertNull(manager.getOrCreate("not-a-uuid"));
            // 0 sessions registered
            assertEquals(0, manager.sessionCount());
        }
    }

    // ── 4. remove() ──

    @Nested
    @DisplayName("remove()")
    class RemoveTests {

        @Test
        @DisplayName("remove() deletes the session from the registry")
        void removeDeletesSession() {
            McpSession s = manager.create();
            String id = s.sessionId();
            manager.remove(id);
            assertNull(manager.get(id));
        }

        @Test
        @DisplayName("remove() is idempotent on unknown ids")
        void removeIsIdempotent() {
            manager.remove(UUID.randomUUID().toString());
            // no exception
        }

        @Test
        @DisplayName("remove(null) is a no-op")
        void removeNullIsNoOp() {
            manager.remove(null);
            // no exception
        }

        @Test
        @DisplayName("remove() closes the session")
        void removeClosesSession() {
            McpSession s = manager.create();
            String id = s.sessionId();
            manager.remove(id);
            assertTrue(s.isClosed(), "remove() must close the session");
        }
    }

    // ── 5. sweepExpired() ──

    @Nested
    @DisplayName("sweepExpired()")
    class SweepExpiredTests {

        @Test
        @DisplayName("sweepExpired() removes sessions whose lastAccessAt is older than the TTL")
        void sweepExpiredRemovesOldSessions() throws Exception {
            McpSession old = manager.create();
            McpSession fresh = manager.create();
            // Force old.lastAccessAt into the past
            java.lang.reflect.Field f = McpSession.class.getDeclaredField("lastAccessAt");
            f.setAccessible(true);
            f.set(old, java.time.Instant.now().minusSeconds(120));

            int removed = manager.sweepExpired(Duration.ofSeconds(60));

            assertEquals(1, removed);
            assertNull(manager.get(old.sessionId()));
            assertNotNull(manager.get(fresh.sessionId()),
                "Fresh session must NOT be swept");
        }

        @Test
        @DisplayName("sweepExpired() is a no-op when no sessions are expired")
        void sweepExpiredNoOpWhenFresh() {
            manager.create();
            manager.create();
            int removed = manager.sweepExpired(Duration.ofMinutes(30));
            assertEquals(0, removed);
            assertEquals(2, manager.sessionCount());
        }

        @Test
        @DisplayName("sweepExpired(null) is a no-op")
        void sweepExpiredNullIsNoOp() {
            manager.create();
            int removed = manager.sweepExpired(null);
            assertEquals(0, removed);
        }

        @Test
        @DisplayName("sweepExpired() closes each removed session's open SSE streams")
        void sweepExpiredClosesOpenStreams() throws Exception {
            // Build a session with a no-op stream (we cannot construct a real
            // McpSseStream without an HttpExchange, so we verify the close
            // path indirectly: the session itself is closed after sweep).
            McpSession s = manager.create();
            java.lang.reflect.Field f = McpSession.class.getDeclaredField("lastAccessAt");
            f.setAccessible(true);
            f.set(s, java.time.Instant.now().minusSeconds(120));
            manager.sweepExpired(Duration.ofSeconds(60));
            assertTrue(s.isClosed(), "Swept session must be closed");
        }
    }

    // ── 6. activeStreamCount ──

    @Nested
    @DisplayName("activeStreamCount")
    class ActiveStreamCountTests {

        @Test
        @DisplayName("activeStreamCount starts at 0")
        void activeStreamCountStartsAtZero() {
            assertEquals(0, manager.activeStreamCount());
        }

        @Test
        @DisplayName("incrementStreamCount and decrementStreamCount adjust the counter")
        void incrementAndDecrementAdjustCounter() {
            manager.incrementStreamCount();
            manager.incrementStreamCount();
            manager.incrementStreamCount();
            assertEquals(3, manager.activeStreamCount());
            manager.decrementStreamCount();
            assertEquals(2, manager.activeStreamCount());
        }

        @Test
        @DisplayName("decrementStreamCount floors at 0 (idempotent double-decrement)")
        void decrementStreamCountFloorsAtZero() {
            manager.decrementStreamCount();
            manager.decrementStreamCount();
            manager.decrementStreamCount();
            assertEquals(0, manager.activeStreamCount(),
                "decrementStreamCount must not underflow below 0");
        }
    }

    // ── 7. shutdown() ──

    @Nested
    @DisplayName("shutdown()")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown() clears the session registry and the stream counter")
        void shutdownClearsState() {
            manager.create();
            manager.create();
            manager.incrementStreamCount();
            manager.incrementStreamCount();

            manager.shutdown();

            assertEquals(0, manager.sessionCount());
            assertEquals(0, manager.activeStreamCount());
        }

        @Test
        @DisplayName("shutdown() is idempotent (safe to call twice)")
        void shutdownIsIdempotent() {
            manager.create();
            manager.shutdown();
            manager.shutdown();  // must not throw
        }

        @Test
        @DisplayName("shutdown() closes every registered session")
        void shutdownClosesEverySession() {
            McpSession a = manager.create();
            McpSession b = manager.create();
            manager.shutdown();
            assertTrue(a.isClosed());
            assertTrue(b.isClosed());
        }
    }

    // ── 8. Concurrency ──

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("100 concurrent create() calls produce 100 distinct sessions")
        void concurrentCreateProducesDistinctSessions() throws Exception {
            int n = 100;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentHashMap<String, McpSession> seen = new ConcurrentHashMap<>();
            List<java.util.concurrent.Future<McpSession>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    McpSession s = manager.create();
                    seen.put(s.sessionId(), s);
                    return s;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<McpSession> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            assertEquals(n, seen.size());
            assertEquals(n, manager.sessionCount());
        }
    }
}
