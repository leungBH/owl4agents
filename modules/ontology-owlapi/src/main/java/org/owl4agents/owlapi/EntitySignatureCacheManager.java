package org.owl4agents.owlapi;

import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared manager for {@link EntitySignatureCache} instances, keyed by
 * {@code ontologyId.id()}. Provides thread-safe lazy initialization using
 * {@link ConcurrentHashMap#computeIfAbsent} to ensure each ontology's cache
 * is built at most once. Implements {@link OntologyReloadListener} to
 * invalidate caches when ontologies are reloaded.
 *
 * <p>Callers should check the return value of {@link #getOrCreate}: if null,
 * the cache build failed and the caller should fall back to the legacy
 * full-scan logic.</p>
 */
public final class EntitySignatureCacheManager implements OntologyReloadListener {

    private static final Logger LOG = Logger.getLogger(EntitySignatureCacheManager.class.getName());

    private final ConcurrentHashMap<String, EntitySignatureCache> cache = new ConcurrentHashMap<>();

    /**
     * Get or build the {@link EntitySignatureCache} for the given ontology.
     *
     * <p>The build is synchronous to avoid ForkJoinPool starvation on large
     * ontologies (e.g., Mondo 226MB). If the build throws an exception, it
     * is logged and {@code null} is returned so the caller can fall back to
     * the stream-scan path.</p>
     *
     * @param ontologyId the ontology ID (used as cache key)
     * @param ontology   the loaded {@link OWLOntology} (must come from {@link OntologyCache#getOrCreate})
     * @return the cache, or {@code null} if the build failed (caller should fall back)
     */
    public EntitySignatureCache getOrCreate(OntologyId ontologyId, OWLOntology ontology) {
        String key = ontologyId.id();
        EntitySignatureCache existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            return cache.computeIfAbsent(key, k -> EntitySignatureCache.build(ontology));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EntitySignatureCache build failed for ontology " + key
                + ", falling back to stream scan", e);
            return null;
        }
    }

    @Override
    public void onOntologyReloaded(OntologyId ontologyId) {
        cache.remove(ontologyId.id());
    }

    @Override
    public void onAllOntologiesReloaded() {
        cache.clear();
    }
}
