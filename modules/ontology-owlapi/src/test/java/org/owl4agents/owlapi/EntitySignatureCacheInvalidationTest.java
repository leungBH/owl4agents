package org.owl4agents.owlapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D4 / task 5.12: Verifies the global {@link EntitySignatureCache}
 * {@link EntitySignatureCache#invalidateAll()} method clears all entries
 * from the static Caffeine cache.
 *
 * <p>This is the v0.8.6 task 5.3 hook called from
 * {@code ReasonerServiceImpl.onOntologyReloaded} and
 * {@code onAllOntologiesReloaded} to prevent stale signature entries from
 * producing false-positive {@code contains} results after an ontology
 * reload.</p>
 */
@DisplayName("v0.8.6 D4 / task 5.12: EntitySignatureCache invalidateAll")
class EntitySignatureCacheInvalidationTest {

    @BeforeEach
    void resetGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @AfterEach
    void cleanupGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @Test
    @DisplayName("invalidateAll() drops all entries (size == 0)")
    void invalidateAllClearsCache() {
        // Insert 100 entries across all 4 kinds.
        for (int i = 0; i < 25; i++) {
            EntitySignatureCache.put("class", "http://example.org/test#C" + i);
            EntitySignatureCache.put("objprop", "http://example.org/test#P" + i);
            EntitySignatureCache.put("dataprop", "http://example.org/test#D" + i);
            EntitySignatureCache.put("individual", "http://example.org/test#I" + i);
        }
        EntitySignatureCache.cleanUp();
        long sizeBefore = EntitySignatureCache.estimatedSize();
        assertTrue(sizeBefore > 0,
            "Cache must have entries before invalidateAll, but size was " + sizeBefore);

        EntitySignatureCache.invalidateAll();
        // Caffeine.invalidateAll is synchronous — no cleanUp() needed.
        long sizeAfter = EntitySignatureCache.estimatedSize();
        assertEquals(0L, sizeAfter,
            "Cache size must be 0 after invalidateAll, but was " + sizeAfter);
    }

    @Test
    @DisplayName("get() returns null for every previously-present entry after invalidateAll")
    void getReturnsNullAfterInvalidation() {
        EntitySignatureCache.put("class", "http://example.org/test#C1");
        EntitySignatureCache.put("objprop", "http://example.org/test#P1");
        EntitySignatureCache.put("dataprop", "http://example.org/test#D1");
        EntitySignatureCache.put("individual", "http://example.org/test#I1");

        // Sanity check: entries present before invalidation.
        assertEquals(Boolean.TRUE, EntitySignatureCache.get("class", "http://example.org/test#C1"));
        assertEquals(Boolean.TRUE, EntitySignatureCache.get("objprop", "http://example.org/test#P1"));

        EntitySignatureCache.invalidateAll();

        assertNull(EntitySignatureCache.get("class", "http://example.org/test#C1"),
            "class entry must be absent after invalidateAll");
        assertNull(EntitySignatureCache.get("objprop", "http://example.org/test#P1"),
            "objprop entry must be absent after invalidateAll");
        assertNull(EntitySignatureCache.get("dataprop", "http://example.org/test#D1"),
            "dataprop entry must be absent after invalidateAll");
        assertNull(EntitySignatureCache.get("individual", "http://example.org/test#I1"),
            "individual entry must be absent after invalidateAll");
    }

    @Test
    @DisplayName("invalidateAll() on empty cache is a no-op (no exception)")
    void invalidateAllOnEmptyCacheIsNoOp() {
        EntitySignatureCache.invalidateAll();
        EntitySignatureCache.invalidateAll();
        assertEquals(0L, EntitySignatureCache.estimatedSize(),
            "Empty cache must remain size 0 after repeated invalidateAll");
    }
}
