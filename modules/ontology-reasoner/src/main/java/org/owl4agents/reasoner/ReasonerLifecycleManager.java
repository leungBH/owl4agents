package org.owl4agents.reasoner;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.OntologyReloadListener;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class ReasonerLifecycleManager implements OntologyReloadListener {

    private final Map<String, OWLReasonerAdapter> activeReasoners = new ConcurrentHashMap<>();
    private final Map<String, String> activeReasonerNames = new ConcurrentHashMap<>();
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
     * Get or create the reasoner adapter for the given ontology.
     * If a reasoner of the same type is already active, reuse it.
     * If the reasoner type changes, shut down the old one and create a new one.
     */
    public OWLReasonerAdapter getOrCreateReasoner(OntologyId ontologyId, String reasonerName,
                                                    OWLOntology ontology, String detectedProfile,
                                                    boolean explanationRequested) {
        String key = ontologyId.id();

        // Auto-select if needed
        if ("auto".equalsIgnoreCase(reasonerName) || reasonerName == null) {
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult selection = selector.select(detectedProfile, explanationRequested);
            reasonerName = selection.reasonerName();
        }

        // Normalize to canonical registry name (case-insensitive match)
        reasonerName = canonicalReasonerName(reasonerName);

        // Check if we already have an active reasoner of the same type
        OWLReasonerAdapter existing = activeReasoners.get(key);
        String existingName = activeReasonerNames.get(key);

        if (existing != null && existing.isActive() && existingName.equals(reasonerName)) {
            return existing; // Reuse existing reasoner
        }

        // Shut down existing reasoner if type changed or it's no longer active
        if (existing != null) {
            existing.shutdown();
            activeReasoners.remove(key);
            activeReasonerNames.remove(key);
        }

        // Create new adapter instance
        OWLReasonerAdapter newAdapter = createAdapter(reasonerName);
        newAdapter.initialize(ontology);

        activeReasoners.put(key, newAdapter);
        activeReasonerNames.put(key, reasonerName);

        return newAdapter;
    }

    /**
     * Shut down the reasoner for a given ontology.
     * Called when ontology is re-imported or session ends.
     */
    public void shutdownReasoner(OntologyId ontologyId) {
        String key = ontologyId.id();
        OWLReasonerAdapter adapter = activeReasoners.remove(key);
        activeReasonerNames.remove(key);
        classifiedMap.remove(key);
        profileCacheMap.remove(key);
        if (adapter != null) {
            adapter.shutdown();
        }
    }

    /**
     * Get the active reasoner for a given ontology (if any).
     */
    public Optional<OWLReasonerAdapter> getActiveReasoner(OntologyId ontologyId) {
        String key = ontologyId.id();
        OWLReasonerAdapter adapter = activeReasoners.get(key);
        if (adapter != null && adapter.isActive()) {
            return Optional.of(adapter);
        }
        return Optional.empty();
    }

    /**
     * Check whether reasoning has been run for a given ontology.
     */
    public boolean hasReasoningRun(OntologyId ontologyId) {
        String key = ontologyId.id();
        return activeReasoners.containsKey(key) || reasoningReports.containsKey(key);
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
                adapter.getSupportedOperations(), adapter.supportsExplanation()));
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
        for (OWLReasonerAdapter adapter : activeReasoners.values()) {
            adapter.shutdown();
        }
        activeReasoners.clear();
        activeReasonerNames.clear();
        classifiedMap.clear();
        profileCacheMap.clear();
    }

    // ── OntologyReloadListener implementation ──────────────────────────

    /**
     * Called by {@link org.owl4agents.owlapi.OntologyCache} when a single
     * ontology file has changed (mtime/size mismatch detected). Delegates
     * to {@link #shutdownReasoner(OntologyId)} which removes the adapter
     * from the cache first, then calls {@code adapter.shutdown()} to
     * release HermiT/ELK/Openllet native resources (thread pools, memory).
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
        // Take a snapshot of keys to avoid ConcurrentModificationException
        Set<String> keys = new HashSet<>(activeReasoners.keySet());
        for (String key : keys) {
            shutdownReasoner(new OntologyId(key));
        }
    }
}