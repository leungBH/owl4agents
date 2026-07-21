package org.owl4agents.owlapi;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D4 / task 5.12a: Verifies the global {@link EntitySignatureCache}
 * Caffeine {@code recordStats()} instrumentation reports accurate hit rate
 * and eviction count. {@code recordStats()} is configured on the static
 * cache so the upcoming monitoring endpoint (v1.0.1) and unit tests can
 * assert cache effectiveness.
 *
 * <p><b>Important:</b> Caffeine stats are cumulative over the lifetime of
 * the cache instance — they are NOT reset by {@code invalidateAll()}.
 * Because the cache is {@code static final} and shared across all tests in
 * the JVM, this test uses <b>delta assertions</b>: snapshot the stats
 * before the test operations, then assert the <i>difference</i> matches
 * the expected delta. This isolates the test from other tests' stats
 * accumulation.</p>
 *
 * <p>Scenario: 1000 puts + 1000 gets where 500 get calls hit previously
 * inserted entries and 500 get calls miss. Expected delta:
 * {@code hitCount == 500}, {@code missCount == 500},
 * {@code hitRate == 0.5}. A second scenario inserts 60K entries (above
 * the 50K cap) and asserts {@code evictionCount > 0}.</p>
 */
@DisplayName("v0.8.6 D4 / task 5.12a: EntitySignatureCache stats instrumentation")
class EntitySignatureCacheStatsTest {

    @BeforeEach
    void resetGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @AfterEach
    void cleanupGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @Test
    @DisplayName("1000 puts + 500 hits + 500 misses -> hitRate == 0.5 (delta)")
    void hitRateIsHalfForFiftyFiftyHitsMisses() {
        // Snapshot stats BEFORE the test operations. Caffeine stats are
        // cumulative across the JVM lifetime and are NOT reset by
        // invalidateAll(), so we must use deltas to isolate this test
        // from other tests' stats.
        EntitySignatureCache.cleanUp();
        CacheStats before = EntitySignatureCache.stats();

        // 1000 puts
        for (int i = 0; i < 1_000; i++) {
            EntitySignatureCache.put("class", "http://example.org/test#C" + i);
        }
        EntitySignatureCache.cleanUp();

        // 500 hits — read back the first 500 inserted IRIs
        for (int i = 0; i < 500; i++) {
            EntitySignatureCache.get("class", "http://example.org/test#C" + i);
        }

        // 500 misses — read back IRIs that were never inserted (offset by 100_000)
        for (int i = 0; i < 500; i++) {
            EntitySignatureCache.get("class", "http://example.org/test#C" + (i + 100_000));
        }

        EntitySignatureCache.cleanUp();
        CacheStats after = EntitySignatureCache.stats();

        long hitsDelta = after.hitCount() - before.hitCount();
        long missesDelta = after.missCount() - before.missCount();
        long requestsDelta = after.requestCount() - before.requestCount();

        assertEquals(500L, hitsDelta, "hitCount delta must be 500, but was " + hitsDelta);
        assertEquals(500L, missesDelta, "missCount delta must be 500, but was " + missesDelta);
        assertEquals(1_000L, requestsDelta, "requestCount delta must be 1000, but was " + requestsDelta);

        // hitRate over the delta window = hits / (hits + misses) = 500/1000 = 0.5
        double hitRateDelta = (double) hitsDelta / (hitsDelta + missesDelta);
        assertEquals(0.5, hitRateDelta, 1e-6,
            "hitRate (delta) must be 0.5, but was " + hitRateDelta);
    }

    @Test
    @DisplayName("Inserting 60K entries into 50K cap -> evictionCount delta > 0")
    void evictionCountNonZeroAfterOverfill() {
        EntitySignatureCache.cleanUp();
        CacheStats before = EntitySignatureCache.stats();

        for (int i = 0; i < 60_000; i++) {
            EntitySignatureCache.put("class", "http://example.org/test#E" + i);
        }
        EntitySignatureCache.cleanUp();

        CacheStats after = EntitySignatureCache.stats();
        long evictionsDelta = after.evictionCount() - before.evictionCount();
        assertTrue(evictionsDelta > 0,
            "evictionCount delta must be > 0 after inserting 60K into a 50K-capped cache, but was " + evictionsDelta);
    }
}
