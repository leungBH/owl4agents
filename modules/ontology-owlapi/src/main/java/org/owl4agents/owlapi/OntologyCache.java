package org.owl4agents.owlapi;

import org.owl4agents.core.OntologyId;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Workspace-level in-memory cache for loaded {@link OWLOntology} instances.
 *
 * <p>Multiple services ({@code ReasonerServiceImpl},
 * {@code ConsistencyAnalysisService}, {@code SemanticDeepeningService})
 * share a single {@code OntologyCache} instance to avoid re-loading the
 * same ontology file from disk on every verification call. For large
 * ontologies (Mondo: 236 MB, 36-211s load time), this reduces subsequent
 * verification calls from tens of seconds to 1-5 seconds.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Uses {@link ConcurrentHashMap} with {@link CompletableFuture} values
 * to ensure that at most one thread loads a given ontology. The
 * {@code computeIfAbsent} call only briefly holds the bin-level lock
 * while constructing the future; the actual disk I/O happens asynchronously
 * inside the future, so concurrent calls for <em>different</em> ontologyIds
 * do not block each other.</p>
 *
 * <h2>Cache invalidation</h2>
 * <p>On every {@link #getOrCreate(OntologyId)} call, the file's mtime and
 * size are checked. If either changed, the stale entry is removed and the
 * ontology is reloaded. Before the new entry becomes visible,
 * {@link OntologyReloadListener#onOntologyReloaded(OntologyId)} is called
 * to shut down any reasoner adapter bound to the old ontology (avoiding
 * a TOCTOU window).</p>
 *
 * <h2>Path resolution</h2>
 * <p>Ontology files are resolved as:
 * {@code <workspaceBasePath>/<workspaceName>/ontologies/<id>/canonical/ontology.owl}
 * This unifies path resolution across all three services and fixes the
 * hardcoded {@code "default"} workspace bug in
 * {@code ConsistencyAnalysisService} and {@code SemanticDeepeningService}.</p>
 */
public final class OntologyCache {

    private final ConcurrentHashMap<String, CompletableFuture<CacheEntry>> cache = new ConcurrentHashMap<>();
    private final String workspaceBasePath;
    private final String workspaceName;
    private final CopyOnWriteArrayList<OntologyReloadListener> reloadListeners = new CopyOnWriteArrayList<>();

    // v0.8.4 Decision 7: TTL window — skip file stat syscalls if validated within ttlMillis.
    private final ConcurrentHashMap<String, Long> lastValidatedAtMap = new ConcurrentHashMap<>();
    private final long ttlMillis;

    private record CacheEntry(OWLOntology ontology, long fileMtime, long fileSize) {}

    /**
     * @param workspaceBasePath absolute path to the workspace root directory
     * @param workspaceName     workspace name (e.g. {@code "default"})
     */
    public OntologyCache(String workspaceBasePath, String workspaceName) {
        this(workspaceBasePath, workspaceName, 5000);
    }

    /**
     * @param workspaceBasePath absolute path to the workspace root directory
     * @param workspaceName     workspace name (e.g. {@code "default"})
     * @param ttlMillis         TTL window in milliseconds for skipping file stat
     *                          syscalls. Use {@code 0} to disable the TTL window
     *                          (always stat the file).
     */
    public OntologyCache(String workspaceBasePath, String workspaceName, long ttlMillis) {
        this.workspaceBasePath = workspaceBasePath;
        this.workspaceName = workspaceName;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Register a listener that receives callbacks when an ontology is reloaded.
     * Multiple listeners can be registered; each will be called on reload
     * events. Uses {@link CopyOnWriteArrayList} for thread-safe
     * post-construction registration.
     */
    public void addReloadListener(OntologyReloadListener listener) {
        this.reloadListeners.add(listener);
    }

    /**
     * @return the workspace base path (used by {@code CliServiceFactory}
     *         for service initialization).
     */
    public String getWorkspaceBasePath() {
        return workspaceBasePath;
    }

    /**
     * Get the cached {@link OWLOntology} for {@code ontologyId}, or load
     * it from disk if not cached or if the file has changed.
     *
     * <p>Cache validity is checked via file mtime + size. If either
     * changed since the last load, the stale entry is removed and the
     * ontology is reloaded. Before the new entry becomes visible,
     * {@link OntologyReloadListener#onOntologyReloaded(OntologyId)} is
     * called (only when the previous load succeeded — failed loads have
     * no adapter to invalidate) to shut down any reasoner adapter bound
     * to the old ontology.</p>
     *
     * @param ontologyId the ontology to load/cache
     * @return the cached or freshly-loaded {@link OWLOntology}
     * @throws OWLOntologyCreationException if the file is missing or
     *         loading fails (the failed future is removed so the next
     *         call retries)
     */
    public OWLOntology getOrCreate(OntologyId ontologyId) throws OWLOntologyCreationException {
        String key = ontologyId.id();

        // v0.8.4 Decision 7: TTL window — if validated within ttlMillis, skip all 3
        // file stat syscalls (Files.exists + getLastModifiedTime + size) and
        // return the cached ontology directly.
        Long lastValidated = lastValidatedAtMap.get(key);
        if (ttlMillis > 0 && lastValidated != null && System.currentTimeMillis() - lastValidated < ttlMillis) {
            CompletableFuture<CacheEntry> cached = cache.get(key);
            if (cached != null) {
                try {
                    return cached.join().ontology();
                } catch (CompletionException e) {
                    // Fall through to full load if cached entry is broken
                }
            }
        }

        Path ontologyPath = resolveOntologyPath(ontologyId);
        if (!Files.exists(ontologyPath)) {
            throw new OWLOntologyCreationException("Ontology file not found: " + ontologyPath);
        }
        long currentMtime;
        long currentSize;
        try {
            currentMtime = Files.getLastModifiedTime(ontologyPath).toMillis();
            currentSize = Files.size(ontologyPath);
        } catch (IOException e) {
            throw new OWLOntologyCreationException("Failed to stat ontology file: " + ontologyPath, e);
        }

        CompletableFuture<CacheEntry> existing = cache.get(ontologyId.id());
        boolean previousLoadSucceeded = false;
        if (existing != null) {
            try {
                CacheEntry entry = existing.join();
                previousLoadSucceeded = true;
                if (entry.fileMtime() == currentMtime && entry.fileSize() == currentSize) {
                    // v0.8.4: update TTL timestamp on cache hit
                    lastValidatedAtMap.put(key, System.currentTimeMillis());
                    return entry.ontology();
                }
                cache.remove(ontologyId.id(), existing);
            } catch (CompletionException e) {
                cache.remove(ontologyId.id(), existing);
            }
        }

        if (previousLoadSucceeded) {
            for (OntologyReloadListener listener : reloadListeners) {
                listener.onOntologyReloaded(ontologyId);
            }
        }

        CompletableFuture<CacheEntry> newFuture = cache.computeIfAbsent(ontologyId.id(), k -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                    try (InputStream is = Files.newInputStream(ontologyPath)) {
                        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(is);
                        return new CacheEntry(ontology, currentMtime, currentSize);
                    }
                } catch (OWLOntologyCreationException ex) {
                    throw new CompletionException(ex);
                } catch (IOException ex) {
                    throw new CompletionException(new OWLOntologyCreationException(
                        "Failed to read ontology file: " + ontologyPath, ex));
                }
            });
        });

        try {
            CacheEntry entry = newFuture.join();
            // v0.8.4: update TTL timestamp after successful full load
            lastValidatedAtMap.put(key, System.currentTimeMillis());
            return entry.ontology();
        } catch (CompletionException e) {
            cache.remove(ontologyId.id(), newFuture);
            Throwable cause = e.getCause();
            if (cause instanceof OWLOntologyCreationException owlex) {
                throw owlex;
            }
            throw new OWLOntologyCreationException("Failed to load ontology " + ontologyId.id(), cause);
        }
    }

    /**
     * Remove a single ontology from the cache and notify the listener.
     */
    public void invalidate(OntologyId ontologyId) {
        CompletableFuture<CacheEntry> removed = cache.remove(ontologyId.id());
        lastValidatedAtMap.remove(ontologyId.id());
        if (removed != null) {
            for (OntologyReloadListener listener : reloadListeners) {
                listener.onOntologyReloaded(ontologyId);
            }
        }
    }

    /**
     * Remove all ontologies from the cache and notify the listener.
     */
    public void invalidateAll() {
        cache.clear();
        lastValidatedAtMap.clear();
        for (OntologyReloadListener listener : reloadListeners) {
            listener.onAllOntologiesReloaded();
        }
    }

    private Path resolveOntologyPath(OntologyId ontologyId) {
        return Path.of(workspaceBasePath, workspaceName,
                       "ontologies", ontologyId.id(), "canonical", "ontology.owl");
    }
}
