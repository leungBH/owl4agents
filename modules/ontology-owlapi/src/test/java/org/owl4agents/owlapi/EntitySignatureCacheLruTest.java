package org.owl4agents.owlapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.6 D4 / task 5.11: Verifies the global {@link EntitySignatureCache}
 * Caffeine cache enforces its 50K-entry LRU cap. Inserting 60K entries must
 * result in an estimated size at or below 50_000 once Caffeine's eviction
 * maintenance has run.
 *
 * <p>The cache is a static single instance shared across all
 * {@link EntitySignatureCache} instances (see Javadoc on the class), so we
 * {@code invalidateAll()} before each test to guarantee isolation.</p>
 */
@DisplayName("v0.8.6 D4 / task 5.11: EntitySignatureCache LRU eviction")
class EntitySignatureCacheLruTest {

    @BeforeEach
    void resetGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @AfterEach
    void cleanupGlobalCache() {
        EntitySignatureCache.invalidateAll();
    }

    @Test
    @DisplayName("Inserting 60K entries keeps estimatedSize <= 50_000 (W-TinyLFU cap)")
    void lruCapsAt50kEntries() {
        // Insert 60K distinct class IRIs — well above the 50K cap.
        for (int i = 0; i < 60_000; i++) {
            EntitySignatureCache.put("class", "http://example.org/test#C" + i);
        }

        // Force Caffeine to run eviction maintenance synchronously. Without
        // this, estimatedSize() may temporarily report a value above the cap
        // because Caffeine performs eviction lazily / on the next maintenance
        // window.
        EntitySignatureCache.cleanUp();

        long size = EntitySignatureCache.estimatedSize();
        assertTrue(size <= 50_000,
            "Cache size must be <= 50_000 after inserting 60K entries, but was " + size);
    }

    @Test
    @DisplayName("Inserting below cap keeps all entries (no premature eviction)")
    void belowCapKeepsAllEntries() {
        for (int i = 0; i < 1_000; i++) {
            EntitySignatureCache.put("class", "http://example.org/test#C" + i);
        }
        EntitySignatureCache.cleanUp();

        long size = EntitySignatureCache.estimatedSize();
        assertTrue(size >= 1_000 && size <= 50_000,
            "Cache size must be >= 1000 and <= 50000 for 1K insertions, but was " + size);
    }
}
