package org.owl4agents.reasoner;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.OntologyReloadListener;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages reasoner lifecycle for ontology sessions.
 * - Reasoner initialized on first reasoning call
 * - Reused for subsequent calls on same ontology
 * - Shut down on ontology re-import or session end
 * - New instance when reasoner type changes within same session
 *
 * <p>Implements {@link OntologyReloadListener} to receive callbacks from
 * {@link org.owl4agents.owlapi.OntologyCache} when an ontology file changes.
 * On reload, the reasoner adapter bound to the old {@link OWLOntology} is
 * shut down (releasing HermiT/ELK/Openllet native resources) so the next
 * reasoning call builds a fresh adapter bound to the new ontology.</p>
 *
 * <p><b>v0.8.6 D3 (Cache Governance):</b> {@code activeReasoners} migrated
 * from unbounded {@link ConcurrentHashMap} to a bounded LRU
 * {@link LinkedHashMap} (access-order) wrapped with
 * {@link Collections#synchronizedMap}. The map is bounded to
 * {@link #MAX_ACTIVE_REASONERS} (=4) entries via a custom
 * {@link #evictIfFull()} method (NOT {@code removeEldestEntry} — that cannot
 * skip in-use entries). Reference counting via {@link #inUseCount} prevents
 * evicting a reasoner that another thread is actively using.</p>
 *
 * <p><b>Compound key (v0.8.6):</b> The map key changed from
 * {@code ontologyId.id()} to {@code ontologyId.id() + "|" + reasonerName}
 * so multiple reasoners per ontology are allowed (e.g., HermiT for
 * classification + ELK for fast entailment + Openllet for explanation).
 * Callers that look up by ontology ID only (e.g.,
 * {@link #getActiveReasoner(OntologyId)}, {@link #shutdownReasoner(OntologyId)})
 * iterate the map and operate on ALL reasoners for that ontology.</p>
 */
public class ReasonerLifecycleManager implements OntologyReloadListener {

    private static final Logger LOG = Logger.getLogger(ReasonerLifecycleManager.class.getName());

    /**
     * v0.8.6 D3: Maximum number of concurrently active reasoner adapters.
     * HPO + Mondo + Pizza + 1 spare covers the typical benchmark scenario.
     * The 5th reasoner triggers eviction of the LRU in-use-safe one.
     */
    static final int MAX_ACTIVE_REASONERS = 4;

    /**
     * v0.8.6 D3: Access-order LRU map. {@link LinkedHashMap} with
     * {@code accessOrder=true} so {@code get} operations move the entry to
     * the tail (most-recently-used). The head is the least-recently-used
     * entry — the first candidate for eviction.
     *
     * <p>Wrapped with {@link Collections#synchronizedMap} for thread safety.
     * Compound operations (get-then-put, iteration) MUST be inside a
     * {@code synchronized(activeReasoners)} block.</p>
     *
     * <p>Package-private so unit tests in {@code org.owl4agents.reasoner}
     * can inspect size and contents directly.</p>
     */
    final Map<String, OWLReasonerAdapter> activeReasoners =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));

    /**
     * v0.8.6 D3: Reference count per reasoner key. Incremented in
     * {@link #getOrCreateReasoner} (inside {@code synchronized(activeReasoners)}),
     * decremented in {@link #releaseReasoner(String)}. The custom
     * {@link #evictIfFull()} method only disposes entries with
     * {@code inUseCount == 0} to prevent evicting a reasoner that another
     * thread is actively using.
     *
     * <p>Uses {@link ConcurrentHashMap} + {@link AtomicLong} so
     * increment/decrement are lock-free; only the initial {@code compute}
     * for creation needs synchronization (handled inside the
     * {@code synchronized(activeReasoners)} block in {@link #getOrCreateReasoner}).</p>
     *
     * <p>Package-private so unit tests can simulate active calls by
     * incrementing the count directly.</p>
     */
    final Map<String, AtomicLong> inUseCount = new ConcurrentHashMap<>();

    private final Map<String, ReasoningReport> reasoningReports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> classifiedMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> profileCacheMap = new ConcurrentHashMap<>();

    private final Map<String, OWLReasonerAdapter> adapterRegistry;

    public ReasonerLifecycleManager() {
        this.adapterRegistry = new HashMap<>();
        // Register available adapters — all compatible with OWL API 5.x
        adapterRegistry.put("HermiT", new HermiTAdapter());
        adapterRegistry.put("ELK", new ELKAdapter());
        adapterRegistry.put("Openllet", new OpenlletAdapter());
    }

    /**
     * Get or create the reasoner adapter for the given ontology and reasoner name.
     * If a reasoner of the same type is already active (matched by compound key
     * {@code ontologyId.id() + "|" + reasonerName}), reuse it. Otherwise create
     * a new adapter, register it in the LRU map, and trigger eviction if the
     * map exceeds {@link #MAX_ACTIVE_REASONERS}.
     *
     * <p>v0.8.6 D3: Reference count ({@link #inUseCount}) is incremented
     * atomically inside the {@code synchronized(activeReasoners)} block to
     * prevent the ABA race where {@link #evictIfFull()} disposes a reasoner
     * between its insertion and its count increment.</p>
     *
     * @param ontologyId           the ontology ID (part 1 of the compound key)
     * @param reasonerName         the reasoner name ("HermiT", "ELK", "Openllet", or "auto")
     * @param ontology             the loaded OWL ontology
     * @param detectedProfile      the detected OWL profile
     * @param explanationRequested whether explanation is requested (affects auto-selection)
     * @return the active or newly-created adapter
     */
    public OWLReasonerAdapter getOrCreateReasoner(OntologyId ontologyId, String reasonerName,
                                                    OWLOntology ontology, String detectedProfile,
                                                    boolean explanationRequested) {
        // Auto-select if needed
        if ("auto".equalsIgnoreCase(reasonerName) || reasonerName == null) {
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult selection = selector.select(detectedProfile, explanationRequested);
            reasonerName = selection.reasonerName();
        }

        // Normalize to canonical registry name (case-insensitive match)
        reasonerName = canonicalReasonerName(reasonerName);
        final String canonicalReasonerName = reasonerName;
        final String key = buildKey(ontologyId, canonicalReasonerName);

        synchronized (activeReasoners) {
            // Check if we already have an active reasoner of the same type.
            // LinkedHashMap.get() in access-order mode moves the entry to the
            // tail (most-recently-used), which is the desired LRU touch.
            OWLReasonerAdapter existing = activeReasoners.get(key);
            if (existing != null && existing.isActive()) {
                // Reuse existing reasoner — increment reference count inside the sync block.
                incrementInUseCount(key);
                return existing;
            }

            // If existing but no longer active, shut it down and remove before creating a new one.
            if (existing != null) {
                try {
                    existing.shutdown();
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Failed to shut down inactive reasoner " + key, ex);
                }
                activeReasoners.remove(key);
                inUseCount.remove(key);
            }

            // Create new adapter instance
            OWLReasonerAdapter newAdapter = createAdapter(canonicalReasonerName);
            newAdapter.initialize(ontology);

            activeReasoners.put(key, newAdapter);
            // Increment reference count INSIDE the sync block — prevents
            // evictIfFull() from disposing this entry between put and increment.
            incrementInUseCount(key);

            // Trigger LRU eviction if we exceeded the cap. evictIfFull iterates
            // the access-ordered map to find the eldest in-use-safe entry.
            evictIfFull();

            return newAdapter;
        }
    }

    /**
     * v0.8.6 D3: Build the compound key for {@link #activeReasoners}.
     *
     * @param ontologyId   the ontology ID
     * @param reasonerName the canonical reasoner name (e.g., "HermiT", "ELK", "Openllet")
     * @return {@code ontologyId.id() + "|" + reasonerName}
     */
    private static String buildKey(OntologyId ontologyId, String reasonerName) {
        return ontologyId.id() + "|" + reasonerName;
    }

    /**
     * v0.8.6 D3: Increment {@link #inUseCount} for the given key. MUST be
     * called inside a {@code synchronized(activeReasoners)} block to prevent
     * the ABA race with {@link #evictIfFull()}.
     */
    private void incrementInUseCount(String key) {
        inUseCount.compute(key, (k, v) -> {
            if (v == null) return new AtomicLong(1);
            v.incrementAndGet();
            return v;
        });
    }

    /**
     * v0.8.6 D3: Custom LRU eviction. Called inside
     * {@code synchronized(activeReasoners)} after a {@code put}. Iterates the
     * access-ordered {@link #activeReasoners} (head = least-recently-used)
     * and disposes the first entry with {@code inUseCount == 0}.
     *
     * <p>Why not {@code LinkedHashMap.removeEldestEntry}: that method is
     * called inside {@code put} and can only return true/false. It cannot
     * skip the eldest if it's in use, so returning false would leave the
     * map unbounded. The custom {@code evictIfFull} iterates to find the
     * eldest in-use-safe entry.</p>
     *
     * <p>If ALL entries have {@code inUseCount > 0} (all reasoners are
     * actively in use), eviction is skipped and a {@code WARNING} is logged.
     * The map size temporarily exceeds {@link #MAX_ACTIVE_REASONERS} until
     * the next {@link #releaseReasoner(String)} call drops a count to 0,
     * at which point the next {@link #getOrCreateReasoner} triggers
     * {@code evictIfFull} again.</p>
     */
    private void evictIfFull() {
        // CALLER MUST hold synchronized(activeReasoners).
        if (activeReasoners.size() <= MAX_ACTIVE_REASONERS) return;

        String evictKey = null;
        synchronized (activeReasoners) {
            for (Map.Entry<String, OWLReasonerAdapter> e : activeReasoners.entrySet()) {
                AtomicLong count = inUseCount.get(e.getKey());
                if (count == null || count.get() == 0) {
                    evictKey = e.getKey();
                    break;
                }
            }
        }

        if (evictKey != null) {
            OWLReasonerAdapter evicted;
            synchronized (activeReasoners) {
                evicted = activeReasoners.remove(evictKey);
            }
            inUseCount.remove(evictKey);
            if (evicted != null) {
                try {
                    evicted.shutdown();
                } catch (Exception ex) {
                    // v0.8.6 task 5.16: dispose failures are logged and
                    // swallowed — the entry is already removed from the map,
                    // so the LRU state is consistent. The leaked native
                    // resources (HermiT/Openllet thread pools) will be
                    // cleaned up on JVM exit or by ExitOnOutOfMemoryError.
                    LOG.log(Level.SEVERE, "Failed to dispose evicted reasoner " + evictKey, ex);
                }
            }
        } else {
            // All reasoners in use — skip eviction this round, log warning.
            LOG.warning("All " + activeReasoners.size()
                + " active reasoners are in-use; cannot evict (size temporarily exceeds "
                + MAX_ACTIVE_REASONERS + ")");
        }
    }

    /**
     * v0.8.6 D3 / task 5.6: Release a reasoner reference acquired via
     * {@link #getOrCreateReasoner}. Decrements the {@link #inUseCount} for
     * the given key, allowing the LRU evictor to safely dispose it when
     * {@link #MAX_ACTIVE_REASONERS} is exceeded.
     *
     * <p>Reference count release rules (per D1 — caller responsibility):
     * <ul>
     *   <li>{@code metadata.fallbackFrom == null}: release only
     *       {@code metadata.reasonerName}.</li>
     *   <li>{@code metadata.fallbackFrom != null}: release BOTH primary
     *       AND fallback.</li>
     *   <li>{@code REASONER_TIMEOUT}: release BOTH primary AND "ELK".</li>
     *   <li>{@code REASONER_BUSY}: release nothing (no reasoner acquired).</li>
     * </ul>
     * The caller ({@link org.owl4agents.reasoner.ReasonerServiceImpl}) already
     * calls {@link #releaseReasoner(OntologyId, String)} with the correct
     * reasoner names based on metadata. This method just decrements the
     * count and triggers eviction if the cap is exceeded.</p>
     *
     * @param key the compound key ({@code ontologyId.id() + "|" + reasonerName})
     */
    void releaseReasoner(String key) {
        AtomicLong count = inUseCount.get(key);
        if (count == null) {
            // Release without acquire — no-op (defensive against double-release).
            return;
        }
        long newCount = count.decrementAndGet();
        if (newCount <= 0) {
            inUseCount.remove(key);
            // After a release, the entry may now be eligible for eviction
            // on the next getOrCreateReasoner call. We also proactively
            // trigger eviction here to keep the map bounded.
            synchronized (activeReasoners) {
                evictIfFull();
            }
        }
    }

    /**
     * v0.8.6 D3 / task 5.6: Release a reasoner reference by ontology ID and
     * reasoner name. Builds the compound key and delegates to
     * {@link #releaseReasoner(String)}.
     *
     * @param ontologyId   the ontology whose reasoner reference is being released
     * @param reasonerName the reasoner name being released (may differ from
     *                     the active reasoner if ELK fallback occurred)
     */
    public void releaseReasoner(OntologyId ontologyId, String reasonerName) {
        if (reasonerName == null || reasonerName.isBlank()) return;
        String canonical = canonicalReasonerName(reasonerName);
        releaseReasoner(buildKey(ontologyId, canonical));
    }

    /**
     * Shut down ALL reasoners for a given ontology (across all reasoner names).
     * Called when ontology is re-imported or session ends.
     *
     * <p>v0.8.6: Updated to iterate the map and shut down every entry whose
     * key starts with {@code ontologyId.id() + "|"}. This is necessary
     * because the compound key now allows multiple reasoners per ontology.</p>
     *
     * @param ontologyId the ontology whose reasoners should be shut down
     */
    public void shutdownReasoner(OntologyId ontologyId) {
        String prefix = ontologyId.id() + "|";
        List<String> keysToRemove = new ArrayList<>();
        synchronized (activeReasoners) {
            for (String key : activeReasoners.keySet()) {
                if (key.startsWith(prefix)) {
                    keysToRemove.add(key);
                }
            }
            for (String key : keysToRemove) {
                OWLReasonerAdapter adapter = activeReasoners.remove(key);
                inUseCount.remove(key);
                if (adapter != null) {
                    try {
                        adapter.shutdown();
                    } catch (Exception ex) {
                        LOG.log(Level.WARNING, "Failed to shut down reasoner " + key, ex);
                    }
                }
            }
        }
        classifiedMap.remove(ontologyId.id());
        profileCacheMap.remove(ontologyId.id());
    }

    /**
     * Get ANY active reasoner for a given ontology (if any). With the v0.8.6
     * compound key, multiple reasoners may be active for one ontology; this
     * method returns the first one found (iteration order = LRU access order).
     *
     * @param ontologyId the ontology ID to look up
     * @return any active reasoner for the ontology, or empty if none
     */
    public Optional<OWLReasonerAdapter> getActiveReasoner(OntologyId ontologyId) {
        String prefix = ontologyId.id() + "|";
        synchronized (activeReasoners) {
            for (Map.Entry<String, OWLReasonerAdapter> entry : activeReasoners.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    OWLReasonerAdapter adapter = entry.getValue();
                    if (adapter != null && adapter.isActive()) {
                        // Access-order touch: re-insert via get to update LRU position.
                        activeReasoners.get(entry.getKey());
                        return Optional.of(adapter);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Check whether ANY reasoning has been run for a given ontology (any
     * reasoner name). Returns true if at least one reasoner entry exists
     * for the ontology OR a reasoning report is stored.
     *
     * @param ontologyId the ontology ID to check
     * @return true if any reasoner has been created for this ontology
     */
    public boolean hasReasoningRun(OntologyId ontologyId) {
        String prefix = ontologyId.id() + "|";
        synchronized (activeReasoners) {
            for (String key : activeReasoners.keySet()) {
                if (key.startsWith(prefix)) return true;
            }
        }
        return reasoningReports.containsKey(ontologyId.id());
    }

    /**
     * Store the reasoning report for an ontology.
     */
    public void storeReasoningReport(OntologyId ontologyId, ReasoningReport report) {
        reasoningReports.put(ontologyId.id(), report);
    }

    /**
     * Get the reasoning report for an ontology.
     */
    public Optional<ReasoningReport> getReasoningReport(OntologyId ontologyId) {
        return Optional.ofNullable(reasoningReports.get(ontologyId.id()));
    }

    public boolean isClassified(OntologyId ontologyId) {
        return Boolean.TRUE.equals(classifiedMap.get(ontologyId.id()));
    }

    public void markClassified(OntologyId ontologyId) {
        classifiedMap.put(ontologyId.id(), Boolean.TRUE);
    }

    public String getCachedProfile(OntologyId ontologyId) {
        return profileCacheMap.get(ontologyId.id());
    }

    public void setCachedProfile(OntologyId ontologyId, String profile) {
        profileCacheMap.put(ontologyId.id(), profile);
    }

    /**
     * List all available reasoner adapters with capabilities.
     */
    public ReasonerListResult listReasoners() {
        List<ReasonerCapability> capabilities = new ArrayList<>();
        for (Map.Entry<String, OWLReasonerAdapter> entry : adapterRegistry.entrySet()) {
            OWLReasonerAdapter adapter = entry.getValue();
            capabilities.add(new ReasonerCapability(
                adapter.getName(), adapter.getSupportedProfiles(),
                adapter.getSupportedOperations(), adapter.supportsExplanation(),
                adapter.supportsConsistency(), adapter.supportsTemporaryOntology()));
        }
        return new ReasonerListResult(capabilities);
    }

    private OWLReasonerAdapter createAdapter(String reasonerName) {
        // Create a fresh instance each time to avoid state sharing
        switch (reasonerName) {
            case "HermiT":
                return new HermiTAdapter();
            case "ELK":
                return new ELKAdapter();
            case "Openllet":
                return new OpenlletAdapter();
            default:
                throw new IllegalArgumentException("Unknown reasoner: " + reasonerName);
        }
    }

    /**
     * Map any-case input ("hermit", "HERMIT", "HermiT") to the canonical
     * registry key ("HermiT", "ELK", "Openllet"). Returns the input unchanged
     * when no registry match is found so the downstream switch can throw a
     * precise "Unknown reasoner" error.
     */
    private String canonicalReasonerName(String reasonerName) {
        if (reasonerName == null) return null;
        for (String key : adapterRegistry.keySet()) {
            if (key.equalsIgnoreCase(reasonerName)) {
                return key;
            }
        }
        return reasonerName;
    }

    /**
     * Shut down all active reasoners (called on session end).
     */
    public void shutdownAll() {
        List<OWLReasonerAdapter> toShutdown = new ArrayList<>();
        synchronized (activeReasoners) {
            toShutdown.addAll(activeReasoners.values());
            activeReasoners.clear();
            inUseCount.clear();
        }
        for (OWLReasonerAdapter adapter : toShutdown) {
            try {
                adapter.shutdown();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Failed to shut down reasoner during shutdownAll", ex);
            }
        }
        classifiedMap.clear();
        profileCacheMap.clear();
    }

    // ── OntologyReloadListener implementation ──────────────────────────

    /**
     * Called by {@link org.owl4agents.owlapi.OntologyCache} when a single
     * ontology file has changed (mtime/size mismatch detected). Delegates
     * to {@link #shutdownReasoner(OntologyId)} which removes all adapters
     * for the ontology from the LRU map first, then calls
     * {@code adapter.shutdown()} to release HermiT/ELK/Openllet native
     * resources (thread pools, memory).
     *
     * <p>The next {@link #getOrCreateReasoner} call will build a fresh
     * adapter bound to the new {@link OWLOntology} instance.</p>
     */
    @Override
    public void onOntologyReloaded(OntologyId ontologyId) {
        shutdownReasoner(ontologyId);
    }

    /**
     * Called by {@link org.owl4agents.owlapi.OntologyCache} when all
     * ontologies are invalidated. Iterates all cached ontologyIds and
     * shuts down each reasoner adapter.
     */
    @Override
    public void onAllOntologiesReloaded() {
        // Take a snapshot of unique ontology IDs (strip the "|reasonerName" suffix)
        Set<String> ontologyIds = new HashSet<>();
        synchronized (activeReasoners) {
            for (String key : activeReasoners.keySet()) {
                int sep = key.indexOf('|');
                if (sep > 0) {
                    ontologyIds.add(key.substring(0, sep));
                }
            }
        }
        for (String ontId : ontologyIds) {
            shutdownReasoner(new OntologyId(ontId));
        }
    }

    // ── v0.8.6 D3 test helpers (package-private) ──────────────────────

    /**
     * v0.8.6 D3: Test helper to insert a reasoner adapter directly into the
     * LRU map without going through {@link #getOrCreateReasoner}. Used by
     * LRU/access-order/in-use-protection unit tests to drive eviction
     * scenarios with mock adapters (avoiding the cost of loading a real
     * ontology).
     *
     * <p>Does NOT increment {@link #inUseCount} — the entry is inserted
     * with count = 0 (or whatever the test previously set via
     * {@link #inUseCount}). The test is responsible for manipulating
     * {@code inUseCount} to simulate active calls.</p>
     *
     * <p>Triggers {@link #evictIfFull()} after insertion.</p>
     *
     * @param key     the compound key ({@code ontologyId.id() + "|" + reasonerName})
     * @param adapter the adapter to insert
     */
    void insertReasonerForTest(String key, OWLReasonerAdapter adapter) {
        synchronized (activeReasoners) {
            activeReasoners.put(key, adapter);
            // Ensure inUseCount entry exists (default 0). Tests that need
            // in-use protection will increment via inUseCount directly.
            inUseCount.computeIfAbsent(key, k -> new AtomicLong(0));
            evictIfFull();
        }
    }
}
