package org.owl4agents.reasoner;

import org.owl4agents.core.*;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.EntitySignatureCache;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.OntologyIriResolver;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.reasoner.wrapper.ReasonerCallWrapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.Node;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Default implementation of ReasonerService.
 * Manages reasoner lifecycle, executes reasoning operations, and stores inferred results.
 */
public class ReasonerServiceImpl implements ReasonerService, org.owl4agents.owlapi.OntologyReloadListener {

    /**
     * v0.8.7 post-release fix: above this class count, ALL explicit reasoner
     * selections are rejected (even ELK). v0.8.7's additional heap footprint
     * (Jena SHACL + ToolCall pipeline + overlay) makes 4GB insufficient for
     * >50K class ontologies regardless of reasoner choice.
     */
    static final int VERY_LARGE_ONTOLOGY_CLASS_THRESHOLD = 50_000;

    private final ReasonerLifecycleManager lifecycleManager;
    private final CatalogStore catalogStore;
    private final String workspaceBasePath;
    private final String workspaceName;
    private final OntologyCache ontologyCache;
    private final EntitySignatureCacheManager entitySignatureCacheManager;

    // v0.8.4 Decision 6: in-memory index of inferred class hierarchy per ontology.
    // Key = ontologyId.id(), Value = Map<subjectIRI, Set<objectIRI>>.
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Set<String>>> inferredHierarchyCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // v0.8.5 D5/D7: Source ontology consistency cache.
    // Key = "<ontologyId>|<fingerprint>|<reasonerName>|<importsState>"
    // Value = Boolean (true = consistent, false = inconsistent).
    // v0.8.6 D4 task 5.8: Migrated from LinkedHashMap with manual LRU eviction
    // (256 entries) to Caffeine Cache with maximumSize(200),
    // expireAfterAccess(2h), recordStats(). Caffeine's W-TinyLFU policy
    // provides better hit rates than LRU and the stats() API exposes
    // hit/miss/eviction counts for monitoring.
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> sourceConsistencyCache =
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterAccess(java.time.Duration.ofHours(2))
            .recordStats()
            .build();
    private final java.util.concurrent.atomic.AtomicLong sourceConsistencyCacheHits =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong sourceConsistencyCacheMisses =
        new java.util.concurrent.atomic.AtomicLong(0);

    // v0.8.5 D7: Platform-thread ExecutorService for exact consistency checks.
    // NOT virtual threads (HermiT/Openllet use synchronized blocks that pin
    // virtual thread carriers, preventing unmount on timeout).
    // v0.8.6: Retained for backward compatibility but new reasoner calls
    // route through {@link #reasonerCallWrapper} which enforces strict
    // serial execution via single-thread SynchronousQueue executor.
    private final java.util.concurrent.ExecutorService exactCheckExecutor =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "owl4agents-exact-check");
            t.setDaemon(true);
            return t;
        });

    // v0.8.5 D3/D4: TemporaryOntologyFactory + TransientReasonerSession collaborators.
    private final TemporaryOntologyFactory temporaryOntologyFactory = new TemporaryOntologyFactory();

    // v0.8.6 D1: Unified ReasonerCallWrapper with single-thread SynchronousQueue executor.
    // Provides timeout enforcement, ELK fallback, executor recovery for ALL reasoner calls.
    // Strict serial execution (no queueing) prevents thundering-herd OOM on large ontologies.
    private final ReasonerCallWrapper reasonerCallWrapper = createReasonerCallWrapper();
    private final long reasonerTimeoutSec = resolveReasonerTimeoutSec();

    private static long resolveReasonerTimeoutSec() {
        String raw = System.getProperty("owl4agents.reasoner.timeout.seconds", "30");
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed < 1 ? 1 : parsed;
        } catch (NumberFormatException e) {
            java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName())
                .warning("Invalid owl4agents.reasoner.timeout.seconds='" + raw + "'; using default 30");
            return 30;
        }
    }

    private static ReasonerCallWrapper createReasonerCallWrapper() {
        long timeoutSec = resolveReasonerTimeoutSec();
        java.util.concurrent.ExecutorService reasonerExecutor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "owl4agents-reasoner-wrapper");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        return new ReasonerCallWrapper(reasonerExecutor, timeoutSec);
    }

    // v0.8.5 P1 fix: Cached exact-check session per (ontologyId, reasonerName).
    // The base ontology copy (without claim axiom) and reasoner are created once
    // and reused across claims. Each claim axiom is added via applyChange(AddAxiom),
    // consistency is checked, then the axiom is removed via applyChange(RemoveAxiom)
    // to restore the base state. This reduces per-claim cost from ~5s (copy + reasoner
    // init for Mondo 226MB) to milliseconds (incremental consistency check).
    // Cache is invalidated on ontology reload (see onOntologyReloaded).
    private final java.util.concurrent.ConcurrentHashMap<String, CachedExactCheckSession> exactCheckSessionCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Get the lifecycle manager for sharing with other services (e.g. consistency analysis).
     */
    public ReasonerLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Get the entity signature cache manager (v0.8.4). May be {@code null} when
     * constructed via deprecated constructors.
     */
    public EntitySignatureCacheManager getEntitySignatureCacheManager() {
        return entitySignatureCacheManager;
    }

    /**
     * @deprecated Use the 4-arg constructor with shared {@link OntologyCache}
     *             for cross-service cache sharing. This constructor creates
     *             a standalone cache that is not shared with other services.
     */
    @Deprecated
    public ReasonerServiceImpl(CatalogStore catalogStore, String workspaceBasePath) {
        this(catalogStore, workspaceBasePath, "default");
    }

    /**
     * @deprecated Use the 4-arg constructor with shared {@link OntologyCache}
     *             for cross-service cache sharing. This constructor creates
     *             a standalone cache that is not shared with other services.
     */
    @Deprecated
    public ReasonerServiceImpl(CatalogStore catalogStore, String workspaceBasePath, String workspaceName) {
        this.lifecycleManager = new ReasonerLifecycleManager();
        this.catalogStore = catalogStore;
        this.workspaceBasePath = workspaceBasePath;
        this.workspaceName = workspaceName;
        this.ontologyCache = new OntologyCache(workspaceBasePath, workspaceName);
        this.ontologyCache.addReloadListener(this.lifecycleManager);
        this.ontologyCache.addReloadListener(this);
        this.entitySignatureCacheManager = null;
    }

    /**
     * @deprecated Use the 5-arg constructor with {@link EntitySignatureCacheManager}
     *             for O(1) entity signature lookups in {@code checkAxiomEntailment}.
     */
    @Deprecated
    public ReasonerServiceImpl(CatalogStore catalogStore, String workspaceBasePath,
                                String workspaceName, OntologyCache ontologyCache) {
        this.lifecycleManager = new ReasonerLifecycleManager();
        this.catalogStore = catalogStore;
        this.workspaceBasePath = workspaceBasePath;
        this.workspaceName = workspaceName;
        this.ontologyCache = ontologyCache;
        this.ontologyCache.addReloadListener(this.lifecycleManager);
        this.ontologyCache.addReloadListener(this);
        this.entitySignatureCacheManager = null;
    }

    /**
     * v0.8.4: Primary constructor with shared {@link OntologyCache} and
     * {@link EntitySignatureCacheManager} for O(1) entity signature lookups.
     *
     * @param catalogStore                the catalog store for ontology metadata
     * @param workspaceBasePath           absolute path to the workspace root
     * @param workspaceName               workspace name (e.g. {@code "default"})
     * @param ontologyCache               shared {@link OntologyCache} instance
     * @param entitySignatureCacheManager shared {@link EntitySignatureCacheManager}
     *                                    for asserted axiom index lookups
     */
    public ReasonerServiceImpl(CatalogStore catalogStore, String workspaceBasePath,
                                String workspaceName, OntologyCache ontologyCache,
                                EntitySignatureCacheManager entitySignatureCacheManager) {
        this.lifecycleManager = new ReasonerLifecycleManager();
        this.catalogStore = catalogStore;
        this.workspaceBasePath = workspaceBasePath;
        this.workspaceName = workspaceName;
        this.ontologyCache = ontologyCache;
        this.entitySignatureCacheManager = entitySignatureCacheManager;
        this.ontologyCache.addReloadListener(this.lifecycleManager);
        // v0.8.4 Decision 6: register self to clear inferredHierarchyCache on reload
        this.ontologyCache.addReloadListener(this);
    }

    // ── OntologyReloadListener implementation (v0.8.4 Decision 6, v0.8.5 D5) ──

    @Override
    public void onOntologyReloaded(OntologyId ontologyId) {
        inferredHierarchyCache.remove(ontologyId.id());
        // v0.8.5 D5: invalidate source consistency cache entries for this ontology
        invalidateSourceConsistencyCache(ontologyId.id());
        // v0.8.5 P1: invalidate cached exact-check session for this ontology
        invalidateExactCheckSessionCache(ontologyId.id());
        // v0.8.6 D4 task 5.3: invalidate the global EntitySignatureCache so
        // stale entity IRIs from the old ontology version do not produce
        // false-positive contains() results after reload.
        EntitySignatureCache.invalidateAll();
    }

    @Override
    public void onAllOntologiesReloaded() {
        inferredHierarchyCache.clear();
        // v0.8.5 D5: clear all source consistency cache entries
        // v0.8.6 task 5.8: Caffeine cache — invalidateAll() replaces manual clear.
        sourceConsistencyCache.invalidateAll();
        // v0.8.5 P1: close all cached exact-check sessions
        closeAllExactCheckSessionCache();
        // v0.8.6 D4 task 5.3: invalidate the global EntitySignatureCache so
        // stale entity IRIs from the old ontologies do not produce
        // false-positive contains() results after a full reload.
        EntitySignatureCache.invalidateAll();
    }

    @Override
    public ServiceResult<ReasonerListResult> listReasoners() {
        return ServiceResult.success(lifecycleManager.listReasoners(), ResultMetadata.empty());
    }

    @Override
    public ServiceResult<ReasoningReport> runReasoner(OntologyId ontologyId, Optional<String> reasonerName) {
        long totalStart = System.currentTimeMillis();
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            boolean explanationRequested = reasonerName.map("openllet"::equalsIgnoreCase).orElse(false);
            effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, explanationRequested, ontology);

            long initStart = System.currentTimeMillis();
            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);
            long initTime = System.currentTimeMillis() - initStart;

            long classStart = System.currentTimeMillis();
            ClassificationResult classificationResult = adapter.classify(ontologyId.id());
            long classTime = System.currentTimeMillis() - classStart;

            long realStart = System.currentTimeMillis();
            RealizationResult realizationResult = adapter.realize(ontologyId.id());
            long realTime = System.currentTimeMillis() - realStart;

            ConsistencyResult consistencyResult = adapter.checkConsistency(ontologyId.id());

            long totalTime = System.currentTimeMillis() - totalStart;

            // Build reasoning report
            Map<String, Integer> axiomCounts = new LinkedHashMap<>();
            axiomCounts.put("SubClassOf", classificationResult.delta().size());
            axiomCounts.put("InferredIndividualType", realizationResult.delta().size());

            ReasoningReport report = new ReasoningReport(
                ontologyId.id(),
                effectiveReasonerName,
                detectedProfile,
                true,
                true,
                consistencyResult.consistent(),
                new ReasoningReport.TimingBreakdown(initTime, classTime, realTime, totalTime),
                0,
                axiomCounts,
                null
            );

            // Store inferred data and report
            storeInferredData(ontologyId, classificationResult, realizationResult);
            lifecycleManager.storeReasoningReport(ontologyId, report);
            storeReasoningReportFile(ontologyId, report);

            return ServiceResult.success(report, ResultMetadata.empty());

        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Unknown reasoner") || e.getMessage().contains("PROFILE_NOT_SUPPORTED")) {
                return ServiceResult.error(ErrorCode.PROFILE_NOT_SUPPORTED, e.getMessage());
            }
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (Exception e) {
            ReasoningReport.TimingBreakdown timing = new ReasoningReport.TimingBreakdown(
                0, 0, 0, System.currentTimeMillis() - totalStart);
            ReasoningReport report = new ReasoningReport(
                ontologyId.id(), reasonerName.orElse("unknown"), "unknown",
                false, null, false, timing, 0, Map.of(),
                new ReasoningReport.ErrorDetails("reasoner-initialization-failed", e.getMessage(), null));
            lifecycleManager.storeReasoningReport(ontologyId, report);
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } finally {
            // v0.8.6 D3: Release the reference count acquired by getOrCreateReasoner.
            // The adapter stays in the LRU map (eligible for reuse) but can now be
            // evicted if MAX_ACTIVE_REASONERS is exceeded.
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<ClassificationResult> classify(OntologyId ontologyId, Optional<String> reasonerName) {
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            boolean explanationRequested = false;
            effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, explanationRequested, ontology);

            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            ClassificationResult result = adapter.classify(ontologyId.id());
            lifecycleManager.markClassified(ontologyId);
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } finally {
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<RealizationResult> realize(OntologyId ontologyId, Optional<String> reasonerName) {
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false, ontology);

            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

            RealizationResult result = adapter.realize(ontologyId.id());
            lifecycleManager.markClassified(ontologyId);
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } finally {
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<ConsistencyResult> checkConsistency(OntologyId ontologyId, Optional<String> reasonerName) {
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false, ontology);

            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

            ConsistencyResult result = adapter.checkConsistency(ontologyId.id());
            lifecycleManager.markClassified(ontologyId);
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } finally {
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<List<String>> getUnsatClasses(OntologyId ontologyId) {
        Optional<OWLReasonerAdapter> adapter = lifecycleManager.getActiveReasoner(ontologyId);
        if (adapter.isEmpty()) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                "Reasoning has not been executed. Run reasoning first before accessing inferred results.");
        }
        try {
            Set<String> unsatClasses = adapter.get().getUnsatClasses();
            return ServiceResult.success(new ArrayList<>(unsatClasses), ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<InconsistencyExplanation> explainInconsistency(OntologyId ontologyId, Optional<String> reasonerName) {
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            // For explanation, prefer Openllet
            boolean explanationRequested = true;
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            effectiveReasonerName = reasonerName.orElse("openllet");

            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            InconsistencyExplanation explanation = adapter.explainInconsistency(ontologyId.id());

            if (explanation == null) {
                return ServiceResult.error(ErrorCode.ONTOLOGY_CONSISTENT,
                "The ontology is consistent; no inconsistency explanation is needed.");
            }

            return ServiceResult.success(explanation, ResultMetadata.empty());
        } catch (UnsupportedOperationException e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_NOT_SUPPORTED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_FAILED, e.getMessage());
        } finally {
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<UnsatClassExplanation> explainUnsatClass(OntologyId ontologyId, String classIRI, Optional<String> reasonerName) {
        OWLReasonerAdapter adapter = null;
        String effectiveReasonerName = null;
        try {
            boolean explanationRequested = true;
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            effectiveReasonerName = reasonerName.orElse("openllet");

            adapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, explanationRequested);

            UnsatClassExplanation explanation = adapter.explainUnsatClass(ontologyId.id(), classIRI);

            if (explanation == null) {
                return ServiceResult.error(ErrorCode.ONTOLOGY_CONSISTENT,
                "The class is satisfiable; no unsatisfiability explanation is needed.");
            }

            return ServiceResult.success(explanation, ResultMetadata.empty());
        } catch (UnsupportedOperationException e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_NOT_SUPPORTED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.EXPLANATION_FAILED, e.getMessage());
        } finally {
            if (adapter != null && effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Override
    public ServiceResult<ReasoningReport> getReasoningReport(OntologyId ontologyId) {
        // Try in-memory first (fast path for same-session access)
        Optional<ReasoningReport> report = lifecycleManager.getReasoningReport(ontologyId);
        if (report.isPresent()) {
            return ServiceResult.success(report.get(), ResultMetadata.empty());
        }
        // Filesystem fallback: read persisted reasoning-report.json from prior CLI invocation
        try {
            Path reportFile = getInferredDir(ontologyId).resolve("reasoning-report.json");
            if (Files.exists(reportFile)) {
                ReasoningReport persisted = deserializeReportFromJson(Files.readString(reportFile));
                if (persisted != null) {
                    return ServiceResult.success(persisted, ResultMetadata.empty());
                }
            }
        } catch (Exception e) {
            // Fall through to error
        }
        return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
            "Reasoning has not been executed. No reasoning report available.");
    }

    @Override
    public ServiceResult<InferredFactsResult> getInferredFacts(OntologyId ontologyId, Optional<String> entityIRI) {
        // Allow access if reasoning ran in this session OR a prior session left persisted results
        if (!lifecycleManager.hasReasoningRun(ontologyId)) {
            Path reportFile = getInferredDir(ontologyId).resolve("reasoning-report.json");
            if (!Files.exists(reportFile)) {
                return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                    "Reasoning has not been executed. Run reasoning first before accessing inferred facts.");
            }
        }
        try {
            Path inferredDir = getInferredDir(ontologyId);
            Path factsFile = inferredDir.resolve("inferred-facts.jsonl");
            if (!Files.exists(factsFile)) {
                return ServiceResult.success(
                    new InferredFactsResult(ontologyId.id(), entityIRI.orElse(null), List.of()),
                    ResultMetadata.empty());
            }

            List<InferredFact> facts = readInferredFactsFile(factsFile);

            if (entityIRI.isPresent()) {
                String entity = entityIRI.get();
                facts = facts.stream()
                    .filter(f -> f.subjectIRI().equals(entity) ||
                                 (f.objectIRI() != null && f.objectIRI().equals(entity)))
                    .collect(Collectors.toList());
            }

            return ServiceResult.success(
                new InferredFactsResult(ontologyId.id(), entityIRI.orElse(null), facts),
                ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.REASONING_NOT_RUN, e.getMessage());
        }
    }

    /**
     * v0.8.1 ISSUE-03: Equivalent-classes entailment using OWL API
     * {@link org.semanticweb.owlapi.model.OWLClassExpression} operands (subject + object).
     * The v0.8.0 overload that takes a {@code Map<String, String>} can only handle
     * named classes; this overload supports complex expressions (intersection,
     * union, complement, etc.) by delegating to
     * {@code ClassExpressionBuilder.build} on the caller's side and
     * running {@code reasoner.isEntailed(OWLEquivalentClassesAxiom)} on
     * the constructed axiom.
     */
    @Deprecated
    @Override
    public ServiceResult<EntailmentResult> checkEquivalentClassesEntailment(
            OntologyId ontologyId,
            org.semanticweb.owlapi.model.OWLClassExpression subject,
            org.semanticweb.owlapi.model.OWLClassExpression object,
            Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            return checkEquivalentClassesEntailmentImpl(ontology, ontologyId, subject, object, reasonerName);
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED,
                "Failed to check EquivalentClasses entailment: " + e.getMessage());
        }
    }

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}.
     */
    @Override
    public ServiceResult<EntailmentResult> checkEquivalentClassesEntailment(
            OWLOntology ontology,
            OntologyId ontologyId,
            org.semanticweb.owlapi.model.OWLClassExpression subject,
            org.semanticweb.owlapi.model.OWLClassExpression object,
            Optional<String> reasonerName) {
        try {
            return checkEquivalentClassesEntailmentImpl(ontology, ontologyId, subject, object, reasonerName);
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED,
                "Failed to check EquivalentClasses entailment: " + e.getMessage());
        }
    }

    private ServiceResult<EntailmentResult> checkEquivalentClassesEntailmentImpl(
            OWLOntology ontology, OntologyId ontologyId,
            org.semanticweb.owlapi.model.OWLClassExpression subject,
            org.semanticweb.owlapi.model.OWLClassExpression object,
            Optional<String> reasonerName) throws Exception {
        String detectedProfile = detectProfile(ontologyId, ontology);
        String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false, ontology);

        OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
            ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

        try {
            if (!adapter.isActive()) {
                return ServiceResult.error(ErrorCode.REASONING_NOT_RUN,
                    "Reasoner is not active after initialization for ontology: " + ontologyId.id());
            }

            if (!lifecycleManager.isClassified(ontologyId)) {
                try {
                    adapter.getUnderlyingReasoner().precomputeInferences(InferenceType.CLASS_HIERARCHY);
                } catch (Exception ignored) {
                }
                lifecycleManager.markClassified(ontologyId);
            }

            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom axiom =
                df.getOWLEquivalentClassesAxiom(subject, object);

            // Asserted check (rare for complex expressions but possible)
            boolean asserted = ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES, Imports.INCLUDED).stream()
                .anyMatch(ax -> ax.getClassExpressions().size() == 2
                    && ax.getClassExpressions().contains(subject)
                    && ax.getClassExpressions().contains(object));
            if (asserted) {
                return ServiceResult.success(
                    new EntailmentResult(ontologyId.id(), "EquivalentClasses",
                        EntailmentResult.ENTAILED, "asserted", effectiveReasonerName, null),
                    ResultMetadata.empty());
            }

            boolean entailed;
            try {
                entailed = adapter.getUnderlyingReasoner().isEntailed(axiom);
            } catch (Exception e) {
                return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED,
                    "isEntailed failed for EquivalentClasses: " + e.getMessage());
            }

            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), "EquivalentClasses",
                    entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                    entailed ? "inferred" : null, effectiveReasonerName, null),
                ResultMetadata.empty());
        } finally {
            // v0.8.6 D3: Release the reference count acquired by getOrCreateReasoner.
            if (effectiveReasonerName != null) {
                lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
            }
        }
    }

    @Deprecated
    @Override
    public ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                             Map<String, String> parameters, Optional<String> reasonerName) {
        // Validate axiom type support
        Set<String> supportedTypes = Set.of(
            "SubClassOf", "EquivalentClasses", "DisjointClasses", "ClassAssertion",
            "ObjectPropertyAssertion", "DataPropertyAssertion", "ObjectPropertyDomain", "ObjectPropertyRange",
            "DataPropertyDomain", "DataPropertyRange",
            "DifferentIndividuals", "SubObjectPropertyOf",
            "SameIndividual");

        if (!supportedTypes.contains(axiomType)) {
            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), axiomType, EntailmentResult.UNSUPPORTED_AXIOM_TYPE, null, null, null),
                ResultMetadata.empty());
        }

        if (parameters == null || parameters.isEmpty()) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_PARAMETERS,
                "Required axiom fields are missing or malformed for axiom type: " + axiomType);
        }

        try {
            OWLOntology ontology = loadOntology(ontologyId);
            return checkEntailmentImpl(ontology, ontologyId, axiomType, parameters, reasonerName);
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    /**
     * v0.8.4: Overload that accepts a pre-loaded {@link OWLOntology}, avoiding
     * redundant ontology loading in the claim verification hot path.
     */
    @Override
    public ServiceResult<EntailmentResult> checkEntailment(OWLOntology ontology, OntologyId ontologyId,
                                                             String axiomType,
                                                             Map<String, String> parameters,
                                                             Optional<String> reasonerName) {
        Set<String> supportedTypes = Set.of(
            "SubClassOf", "EquivalentClasses", "DisjointClasses", "ClassAssertion",
            "ObjectPropertyAssertion", "DataPropertyAssertion", "ObjectPropertyDomain", "ObjectPropertyRange",
            "DataPropertyDomain", "DataPropertyRange",
            "DifferentIndividuals", "SubObjectPropertyOf",
            "SameIndividual");

        if (!supportedTypes.contains(axiomType)) {
            return ServiceResult.success(
                new EntailmentResult(ontologyId.id(), axiomType, EntailmentResult.UNSUPPORTED_AXIOM_TYPE, null, null, null),
                ResultMetadata.empty());
        }

        if (parameters == null || parameters.isEmpty()) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_PARAMETERS,
                "Required axiom fields are missing or malformed for axiom type: " + axiomType);
        }

        try {
            return checkEntailmentImpl(ontology, ontologyId, axiomType, parameters, reasonerName);
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    private ServiceResult<EntailmentResult> checkEntailmentImpl(OWLOntology ontology, OntologyId ontologyId,
                                                                  String axiomType,
                                                                  Map<String, String> parameters,
                                                                  Optional<String> reasonerName) throws Exception {
        Optional<OWLReasonerAdapter> adapter = lifecycleManager.getActiveReasoner(ontologyId);
        if (adapter.isEmpty()) {
            String detectedProfile = detectProfile(ontologyId, ontology);
            String effectiveReasonerName = resolveReasonerName(reasonerName, detectedProfile, false, ontology);
            OWLReasonerAdapter newAdapter = lifecycleManager.getOrCreateReasoner(
                ontologyId, effectiveReasonerName, ontology, detectedProfile, false);

            try {
                boolean entailed = checkAxiomEntailmentByType(newAdapter, ontologyId, ontology, axiomType, parameters);
                String source = determineSource(ontologyId, ontology, axiomType, parameters);

                return ServiceResult.success(
                    new EntailmentResult(ontologyId.id(), axiomType,
                        entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                        source, effectiveReasonerName, null),
                    ResultMetadata.empty());
            } finally {
                // v0.8.6 D3: Release the reference count acquired by getOrCreateReasoner.
                if (effectiveReasonerName != null) {
                    lifecycleManager.releaseReasoner(ontologyId, effectiveReasonerName);
                }
            }
        }

        boolean entailed = checkAxiomEntailmentByType(adapter.get(), ontologyId, ontology, axiomType, parameters);
        String source = determineSource(ontologyId, ontology, axiomType, parameters);

        return ServiceResult.success(
            new EntailmentResult(ontologyId.id(), axiomType,
                entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                source, adapter.get().getName(), null),
            ResultMetadata.empty());
    }

    @Override
    public ServiceResult<Void> shutdown(OntologyId ontologyId) {
        lifecycleManager.shutdownReasoner(ontologyId);
        return ServiceResult.success(null, ResultMetadata.empty());
    }

    @Override
    public ServiceResult<ReasonerSelectionResult> selectReasoner(OntologyId ontologyId, boolean explanationRequested) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            AutoReasonerSelector selector = new AutoReasonerSelector();
            ReasonerSelectionResult result = selector.select(detectedProfile, explanationRequested);
            return ServiceResult.success(result, ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
        }
    }

    // ── Private helpers ──

    /**
     * v0.8.1 ISSUE-03: public accessor for the loaded OWL ontology, used by
     * {@code ClaimVerificationService.verifyEquivalentClassesWithExpressions}
     * to resolve IRIs inside complex class expressions. Returns the
     * canonical {@code ontology.owl} for the given ontology ID.
     */
    public OWLOntology loadOntologyForClaim(OntologyId ontologyId) throws OWLOntologyCreationException {
        return loadOntology(ontologyId);
    }

    private OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        return ontologyCache.getOrCreate(ontologyId);
    }

    private String detectProfile(OntologyId ontologyId, OWLOntology ontology) {
        String cached = lifecycleManager.getCachedProfile(ontologyId);
        if (cached != null) return cached;
        String profile = computeProfile(ontology);
        lifecycleManager.setCachedProfile(ontologyId, profile);
        return profile;
    }

    private String computeProfile(OWLOntology ontology) {
        try {
            org.semanticweb.owlapi.profiles.OWL2DLProfile dlProfile = new org.semanticweb.owlapi.profiles.OWL2DLProfile();
            org.semanticweb.owlapi.profiles.OWL2ELProfile elProfile = new org.semanticweb.owlapi.profiles.OWL2ELProfile();

            if (elProfile.checkOntology(ontology).getViolations().isEmpty()) {
                return "OWL 2 EL";
            } else if (dlProfile.checkOntology(ontology).getViolations().isEmpty()) {
                return "OWL 2 DL";
            } else {
                return "OWL 2 Full";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * v0.8.6 D2: Resolve the effective reasoner name, passing the ontology's
     * class count and the explicit-override flag to the size-aware
     * {@link AutoReasonerSelector#select(String, boolean, int, boolean)}.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>When the user explicitly specified a non-{@code "auto"} reasoner
     *       ({@code explicitOverride=true}): honor the user's choice and
     *       return it directly. If the ontology is large ({@code classCount
     *       > 20K}), emit a {@code WARN} log — the user accepts the OOM risk.
     *       The size-aware branch in {@code select()} is bypassed by the
     *       early return; the explicitOverride parameter on {@code select()}
     *       exists for unit-testability of the bypass behavior.</li>
     *   <li>When {@code explicitOverride=false}: call the 4-arg
     *       {@code select()} with the ontology's class count. The size-aware
     *       branch may select ELK for large non-EL ontologies, or return an
     *       error result (carried on {@code ReasonerSelectionResult.errorCode()})
     *       when explanation is requested on a large non-EL ontology.</li>
     * </ul>
     *
     * @param reasonerName         the user-specified reasoner name (Optional, may be empty or "auto")
     * @param detectedProfile      the detected OWL profile of the ontology
     * @param explanationRequested whether explanation is requested (true only for explain* paths)
     * @param ontology             the loaded ontology (may be {@code null} — classCount=0, no WARN)
     * @return the effective reasoner name, or {@code null} if the selector returned an error result
     *         (caller is responsible for propagating the error if needed)
     */
    private String resolveReasonerName(Optional<String> reasonerName, String detectedProfile,
                                        boolean explanationRequested, OWLOntology ontology) {
        boolean explicitOverride = reasonerName.isPresent()
            && !"auto".equalsIgnoreCase(reasonerName.get());
        int classCount = (ontology != null) ? ontology.getClassesInSignature().size() : 0;

        if (explicitOverride) {
            String requested = reasonerName.get();
            // v0.8.7 post-release fix: reject hermit/openllet on large ontologies
            // to prevent OOM/timeout. HermiT is OWL 2 DL complete — infeasible
            // on >20K classes. Openllet (explanation) is even heavier.
            // ELK is generally safe but >50K classes can OOM in v0.8.7 due to
            // the additional heap footprint (Jena SHACL + ToolCall pipeline +
            // overlay). Return a clear error instead of a 25s timeout or OOM.
            if (classCount > VERY_LARGE_ONTOLOGY_CLASS_THRESHOLD) {
                throw new ReasonerIncompatibleException(
                    "Reasoner '" + requested + "' is not compatible with very large ontology"
                    + " (classCount=" + classCount + " > " + VERY_LARGE_ONTOLOGY_CLASS_THRESHOLD
                    + "). Use 'auto' or reduce ontology size.");
            }
            if (classCount > AutoReasonerSelector.LARGE_ONTOLOGY_CLASS_THRESHOLD
                    && ("hermit".equalsIgnoreCase(requested)
                        || "openllet".equalsIgnoreCase(requested))) {
                throw new ReasonerIncompatibleException(
                    "Reasoner '" + requested + "' is not compatible with large ontology"
                    + " (classCount=" + classCount + " > "
                    + AutoReasonerSelector.LARGE_ONTOLOGY_CLASS_THRESHOLD
                    + "). HermiT/Openllet perform full OWL 2 DL reasoning and will"
                    + " timeout or OOM. Use reasoner='auto' (selects ELK) or"
                    + " reasoner='elk' instead.");
            }
            return requested;
        }

        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult selection = selector.select(
            detectedProfile, explanationRequested, classCount, false);
        // v0.8.6 D2: when the size-aware branch returns an error result
        // (e.g. explanation requested on a large non-EL ontology), reasonerName
        // is null and errorCode is set. Callers of resolveReasonerName always
        // pass explanationRequested=false today, so this branch is not hit in
        // production; the null is surfaced for future callers that may need
        // to propagate the error via ServiceResult.
        return selection.reasonerName();
    }

    private Path getInferredDir(OntologyId ontologyId) {
        return Path.of(workspaceBasePath, workspaceName, "ontologies", ontologyId.id(), "inferred");
    }

    private void storeInferredData(OntologyId ontologyId,
                                    ClassificationResult classificationResult,
                                    RealizationResult realizationResult) throws IOException {
        Path inferredDir = getInferredDir(ontologyId);
        Files.createDirectories(inferredDir);

        // Write inferred-class-hierarchy.jsonl
        writeHierarchyJsonl(inferredDir.resolve("inferred-class-hierarchy.jsonl"), classificationResult.completeHierarchy());

        // Write inferred-types.jsonl
        writeTypesJsonl(inferredDir.resolve("inferred-types.jsonl"), realizationResult.completeTypes());

        // Write inferred-facts.jsonl (combining all inferred data)
        writeFactsJsonl(inferredDir.resolve("inferred-facts.jsonl"), classificationResult, realizationResult);
    }

    private void storeReasoningReportFile(OntologyId ontologyId, ReasoningReport report) throws IOException {
        Path inferredDir = getInferredDir(ontologyId);
        Files.createDirectories(inferredDir);

        // Write reasoning-report.json
        String json = serializeReportToJson(report);
        Files.writeString(inferredDir.resolve("reasoning-report.json"), json);
    }

    private void writeHierarchyJsonl(Path filePath, List<InferredHierarchyEntry> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredHierarchyEntry entry : entries) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"source\":\"%s\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.source(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private void writeTypesJsonl(Path filePath, List<InferredTypeEntry> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredTypeEntry entry : entries) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"source\":\"%s\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.source(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private void writeFactsJsonl(Path filePath, ClassificationResult classificationResult,
                                  RealizationResult realizationResult) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (InferredHierarchyEntry entry : classificationResult.delta()) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"literalValue\":null,\"axiomType\":\"SubClassOf\",\"source\":\"inferred\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.reasoner()));
                writer.newLine();
            }
            for (InferredTypeEntry entry : realizationResult.delta()) {
                writer.write(String.format(
                    "{\"ontologyId\":\"%s\",\"subjectIRI\":\"%s\",\"predicateIRI\":\"%s\",\"objectIRI\":\"%s\",\"literalValue\":null,\"axiomType\":\"Type\",\"source\":\"inferred\",\"reasoner\":\"%s\"}",
                    entry.ontologyId(), entry.subjectIRI(), entry.predicateIRI(), entry.objectIRI(), entry.reasoner()));
                writer.newLine();
            }
        }
    }

    private String serializeReportToJson(ReasoningReport report) {
        // Simple JSON serialization (no external JSON library)
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"ontologyId\": \"").append(escapeJson(report.ontologyId())).append("\",\n");
        sb.append("  \"reasonerName\": \"").append(escapeJson(report.reasonerName())).append("\",\n");
        sb.append("  \"owlProfile\": \"").append(escapeJson(report.owlProfile())).append("\",\n");
        sb.append("  \"classificationStatus\": ").append(report.classificationStatus()).append(",\n");
        sb.append("  \"realizationStatus\": ").append(report.realizationStatus() != null ? report.realizationStatus() : "null").append(",\n");
        sb.append("  \"consistencyStatus\": ").append(report.consistencyStatus()).append(",\n");

        ReasoningReport.TimingBreakdown timing = report.timingBreakdown();
        sb.append("  \"timingBreakdown\": {\n");
        sb.append("    \"initializationTimeMs\": ").append(timing.initializationTimeMs()).append(",\n");
        sb.append("    \"classificationTimeMs\": ").append(timing.classificationTimeMs()).append(",\n");
        sb.append("    \"realizationTimeMs\": ").append(timing.realizationTimeMs()).append(",\n");
        sb.append("    \"totalTimeMs\": ").append(timing.totalTimeMs()).append("\n");
        sb.append("  },\n");

        sb.append("  \"warningCount\": ").append(report.warningCount()).append(",\n");

        sb.append("  \"inferredAxiomCountsByType\": {\n");
        boolean first = true;
        for (Map.Entry<String, Integer> e : report.inferredAxiomCountsByType().entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(escapeJson(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("\n  },\n");

        ReasoningReport.ErrorDetails error = report.errorDetails();
        if (error != null) {
            sb.append("  \"errorDetails\": {\n");
            sb.append("    \"errorCode\": \"").append(escapeJson(error.errorCode())).append("\",\n");
            sb.append("    \"message\": \"").append(escapeJson(error.message())).append("\"");
            if (error.stackTrace() != null) {
                sb.append(",\n    \"stackTrace\": \"").append(escapeJson(error.stackTrace())).append("\"");
            }
            sb.append("\n  }\n");
        } else {
            sb.append("  \"errorDetails\": null\n");
        }

        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    private List<InferredFact> readInferredFactsFile(Path factsFile) throws IOException {
        List<InferredFact> facts = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(factsFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse simple JSON manually
                // For production, use a proper JSON parser
                InferredFact fact = parseInferredFact(line);
                if (fact != null) {
                    facts.add(fact);
                }
            }
        }
        return facts;
    }

    private InferredFact parseInferredFact(String jsonLine) {
        // Minimal JSON parsing for the JSONL format
        try {
            String ontologyId = extractJsonValue(jsonLine, "ontologyId");
            String subjectIRI = extractJsonValue(jsonLine, "subjectIRI");
            String predicateIRI = extractJsonValue(jsonLine, "predicateIRI");
            String objectIRI = extractJsonValue(jsonLine, "objectIRI");
            String literalValue = extractJsonValue(jsonLine, "literalValue");
            String axiomType = extractJsonValue(jsonLine, "axiomType");
            String source = extractJsonValue(jsonLine, "source");
            String reasoner = extractJsonValue(jsonLine, "reasoner");

            return new InferredFact(ontologyId, subjectIRI, predicateIRI, objectIRI, literalValue, axiomType, source, reasoner);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();

        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            // String value
            int end = json.indexOf('"', start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        } else if (json.charAt(start) == 'n') {
            // null value
            return null;
        } else {
            // Numeric or boolean value
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\n') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    private boolean checkAxiomEntailmentByType(OWLReasonerAdapter adapter, OntologyId ontologyId, OWLOntology ontology,
                                           String axiomType, Map<String, String> parameters) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

        if (adapter.isActive() && !lifecycleManager.isClassified(ontologyId)) {
            try {
                adapter.getUnderlyingReasoner().precomputeInferences(InferenceType.CLASS_HIERARCHY);
            } catch (Exception ignored) {
            }
            lifecycleManager.markClassified(ontologyId);
        }

        switch (axiomType) {
            case "SubClassOf": {
                String subclassIRI = parameters.get("subclass");
                String superclassIRI = parameters.get("superclass");
                if (subclassIRI == null || superclassIRI == null) return false;
                IRI subIri = OntologyIriResolver.resolveOntologyIRI(ontology, subclassIRI, "class");
                IRI supIri = OntologyIriResolver.resolveOntologyIRI(ontology, superclassIRI, "class");
                if (subIri == null || supIri == null) return false;
                OWLClass subClass = df.getOWLClass(subIri);
                OWLClass superClass = df.getOWLClass(supIri);

                // v0.8.4 Decision 5: check EntitySignatureCache index first (O(1) lookup)
                if (entitySignatureCacheManager != null) {
                    try {
                        EntitySignatureCache esc = entitySignatureCacheManager.getOrCreate(ontologyId, ontology);
                        if (esc.getSuperClasses(subclassIRI).contains(superclassIRI)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Check if asserted first
                boolean asserted = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED).stream()
                    .anyMatch(ax -> ax.getSubClass().equals(subClass) && ax.getSuperClass().equals(superClass));
                if (asserted) return true;

                // Check inferred via reasoner (v0.8.1: use getUnderlyingReasoner
                // instead of the v0.8.0 private getOWLReasonerFromAdapter bridge
                // which returned null and silently fell through).
                try {
                    if (adapter.isActive()) {
                        // v0.8.1 DEFECT-1 fix: false = transitive closure of all
                        // superclasses, NOT direct only. True would miss grand-parent
                        // classes (e.g. Dog -> Animal when only Dog->Mammal is direct).
                        NodeSet<OWLClass> inferredSupers =
                            adapter.getUnderlyingReasoner().getSuperClasses(subClass, false);
                        if (inferredSupers != null) {
                            return inferredSupers.getFlattened().contains(superClass);
                        }
                    }
                } catch (Exception e) {
                    // Fall back to checking stored inferred data
                }

                // Check in stored inferred hierarchy
                return checkStoredEntailment(ontologyId, ontology, "SubClassOf", subclassIRI, superclassIRI);
            }

            case "ClassAssertion": {
                String individualIRI = parameters.get("individual");
                String classIRI = parameters.get("class");
                if (individualIRI == null || classIRI == null) return false;

                IRI indIri = OntologyIriResolver.resolveOntologyIRI(ontology, individualIRI, "individual");
                IRI clsIri = OntologyIriResolver.resolveOntologyIRI(ontology, classIRI, "class");
                if (indIri == null || clsIri == null) return false;

                boolean asserted = ontology.getAxioms(AxiomType.CLASS_ASSERTION, Imports.INCLUDED).stream()
                    .anyMatch(ax -> ax.getIndividual().isNamed() &&
                        ax.getIndividual().asOWLNamedIndividual().getIRI().equals(indIri) &&
                        ax.getClassExpression().isNamed() &&
                        ax.getClassExpression().asOWLClass().getIRI().equals(clsIri));
                if (asserted) return true;

                return checkStoredEntailment(ontologyId, ontology, "Type", individualIRI, classIRI);
            }

            // v0.8.1 ISSUE-02 / v0.8.3 R7: ObjectPropertyDomain
            //   stage 1: exact match on asserted domain (existing)
            //   stage 2: extract named classes from complex domain expressions (new)
            //   stage 3: reasoner.getObjectPropertyDomains() + subclass check (new)
            //   stage 4: isEntailed fallback (existing)
            case "ObjectPropertyDomain": {
                String propertyIRI = parameters.get("propertyIRI");
                String domainIRI = parameters.get("domainIRI");
                if (propertyIRI == null || domainIRI == null) return false;
                IRI propIri = OntologyIriResolver.resolveOntologyIRI(ontology, propertyIRI, "object_property");
                IRI domIri = OntologyIriResolver.resolveOntologyIRI(ontology, domainIRI, "class");
                if (propIri == null || domIri == null) return false;
                OWLObjectProperty prop = df.getOWLObjectProperty(propIri);
                OWLClass domain = df.getOWLClass(domIri);

                // v0.8.3 R7 stage 1: exact match on asserted domain
                boolean asserted = ontology.getObjectPropertyDomainAxioms(prop).stream()
                    .anyMatch(ax -> ax.getDomain().equals(domain));
                if (asserted) return true;

                // v0.8.3 R7 stage 2: extract named classes from complex domain
                // expressions (e.g., ObjectIntersectionOf(PizzaBase, ...)) via
                // getSignature(). ax.getDomain().equals(domain) only matches the
                // exact complex expression, missing named classes nested inside.
                boolean assertedInComplex = ontology.getObjectPropertyDomainAxioms(prop).stream()
                    .map(ax -> ax.getDomain())
                    .flatMap(ce -> ce.getSignature().stream())
                    .filter(OWLClass.class::isInstance)
                    .map(OWLClass.class::cast)
                    .anyMatch(other -> other.equals(domain));
                if (assertedInComplex) return true;

                // v0.8.3 R7 stage 3: reasoner.getObjectPropertyDomains() query.
                // Check if domain is in the inferred domain set, or if any inferred
                // domain D is a subclass of domain (SubClassOf(D, domain)) — because
                // ObjectPropertyDomain(prop, D) + SubClassOf(D, domain) entails
                // ObjectPropertyDomain(prop, domain).
                // Direction is critical: SubClassOf(D, domain), NOT SubClassOf(domain, D).
                // The latter would cause false positives (e.g., ontology domain=Animal,
                // claim domain=Dog: SubClassOf(Dog,Animal)=true → wrong supported).
                try {
                    if (adapter.isActive()) {
                        NodeSet<OWLClass> domainSet =
                            adapter.getUnderlyingReasoner().getObjectPropertyDomains(prop, false);
                        Set<OWLClass> flattened = domainSet.getFlattened();
                        if (flattened.contains(domain)) {
                            return true;
                        }
                        for (OWLClass inferredDomain : flattened) {
                            if (adapter.getUnderlyingReasoner().isEntailed(
                                    df.getOWLSubClassOfAxiom(inferredDomain, domain))) {
                                return true;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to stage 3b
                }

                // v0.8.3 R7 stage 3b: inverse property range inference.
                // If prop has an inverse property inv, and inv has range R,
                // then prop has domain R (OWL inverse property semantics).
                // This is needed because ELK (OWL 2 EL) cannot infer domains
                // from inverse property ranges. e.g., isBaseOf has no explicit
                // domain, but its inverse hasBase has range PizzaBase, so
                // isBaseOf's domain is PizzaBase.
                try {
                    for (org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom invAx :
                            ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
                        if (!invAx.getFirstProperty().equals(prop)
                            && !invAx.getSecondProperty().equals(prop)) {
                            continue;
                        }
                        org.semanticweb.owlapi.model.OWLObjectPropertyExpression inversePropExpr =
                            invAx.getFirstProperty().equals(prop)
                                ? invAx.getSecondProperty()
                                : invAx.getFirstProperty();
                        if (!inversePropExpr.isNamed()) {
                            continue;
                        }
                        OWLObjectProperty inverseProp = (OWLObjectProperty) inversePropExpr;
                        // Check if inverse property's range includes domain
                        boolean rangeMatches = ontology.getObjectPropertyRangeAxioms(inverseProp).stream()
                            .flatMap(ax -> ax.getRange().getSignature().stream())
                            .filter(OWLClass.class::isInstance)
                            .map(OWLClass.class::cast)
                            .anyMatch(rangeClass -> rangeClass.equals(domain));
                        if (rangeMatches) return true;
                        // Also check if domain is a subclass of any range class
                        if (adapter.isActive()) {
                            boolean subclassOfRange = ontology.getObjectPropertyRangeAxioms(inverseProp).stream()
                                .flatMap(ax -> ax.getRange().getSignature().stream())
                                .filter(OWLClass.class::isInstance)
                                .map(OWLClass.class::cast)
                                .anyMatch(rangeClass -> {
                                    try {
                                        return adapter.getUnderlyingReasoner().isEntailed(
                                            df.getOWLSubClassOfAxiom(domain, rangeClass));
                                    } catch (Exception e) {
                                        return false;
                                    }
                                });
                            if (subclassOfRange) return true;
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to stage 4
                }

                // v0.8.3 R7 stage 4: isEntailed fallback (existing)
                try {
                    if (adapter.isActive()) {
                        return adapter.getUnderlyingReasoner().isEntailed(
                            df.getOWLObjectPropertyDomainAxiom(prop, domain));
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-02: ObjectPropertyRange (asserted-first then isEntailed)
            case "ObjectPropertyRange": {
                String propertyIRI = parameters.get("propertyIRI");
                String rangeIRI = parameters.get("rangeIRI");
                if (propertyIRI == null || rangeIRI == null) return false;
                IRI propIri = OntologyIriResolver.resolveOntologyIRI(ontology, propertyIRI, "object_property");
                IRI rngIri = OntologyIriResolver.resolveOntologyIRI(ontology, rangeIRI, "class");
                if (propIri == null || rngIri == null) return false;
                OWLObjectProperty prop = df.getOWLObjectProperty(propIri);
                OWLClass range = df.getOWLClass(rngIri);

                boolean asserted = ontology.getObjectPropertyRangeAxioms(prop).stream()
                    .anyMatch(ax -> ax.getRange().equals(range));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        return adapter.getUnderlyingReasoner().isEntailed(
                            df.getOWLObjectPropertyRangeAxiom(prop, range));
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-02: DataPropertyDomain (asserted-first then isEntailed)
            case "DataPropertyDomain": {
                String propertyIRI = parameters.get("propertyIRI");
                String domainIRI = parameters.get("domainIRI");
                if (propertyIRI == null || domainIRI == null) return false;
                IRI propIri = OntologyIriResolver.resolveOntologyIRI(ontology, propertyIRI, "data_property");
                IRI domIri = OntologyIriResolver.resolveOntologyIRI(ontology, domainIRI, "class");
                if (propIri == null || domIri == null) return false;
                OWLDataProperty prop = df.getOWLDataProperty(propIri);
                OWLClass domain = df.getOWLClass(domIri);

                boolean asserted = ontology.getDataPropertyDomainAxioms(prop).stream()
                    .anyMatch(ax -> ax.getDomain().equals(domain));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        return adapter.getUnderlyingReasoner().isEntailed(
                            df.getOWLDataPropertyDomainAxiom(prop, domain));
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-02: DataPropertyRange (asserted-first then isEntailed)
            case "DataPropertyRange": {
                String propertyIRI = parameters.get("propertyIRI");
                String rangeIRI = parameters.get("rangeIRI");
                if (propertyIRI == null || rangeIRI == null) return false;
                IRI propIri = OntologyIriResolver.resolveOntologyIRI(ontology, propertyIRI, "data_property");
                IRI rngIri = OntologyIriResolver.resolveOntologyIRI(ontology, rangeIRI, "datatype");
                if (propIri == null || rngIri == null) return false;
                OWLDataProperty prop = df.getOWLDataProperty(propIri);
                OWLDatatype range = df.getOWLDatatype(rngIri);

                boolean asserted = ontology.getDataPropertyRangeAxioms(prop).stream()
                    .anyMatch(ax -> ax.getRange().equals(range));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        return adapter.getUnderlyingReasoner().isEntailed(
                            df.getOWLDataPropertyRangeAxiom(prop, range));
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-04: DifferentIndividuals
            case "DifferentIndividuals": {
                String ind1IRI = parameters.get("individual1IRI");
                String ind2IRI = parameters.get("individual2IRI");
                if (ind1IRI == null || ind2IRI == null) return false;
                IRI i1 = OntologyIriResolver.resolveOntologyIRI(ontology, ind1IRI, "individual");
                IRI i2 = OntologyIriResolver.resolveOntologyIRI(ontology, ind2IRI, "individual");
                if (i1 == null || i2 == null) return false;
                OWLNamedIndividual ind1 = df.getOWLNamedIndividual(i1);
                OWLNamedIndividual ind2 = df.getOWLNamedIndividual(i2);

                // Asserted: DifferentIndividuals axiom containing ind1
                boolean asserted = ontology.getDifferentIndividualAxioms(ind1).stream()
                    .flatMap(ax -> ax.getIndividuals().stream())
                    .anyMatch(other -> other.equals(ind2));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        OWLAxiom axiom = df.getOWLDifferentIndividualsAxiom(ind1, ind2);
                        return adapter.getUnderlyingReasoner().isEntailed(axiom);
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-04 counter-evidence: SameIndividual
            case "SameIndividual": {
                String ind1IRI = parameters.get("individual1IRI");
                String ind2IRI = parameters.get("individual2IRI");
                if (ind1IRI == null || ind2IRI == null) return false;
                IRI i1 = OntologyIriResolver.resolveOntologyIRI(ontology, ind1IRI, "individual");
                IRI i2 = OntologyIriResolver.resolveOntologyIRI(ontology, ind2IRI, "individual");
                if (i1 == null || i2 == null) return false;
                OWLNamedIndividual ind1 = df.getOWLNamedIndividual(i1);
                OWLNamedIndividual ind2 = df.getOWLNamedIndividual(i2);

                // Asserted: SameIndividual axiom containing ind1
                boolean asserted = ontology.getSameIndividualAxioms(ind1).stream()
                    .flatMap(ax -> ax.getIndividuals().stream())
                    .anyMatch(other -> other.equals(ind2));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        OWLAxiom axiom = df.getOWLSameIndividualAxiom(ind1, ind2);
                        return adapter.getUnderlyingReasoner().isEntailed(axiom);
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-05: SubObjectPropertyOf
            case "SubObjectPropertyOf": {
                String subPropIRI = parameters.get("subPropertyIRI");
                String superPropIRI = parameters.get("superPropertyIRI");
                if (subPropIRI == null || superPropIRI == null) return false;
                IRI subIri = OntologyIriResolver.resolveOntologyIRI(ontology, subPropIRI, "object_property");
                IRI supIri = OntologyIriResolver.resolveOntologyIRI(ontology, superPropIRI, "object_property");
                if (subIri == null || supIri == null) return false;
                OWLObjectProperty subProp = df.getOWLObjectProperty(subIri);
                OWLObjectProperty superProp = df.getOWLObjectProperty(supIri);

                // Asserted: SubObjectPropertyOf axiom whose sub is subProp and super equals superProp
                boolean asserted = ontology.getObjectSubPropertyAxiomsForSubProperty(subProp).stream()
                    .anyMatch(ax -> ax.getSuperProperty().equals(superProp));
                if (asserted) return true;

                try {
                    if (adapter.isActive()) {
                        OWLAxiom axiom = df.getOWLSubObjectPropertyOfAxiom(subProp, superProp);
                        return adapter.getUnderlyingReasoner().isEntailed(axiom);
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.1 ISSUE-03 / v0.8.3 R4: EquivalentClasses
            //   stage 1: extract named classes from complex expressions via getSignature()
            //   stage 2: reasoner.getEquivalentClasses(cls1) query
            //   stage 3: isEntailed fallback
            case "EquivalentClasses": {
                String c1 = parameters.get("class1");
                String c2 = parameters.get("class2");
                if (c1 == null || c2 == null) return false;
                IRI c1Iri = OntologyIriResolver.resolveOntologyIRI(ontology, c1, "class");
                IRI c2Iri = OntologyIriResolver.resolveOntologyIRI(ontology, c2, "class");
                if (c1Iri == null || c2Iri == null) return false;
                OWLClass cls1 = df.getOWLClass(c1Iri);
                OWLClass cls2 = df.getOWLClass(c2Iri);

                // v0.8.3 R4 stage 1: semantic simplification — extract all named
                // classes from complex class expressions (ObjectIntersectionOf,
                // ObjectSomeValuesFrom, etc.) via getSignature(). getNamedClasses()
                // only returns direct operands that are themselves named classes,
                // missing classes nested inside complex expressions.
                boolean asserted = ontology.getEquivalentClassesAxioms(cls1).stream()
                    .flatMap(ax -> ax.getClassExpressionsAsList().stream())
                    .flatMap(ce -> ce.getSignature().stream())
                    .filter(OWLClass.class::isInstance)
                    .map(OWLClass.class::cast)
                    .anyMatch(other -> other.equals(cls2));
                if (asserted) return true;

                // v0.8.3 R4 stage 2: reasoner.getEquivalentClasses() query.
                // For simple equivalent-class definitions, the reasoner can directly
                // return the equivalent class set. (For complex expressions like
                // EquivalentClasses(A, B AND C), A is NOT equivalent to B in strict
                // OWL semantics — stage 1 handles that case via semantic simplification.)
                try {
                    if (adapter.isActive()) {
                        Node<OWLClass> equivNode =
                            adapter.getUnderlyingReasoner().getEquivalentClasses(cls1);
                        if (equivNode.contains(cls2)) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to stage 3
                }

                // v0.8.3 R4 stage 3: isEntailed fallback (existing logic)
                try {
                    if (adapter.isActive()) {
                        OWLAxiom axiom = df.getOWLEquivalentClassesAxiom(cls1, cls2);
                        return adapter.getUnderlyingReasoner().isEntailed(axiom);
                    }
                } catch (Exception ignored) {
                    // fall through to false
                }
                return false;
            }

            // v0.8.4 Decision 5: DisjointClasses with EntitySignatureCache index
            case "DisjointClasses": {
                String class1IRI = parameters.get("class1");
                String class2IRI = parameters.get("class2");
                if (class1IRI == null || class2IRI == null) return false;

                // v0.8.4: check EntitySignatureCache index first (O(1) lookup)
                if (entitySignatureCacheManager != null) {
                    try {
                        EntitySignatureCache esc = entitySignatureCacheManager.getOrCreate(ontologyId, ontology);
                        if (esc.getDisjointClasses(class1IRI).contains(class2IRI)) {
                            return true;
                        }
                        if (esc.getDisjointClasses(class2IRI).contains(class1IRI)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Check asserted disjointness
                IRI c1Iri = OntologyIriResolver.resolveOntologyIRI(ontology, class1IRI, "class");
                IRI c2Iri = OntologyIriResolver.resolveOntologyIRI(ontology, class2IRI, "class");
                if (c1Iri == null || c2Iri == null) return false;
                OWLClass cls1 = df.getOWLClass(c1Iri);
                OWLClass cls2 = df.getOWLClass(c2Iri);

                boolean asserted = ontology.getAxioms(AxiomType.DISJOINT_CLASSES, Imports.INCLUDED)
                    .stream().anyMatch(ax -> {
                        Set<OWLClass> classes = ax.getClassExpressionsAsList()
                            .stream().filter(OWLClassExpression::isNamed)
                            .map(OWLClassExpression::asOWLClass).collect(Collectors.toSet());
                        return classes.contains(cls1) && classes.contains(cls2);
                    });
                if (asserted) return true;

                // Check via reasoner
                try {
                    if (adapter.isActive()) {
                        return adapter.getUnderlyingReasoner().isEntailed(
                            df.getOWLDisjointClassesAxiom(cls1, cls2));
                    }
                } catch (Exception ignored) {
                }
                return false;
            }

            default:
                // For other axiom types, check stored inferred data
                return false;
        }
    }

    private boolean checkStoredEntailment(OntologyId ontologyId, OWLOntology ontology, String type, String subject, String object) {
        // v0.8.4 Decision 6: use in-memory inferred hierarchy cache for O(1) lookup.
        // On first call for a given ontologyId, load the inferred-class-hierarchy.jsonl
        // into the cache. Subsequent calls query the cache directly.
        String key = ontologyId.id();
        Map<String, Set<String>> hierarchy = inferredHierarchyCache.get(key);
        if (hierarchy == null) {
            hierarchy = loadInferredHierarchy(ontologyId);
            if (hierarchy == null) {
                // File not found or load error → not entailed
                return false;
            }
            Map<String, Set<String>> existing = inferredHierarchyCache.putIfAbsent(key, hierarchy);
            if (existing != null) {
                hierarchy = existing;
            }
        }
        Set<String> objects = hierarchy.get(subject);
        return objects != null && objects.contains(object);
    }

    /**
     * v0.8.4 Decision 6: Load inferred-class-hierarchy.jsonl into an in-memory
     * map (subjectIRI → Set<objectIRI>). Returns null if the file doesn't exist.
     */
    private Map<String, Set<String>> loadInferredHierarchy(OntologyId ontologyId) {
        Path inferredFile = getInferredDir(ontologyId).resolve("inferred-class-hierarchy.jsonl");
        if (!Files.exists(inferredFile)) {
            // v0.8.4 Decision 6 task 7.4: file missing → return null (not-entailed)
            return null;
        }
        Map<String, Set<String>> map = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(inferredFile)) {
                String sub = extractJsonValue(line, "subjectIRI");
                String obj = extractJsonValue(line, "objectIRI");
                if (sub != null && obj != null) {
                    map.computeIfAbsent(sub, k -> new java.util.HashSet<>()).add(obj);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return map;
    }

    private String determineSource(OntologyId ontologyId, OWLOntology ontology, String axiomType, Map<String, String> parameters) {
        // Determine whether the axiom is asserted in the ontology.
        // v0.8.1: returns "asserted" (not v0.8.0's "explicit") to align with the
        // claim-verification spec — the `ClaimVerificationService.buildEntailmentEvidence`
        // method checks `entailment.source().contains("inferred")` to pick between
        // `EvidenceKind.INFERRED_AXIOM` and `EvidenceKind.EXPLICIT_AXIOM`; the
        // "inferred" substring check still works, but the asserted branch is now
        // spelled "asserted" so test assertions and evidence `source` fields
        // match the public spec.
        try {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            switch (axiomType) {
                case "SubClassOf": {
                    String subclassIRI = parameters.get("subclass");
                    String superclassIRI = parameters.get("superclass");
                    if (subclassIRI == null || superclassIRI == null) return "unknown";

                    // v0.8.4 Decision 5: use EntitySignatureCache index for O(1) lookup
                    if (entitySignatureCacheManager != null) {
                        try {
                            EntitySignatureCache esc = entitySignatureCacheManager.getOrCreate(ontologyId, ontology);
                            if (esc.getSuperClasses(subclassIRI).contains(superclassIRI)) {
                                return "asserted";
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    OWLClass subClass = df.getOWLClass(IRI.create(subclassIRI));
                    OWLClass superClass = df.getOWLClass(IRI.create(superclassIRI));
                    boolean asserted = ontology.getAxioms(AxiomType.SUBCLASS_OF, Imports.INCLUDED).stream()
                        .anyMatch(ax -> ax.getSubClass().equals(subClass) && ax.getSuperClass().equals(superClass));
                    return asserted ? "asserted" : "inferred";
                }
                case "ObjectPropertyDomain": {
                    String propertyIRI = parameters.get("propertyIRI");
                    String domainIRI = parameters.get("domainIRI");
                    if (propertyIRI == null || domainIRI == null) return "unknown";
                    OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propertyIRI));
                    OWLClass domain = df.getOWLClass(IRI.create(domainIRI));
                    boolean asserted = ontology.getObjectPropertyDomainAxioms(prop).stream()
                        .anyMatch(ax -> ax.getDomain().equals(domain));
                    return asserted ? "asserted" : "inferred";
                }
                case "ObjectPropertyRange": {
                    String propertyIRI = parameters.get("propertyIRI");
                    String rangeIRI = parameters.get("rangeIRI");
                    if (propertyIRI == null || rangeIRI == null) return "unknown";
                    OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propertyIRI));
                    OWLClass range = df.getOWLClass(IRI.create(rangeIRI));
                    boolean asserted = ontology.getObjectPropertyRangeAxioms(prop).stream()
                        .anyMatch(ax -> ax.getRange().equals(range));
                    return asserted ? "asserted" : "inferred";
                }
                case "DataPropertyDomain": {
                    String propertyIRI = parameters.get("propertyIRI");
                    String domainIRI = parameters.get("domainIRI");
                    if (propertyIRI == null || domainIRI == null) return "unknown";
                    OWLDataProperty prop = df.getOWLDataProperty(IRI.create(propertyIRI));
                    OWLClass domain = df.getOWLClass(IRI.create(domainIRI));
                    boolean asserted = ontology.getDataPropertyDomainAxioms(prop).stream()
                        .anyMatch(ax -> ax.getDomain().equals(domain));
                    return asserted ? "asserted" : "inferred";
                }
                case "DataPropertyRange": {
                    String propertyIRI = parameters.get("propertyIRI");
                    String rangeIRI = parameters.get("rangeIRI");
                    if (propertyIRI == null || rangeIRI == null) return "unknown";
                    OWLDataProperty prop = df.getOWLDataProperty(IRI.create(propertyIRI));
                    OWLDatatype range = df.getOWLDatatype(IRI.create(rangeIRI));
                    boolean asserted = ontology.getDataPropertyRangeAxioms(prop).stream()
                        .anyMatch(ax -> ax.getRange().equals(range));
                    return asserted ? "asserted" : "inferred";
                }
                case "DifferentIndividuals": {
                    String ind1IRI = parameters.get("individual1IRI");
                    String ind2IRI = parameters.get("individual2IRI");
                    if (ind1IRI == null || ind2IRI == null) return "unknown";
                    OWLNamedIndividual ind1 = df.getOWLNamedIndividual(IRI.create(ind1IRI));
                    OWLNamedIndividual ind2 = df.getOWLNamedIndividual(IRI.create(ind2IRI));
                    boolean asserted = ontology.getDifferentIndividualAxioms(ind1).stream()
                        .flatMap(ax -> ax.getIndividuals().stream())
                        .anyMatch(other -> other.equals(ind2));
                    return asserted ? "asserted" : "inferred";
                }
                case "SubObjectPropertyOf": {
                    String subPropIRI = parameters.get("subPropertyIRI");
                    String superPropIRI = parameters.get("superPropertyIRI");
                    if (subPropIRI == null || superPropIRI == null) return "unknown";
                    OWLObjectProperty subProp = df.getOWLObjectProperty(IRI.create(subPropIRI));
                    OWLObjectProperty superProp = df.getOWLObjectProperty(IRI.create(superPropIRI));
                    boolean asserted = ontology.getObjectSubPropertyAxiomsForSubProperty(subProp).stream()
                        .anyMatch(ax -> ax.getSuperProperty().equals(superProp));
                    return asserted ? "asserted" : "inferred";
                }
                case "EquivalentClasses": {
                    String c1 = parameters.get("class1");
                    String c2 = parameters.get("class2");
                    if (c1 == null || c2 == null) return "unknown";
                    OWLClass cls1 = df.getOWLClass(IRI.create(c1));
                    OWLClass cls2 = df.getOWLClass(IRI.create(c2));
                    boolean asserted = ontology.getEquivalentClassesAxioms(cls1).stream()
                        .flatMap(ax -> ax.getNamedClasses().stream())
                        .anyMatch(other -> other.equals(cls2));
                    return asserted ? "asserted" : "inferred";
                }
                default:
                    return "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Deserialize a reasoning-report.json string into a ReasoningReport record.
     * Mirrors the structure written by serializeReportToJson().
     */
    private ReasoningReport deserializeReportFromJson(String json) {
        try {
            String ontologyId = extractJsonValue(json, "ontologyId");
            String reasonerName = extractJsonValue(json, "reasonerName");
            String owlProfile = extractJsonValue(json, "owlProfile");
            boolean classificationStatus = Boolean.parseBoolean(extractJsonValue(json, "classificationStatus"));
            String realizationRaw = extractJsonValue(json, "realizationStatus");
            Boolean realizationStatus = (realizationRaw == null || realizationRaw.equals("null")) ? null : Boolean.parseBoolean(realizationRaw);
            boolean consistencyStatus = Boolean.parseBoolean(extractJsonValue(json, "consistencyStatus"));

            // Parse timing breakdown
            long initTime = Long.parseLong(extractNestedJsonValue(json, "timingBreakdown", "initializationTimeMs"));
            long classTime = Long.parseLong(extractNestedJsonValue(json, "timingBreakdown", "classificationTimeMs"));
            long realTime = Long.parseLong(extractNestedJsonValue(json, "timingBreakdown", "realizationTimeMs"));
            long totalTime = Long.parseLong(extractNestedJsonValue(json, "timingBreakdown", "totalTimeMs"));
            ReasoningReport.TimingBreakdown timing = new ReasoningReport.TimingBreakdown(initTime, classTime, realTime, totalTime);

            int warningCount = Integer.parseInt(extractJsonValue(json, "warningCount") != null ? extractJsonValue(json, "warningCount") : "0");

            // Parse inferred axiom counts (nested object)
            Map<String, Integer> axiomCounts = new LinkedHashMap<>();
            String countsBlock = extractJsonBlock(json, "inferredAxiomCountsByType");
            if (countsBlock != null) {
                parseKeyValueCounts(countsBlock, axiomCounts);
            }

            // Parse error details (may be null)
            String errorBlock = extractJsonValue(json, "errorDetails");
            ReasoningReport.ErrorDetails errorDetails = null;
            if (errorBlock != null && !errorBlock.equals("null")) {
                String innerBlock = extractJsonBlock(json, "errorDetails");
                if (innerBlock != null) {
                    String errorCode = extractJsonValue(innerBlock, "errorCode");
                    String message = extractJsonValue(innerBlock, "message");
                    String stackTrace = extractJsonValue(innerBlock, "stackTrace");
                    errorDetails = new ReasoningReport.ErrorDetails(errorCode, message, stackTrace);
                }
            }

            return new ReasoningReport(ontologyId, reasonerName, owlProfile, classificationStatus,
                realizationStatus, consistencyStatus, timing, warningCount, axiomCounts, errorDetails);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractNestedJsonValue(String json, String parentKey, String childKey) {
        String block = extractJsonBlock(json, parentKey);
        if (block == null) return null;
        return extractJsonValue(block, childKey);
    }

    private String extractJsonBlock(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '{') {
            int depth = 1;
            int end = start + 1;
            while (end < json.length() && depth > 0) {
                if (json.charAt(end) == '{') depth++;
                else if (json.charAt(end) == '}') depth--;
                end++;
            }
            return json.substring(start, end);
        } else if (json.charAt(start) == 'n') {
            return "null";
        }
        return null;
    }

    private void parseKeyValueCounts(String block, Map<String, Integer> counts) {
        // Parse simple "key": value pairs from a JSON object block
        int pos = 0;
        while (pos < block.length()) {
            int keyStart = block.indexOf('"', pos);
            if (keyStart == -1) break;
            int keyEnd = block.indexOf('"', keyStart + 1);
            if (keyEnd == -1) break;
            String key = block.substring(keyStart + 1, keyEnd);

            int colonPos = block.indexOf(':', keyEnd);
            if (colonPos == -1) break;
            int valStart = colonPos + 1;
            while (valStart < block.length() && block.charAt(valStart) == ' ') valStart++;

            int valEnd = valStart;
            while (valEnd < block.length() && block.charAt(valEnd) != ',' && block.charAt(valEnd) != '}') valEnd++;
            String valueStr = block.substring(valStart, valEnd).trim();
            try {
                counts.put(key, Integer.parseInt(valueStr));
            } catch (NumberFormatException e) {
                // skip malformed entries
            }
            pos = valEnd + 1;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // v0.8.5: Exact consistency check pipeline (tasks 6.1-6.8)
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public ServiceResult<org.owl4agents.core.model.ConsistencyAfterAdditionResult> checkConsistencyAfterAdding(
            OWLOntology sourceOntology,
            OntologyId ontologyId,
            String claimId,
            OWLAxiom claimAxiom,
            Optional<String> reasonerName,
            java.time.Duration timeout) {

        long totalStart = System.nanoTime();
        if (sourceOntology == null) {
            return ServiceResult.error(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED,
                "Source ontology must not be null.");
        }
        if (claimAxiom == null) {
            return ServiceResult.error(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED,
                "Claim axiom must not be null.");
        }
        java.time.Duration effectiveTimeout = (timeout != null) ? timeout : java.time.Duration.ofSeconds(60);
        // v0.8.6: Pass Duration directly to wrapper to support sub-second timeouts
        // (e.g., Duration.ZERO for immediate timeout in v0.8.5 acceptance test).

        // Resolve reasoner name (auto-select if not specified)
        String detectedProfile = detectProfile(ontologyId, sourceOntology);
        String effectiveReasoner = resolveReasonerName(reasonerName, detectedProfile, false, sourceOntology);

        // v0.8.8 D1: Stage 4 must use a full DL profile reasoner for disjointness
        // detection. ELK (OWL 2 EL) cannot detect disjointness-based unsatisfiability.
        // Override ELK to HermiT (small ontologies) or Openllet (large ontologies).
        // This override is Stage 4 only — Stage 3 (entailment) still uses the
        // claim-specified reasoner (ELK for fast subclass reasoning).
        boolean d1Overridden = false;
        if ("ELK".equalsIgnoreCase(effectiveReasoner)) {
            int classCount = sourceOntology.getClassesInSignature().size();
            String dlReasoner = (classCount > AutoReasonerSelector.LARGE_ONTOLOGY_CLASS_THRESHOLD)
                ? "Openllet"
                : "HermiT";
            java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName()).info(
                "v0.8.8 D1: Stage 4 consistency check overriding reasoner from ELK to "
                + dlReasoner + " for disjointness detection (classCount=" + classCount + ")");
            effectiveReasoner = dlReasoner;
            d1Overridden = true;
        }

        // v0.8.5 P1: get or create cached exact-check session (base copy + reasoner).
        // On cache hit, temporaryCopyMs and reasonerInitMs are 0 (reused).
        // On cache miss, creates base ontology copy + initializes reasoner (expensive).
        long[] sessionTiming = new long[2]; // [temporaryCopyMs, reasonerInitMs]
        CachedExactCheckSession cached = getOrCreateCachedSession(
            sourceOntology, ontologyId.id(), effectiveReasoner, effectiveTimeout, sessionTiming);
        long temporaryCopyMs = sessionTiming[0];
        long reasonerInitMs = sessionTiming[1];

        if (cached == null) {
            long totalMs = msSince(totalStart);
            String phase = (temporaryCopyMs > 0 && reasonerInitMs == 0)
                ? "Temporary ontology creation failed"
                : "Transient reasoner init failed";
            return ServiceResult.success(
                org.owl4agents.core.model.ConsistencyAfterAdditionResult.create(
                    ontologyId, claimId, effectiveReasoner,
                    org.owl4agents.core.model.ConsistencyAfterAdditionStatus.ERROR,
                    claimAxiom.toString(), totalMs, false, (reasonerInitMs > 0 || temporaryCopyMs == 0),
                    Optional.of(phase),
                    java.util.List.of(),
                    new org.owl4agents.core.model.PerStageTiming(
                        null, null, null, temporaryCopyMs, reasonerInitMs, null, null, totalMs)),
                ResultMetadata.empty());
        }

        // Acquire opLock to serialize add/check/remove on the cached ontology.
        // This ensures only one claim is processed at a time per ontology.
        cached.opLock.lock();
        try {
            OWLOntology tempOntology = cached.baseHandle.ontology();
            org.semanticweb.owlapi.model.OWLOntologyManager tempManager =
                tempOntology.getOWLOntologyManager();

            // v0.8.8 D2: Check satisfiability of named classes in the claim axiom
            // on O (the base ontology, BEFORE the claim axiom is added). This
            // establishes the baseline for detecting classes that BECOME
            // unsatisfiable after adding the claim.
            List<OWLClass> claimNamedClasses = extractNamedClassesFromAxiom(claimAxiom);
            Map<String, Boolean> satBefore = new HashMap<>();
            for (OWLClass cls : claimNamedClasses) {
                String iri = cls.getIRI().toString();
                try {
                    satBefore.put(iri, cached.session.isSatisfiable(cls));
                } catch (Exception e) {
                    // Before-check failure is best-effort: treat as satisfiable
                    // so a transient error does not suppress contradiction detection.
                    java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName())
                        .warning("v0.8.8 D2: satBefore check failed for " + iri
                            + ": " + e.getMessage() + " (treating as satisfiable)");
                    satBefore.put(iri, true);
                }
            }

            // Add claim axiom to the base ontology (incremental, O(ms))
            tempManager.applyChange(new org.semanticweb.owlapi.model.AddAxiom(tempOntology, claimAxiom));

            // v0.8.8 D4 (pizza-op-008): Track simplicity violations so the cached
            // session can be invalidated after the check (HermiT's internal state
            // may be corrupted after the exception). Declared before the inner try
            // so it is visible in the finally block.
            final java.util.concurrent.atomic.AtomicBoolean simplicityViolation =
                new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                long consistencyCheckStart = System.nanoTime();

                // v0.8.6 D1: Route through ReasonerCallWrapper with ELK fallback.
                // The wrapper handles timeout, ELK fallback, executor recovery.
                // The primarySupplier uses the cached session (fast path).
                // The elkSupplier creates a fresh ELK session (slow path, fallback only).
                final CachedExactCheckSession cachedFinal = cached;
                java.util.function.Supplier<ServiceResult<Boolean>> primarySupplier = () -> {
                    try {
                        org.owl4agents.core.model.ConsistencyResult cr = cachedFinal.session.checkConsistency();
                        return ServiceResult.success(cr.consistent(), ResultMetadata.empty());
                    } catch (IllegalArgumentException e) {
                        // v0.8.8 D4 (pizza-op-008): HermiT's ObjectPropertyInclusionManager
                        // throws IllegalArgumentException when the claim axiom creates an
                        // OWL 2 DL simplicity violation (e.g., asserting a subproperty of a
                        // transitive property that is also functional/inverse-functional
                        // makes the functional property non-simple). Adding an unsatisfiable
                        // axiom means the ontology cannot be in a consistent state — treat
                        // this as INCONSISTENT so the verdict maps to CONTRADICTED.
                        if (e.getMessage() != null && e.getMessage().contains("Non-simple property")) {
                            simplicityViolation.set(true);
                            return ServiceResult.success(false, ResultMetadata.empty());
                        }
                        return ServiceResult.error(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED, e.getMessage());
                    } catch (Exception e) {
                        return ServiceResult.error(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED, e.getMessage());
                    }
                };
                java.util.function.Supplier<ServiceResult<Boolean>> elkSupplier = () -> {
                    // Slow path: create fresh temp ontology + ELK session for fallback.
                    // This is only invoked when the primary reasoner times out.
                    try {
                        ServiceResult<TemporaryOntologyHandle> baseHandle =
                            temporaryOntologyFactory.createBase(sourceOntology,
                                new TemporaryOntologyOptions(true, "elk-fallback"));
                        if (!baseHandle.isSuccess()) {
                            return ServiceResult.error(ErrorCode.TEMPORARY_ONTOLOGY_CREATION_FAILED,
                                "ELK fallback: failed to create temporary ontology");
                        }
                        TemporaryOntologyHandle handle =
                            ((ServiceResult.Success<TemporaryOntologyHandle>) baseHandle).data();
                        OWLOntology elkTempOntology = handle.ontology();
                        elkTempOntology.getOWLOntologyManager().applyChange(
                            new org.semanticweb.owlapi.model.AddAxiom(elkTempOntology, claimAxiom));
                        try {
                            ServiceResult<TransientReasonerSession> sessionResult =
                                TransientReasonerSession.create(elkTempOntology, "ELK", effectiveTimeout);
                            if (!sessionResult.isSuccess()) {
                                return ServiceResult.error(ErrorCode.TRANSIENT_REASONER_INIT_FAILED,
                                    "ELK fallback: failed to create transient session");
                            }
                            TransientReasonerSession elkSession =
                                ((ServiceResult.Success<TransientReasonerSession>) sessionResult).data();
                            org.owl4agents.core.model.ConsistencyResult cr = elkSession.checkConsistency();
                            return ServiceResult.success(cr.consistent(), ResultMetadata.empty());
                        } finally {
                            handle.close();
                        }
                    } catch (Exception e) {
                        return ServiceResult.error(ErrorCode.CLAIM_CONSISTENCY_CHECK_FAILED,
                            "ELK fallback failed: " + e.getMessage());
                    }
                };

                ServiceResult<Boolean> wrapperResult;
                if (d1Overridden) {
                    // v0.8.8 D1: No ELK fallback — ELK cannot detect disjointness,
                    // falling back would silently undo the D1 fix. If the DL reasoner
                    // times out, return REASONER_TIMEOUT (not UNKNOWN or CONTRADICTED).
                    wrapperResult = reasonerCallWrapper.call(
                        effectiveReasoner, ontologyId, primarySupplier, effectiveTimeout);
                } else {
                    wrapperResult = reasonerCallWrapper.callWithElkFallback(
                        effectiveReasoner, ontologyId, primarySupplier, elkSupplier, effectiveTimeout);
                }

                long consistencyCheckMs = msSince(consistencyCheckStart);
                long totalMs = msSince(totalStart);

                // Extract metadata from wrapper result
                org.owl4agents.core.model.ReasonerCallMetadata metadata = null;
                if (wrapperResult.isSuccess()) {
                    metadata = ((ServiceResult.Success<Boolean>) wrapperResult).reasonerMetadata();
                } else {
                    metadata = ((ServiceResult.Error<Boolean>) wrapperResult).reasonerMetadata();
                }

                // Map wrapper result to ConsistencyAfterAdditionResult
                if (wrapperResult.isSuccess()) {
                    ServiceResult.Success<Boolean> success = (ServiceResult.Success<Boolean>) wrapperResult;
                    boolean consistent = success.data();
                    org.owl4agents.core.model.ConsistencyAfterAdditionStatus status;
                    Optional<String> diagnostic = Optional.empty();
                    java.util.List<String> explanationAxioms = java.util.List.of();

                    if (consistent) {
                        status = org.owl4agents.core.model.ConsistencyAfterAdditionStatus.CONSISTENT;

                        // v0.8.8 D2: Check satisfiability of named classes on O∪{α}
                        // (the claim axiom is already applied). Compare with
                        // satBefore to detect classes that BECAME unsatisfiable.
                        Map<String, Boolean> satAfter = new HashMap<>();
                        for (OWLClass cls : claimNamedClasses) {
                            String iri = cls.getIRI().toString();
                            try {
                                satAfter.put(iri, cached.session.isSatisfiable(cls));
                            } catch (Exception e) {
                                // After-check failure is a hard error: we cannot
                                // safely determine the verdict. Return ERROR and
                                // invalidate the cached session.
                                // v0.8.8 P2: precise invalidation — only the
                                // (ontologyId, effectiveReasoner) session, not all.
                                invalidateExactCheckSessionCache(ontologyId.id(), effectiveReasoner);
                                return ServiceResult.success(
                                    org.owl4agents.core.model.ConsistencyAfterAdditionResult.create(
                                        ontologyId, claimId, effectiveReasoner,
                                        org.owl4agents.core.model.ConsistencyAfterAdditionStatus.ERROR,
                                        claimAxiom.toString(), totalMs, true, true,
                                        Optional.of("Satisfiability check failed (after): "
                                            + e.getMessage()),
                                        java.util.List.of(),
                                        new org.owl4agents.core.model.PerStageTiming(
                                            null, null, null, temporaryCopyMs, reasonerInitMs,
                                            consistencyCheckMs, null, totalMs),
                                        metadata),
                                    ResultMetadata.empty());
                            }
                        }

                        // Compare before/after: a class that was satisfiable in O
                        // but unsatisfiable in O∪{α} became unsatisfiable.
                        List<String> becameUnsatisfiable = new ArrayList<>();
                        for (Map.Entry<String, Boolean> entry : satBefore.entrySet()) {
                            String iri = entry.getKey();
                            boolean before = entry.getValue();
                            boolean after = satAfter.getOrDefault(iri, true);
                            if (before && !after) {
                                becameUnsatisfiable.add(iri);
                            }
                        }

                        return ServiceResult.success(
                            org.owl4agents.core.model.ConsistencyAfterAdditionResult.create(
                                ontologyId, claimId, effectiveReasoner, status,
                                claimAxiom.toString(), totalMs, true, true,
                                diagnostic, explanationAxioms,
                                new org.owl4agents.core.model.PerStageTiming(
                                    null, null, null, temporaryCopyMs, reasonerInitMs,
                                    consistencyCheckMs, null, totalMs),
                                metadata, becameUnsatisfiable),
                            ResultMetadata.empty());
                    } else {
                        status = org.owl4agents.core.model.ConsistencyAfterAdditionStatus.INCONSISTENT;
                        // Attempt explanation if supported (Openllet)
                        if (cached.session.supportsExplanation()) {
                            try {
                                org.owl4agents.core.model.InconsistencyExplanation expl =
                                    cached.session.explainInconsistency();
                                explanationAxioms = flattenExplanation(expl);
                            } catch (Exception e) {
                                // explanation is best-effort
                            }
                        }

                        return ServiceResult.success(
                            org.owl4agents.core.model.ConsistencyAfterAdditionResult.create(
                                ontologyId, claimId, effectiveReasoner, status,
                                claimAxiom.toString(), totalMs, true, true,
                                diagnostic, explanationAxioms,
                                new org.owl4agents.core.model.PerStageTiming(
                                    null, null, null, temporaryCopyMs, reasonerInitMs,
                                    consistencyCheckMs, null, totalMs),
                                metadata),
                            ResultMetadata.empty());
                    }
                } else {
                    ServiceResult.Error<Boolean> error = (ServiceResult.Error<Boolean>) wrapperResult;
                    org.owl4agents.core.model.ConsistencyAfterAdditionStatus status;
                    Optional<String> diagnostic;

                    if (error.error().code() == ErrorCode.REASONER_TIMEOUT) {
                        status = org.owl4agents.core.model.ConsistencyAfterAdditionStatus.TIMEOUT;
                        diagnostic = Optional.of("Reasoner exceeded timeout of "
                            + effectiveTimeout.toMillis() + "ms (wrapper-enforced)");
                        // On timeout, invalidate the cached session (reasoner may be corrupted)
                        // v0.8.8 P2: precise invalidation — only the timed-out reasoner's session.
                        invalidateExactCheckSessionCache(ontologyId.id(), effectiveReasoner);
                    } else if (error.error().code() == ErrorCode.REASONER_BUSY) {
                        status = org.owl4agents.core.model.ConsistencyAfterAdditionStatus.ERROR;
                        diagnostic = Optional.of("Reasoner executor busy: " + error.error().message());
                    } else {
                        status = org.owl4agents.core.model.ConsistencyAfterAdditionStatus.ERROR;
                        diagnostic = Optional.of("Reasoner error: " + error.error().message());
                        // On internal error, invalidate the cached session
                        // v0.8.8 P2: precise invalidation — only the failed reasoner's session.
                        invalidateExactCheckSessionCache(ontologyId.id(), effectiveReasoner);
                    }

                    return ServiceResult.success(
                        org.owl4agents.core.model.ConsistencyAfterAdditionResult.create(
                            ontologyId, claimId, effectiveReasoner, status,
                            claimAxiom.toString(), totalMs, false, true,
                            diagnostic, java.util.List.of(),
                            new org.owl4agents.core.model.PerStageTiming(
                                null, null, null, temporaryCopyMs, reasonerInitMs,
                                consistencyCheckMs, null, totalMs),
                            metadata),
                        ResultMetadata.empty());
                }
            } finally {
                // Always remove the claim axiom to restore base ontology state.
                // Best-effort: if this fails, the cache entry is invalidated to
                // avoid corrupting subsequent checks.
                try {
                    tempManager.applyChange(
                        new org.semanticweb.owlapi.model.RemoveAxiom(tempOntology, claimAxiom));
                } catch (Exception e) {
                    // Axiom removal failed — invalidate cache to prevent stale state
                    // v0.8.8 P2: precise invalidation — only this reasoner's session.
                    invalidateExactCheckSessionCache(ontologyId.id(), effectiveReasoner);
                }
                // v0.8.8 D4 (pizza-op-008): If HermiT threw a simplicity violation
                // during checkConsistency, its internal state may be corrupted.
                // Invalidate the cached session so the next claim gets a fresh reasoner.
                if (simplicityViolation.get()) {
                    // v0.8.8 P2: precise invalidation — only this reasoner's session.
                    invalidateExactCheckSessionCache(ontologyId.id(), effectiveReasoner);
                }
            }
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } finally {
            cached.opLock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // v0.8.8 D2: Satisfiability check helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Extract named classes (OWLClass) that appear in a claim axiom.
     * Only SubClassOf, EquivalentClasses, and DisjointClasses axioms are
     * inspected. Complex class expressions are skipped — only named classes
     * (excluding owl:Thing and owl:Nothing) are returned.
     */
    private static List<OWLClass> extractNamedClassesFromAxiom(OWLAxiom axiom) {
        List<OWLClass> classes = new ArrayList<>();
        if (axiom instanceof OWLSubClassOfAxiom sub) {
            addIfNamed(classes, sub.getSubClass());
            addIfNamed(classes, sub.getSuperClass());
        } else if (axiom instanceof OWLEquivalentClassesAxiom eq) {
            for (OWLClassExpression ce : eq.classExpressions().toList()) {
                addIfNamed(classes, ce);
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom dis) {
            for (OWLClassExpression ce : dis.classExpressions().toList()) {
                addIfNamed(classes, ce);
            }
        }
        return classes.stream().distinct().toList();
    }

    private static void addIfNamed(List<OWLClass> classes, OWLClassExpression ce) {
        if (ce.isNamed() && !ce.isOWLThing() && !ce.isOWLNothing()) {
            classes.add(ce.asOWLClass());
        }
    }

    @Override
    public ServiceResult<Boolean> checkSourceOntologyConsistency(
            OntologyId ontologyId, Optional<String> reasonerName) {
        try {
            OWLOntology ontology = loadOntology(ontologyId);
            String detectedProfile = detectProfile(ontologyId, ontology);
            String effectiveReasoner = resolveReasonerName(reasonerName, detectedProfile, false, ontology);

            // D5: check cache first
            String fingerprint = computeFingerprint(ontologyId, ontology);
            String importsState = computeImportsState(ontology);
            String cacheKey = buildSourceConsistencyCacheKey(ontologyId.id(), fingerprint, effectiveReasoner, importsState);

            Boolean cached = getSourceConsistencyCacheEntry(cacheKey);
            if (cached != null) {
                sourceConsistencyCacheHits.incrementAndGet();
                return ServiceResult.success(cached, ResultMetadata.empty());
            }
            sourceConsistencyCacheMisses.incrementAndGet();

            // v0.8.6 D1: Route through ReasonerCallWrapper with ELK fallback.
            // The wrapper enforces timeout (reasonerTimeoutSec), handles ELK fallback
            // when primary times out, and recovers the executor on stuck threads.
            // Reference count release: caller (this method) releases in finally block
            // based on metadata (primary only, or primary + ELK on fallback).
            final OWLOntology ontologyFinal = ontology;
            final String detectedProfileFinal = detectedProfile;
            java.util.function.Supplier<ServiceResult<Boolean>> primarySupplier = () -> {
                try {
                    OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                        ontologyId, effectiveReasoner, ontologyFinal, detectedProfileFinal, false);
                    org.owl4agents.core.model.ConsistencyResult cr = adapter.checkConsistency(ontologyId.id());
                    return ServiceResult.success(cr.consistent(), ResultMetadata.empty());
                } catch (Exception e) {
                    return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
                }
            };
            java.util.function.Supplier<ServiceResult<Boolean>> elkSupplier = () -> {
                try {
                    OWLReasonerAdapter elkAdapter = lifecycleManager.getOrCreateReasoner(
                        ontologyId, "ELK", ontologyFinal, detectedProfileFinal, false);
                    org.owl4agents.core.model.ConsistencyResult cr = elkAdapter.checkConsistency(ontologyId.id());
                    return ServiceResult.success(cr.consistent(), ResultMetadata.empty());
                } catch (Exception e) {
                    return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
                }
            };

            ServiceResult<Boolean> result = reasonerCallWrapper.callWithElkFallback(
                effectiveReasoner, ontologyId, primarySupplier, elkSupplier, reasonerTimeoutSec);

            // Release reasoner references based on metadata
            // (per D1: caller-only responsibility)
            try {
                if (result.isSuccess()) {
                    ServiceResult.Success<Boolean> success = (ServiceResult.Success<Boolean>) result;
                    org.owl4agents.core.model.ReasonerCallMetadata meta = success.reasonerMetadata();
                    if (meta != null) {
                        if (meta.fallbackFrom() != null) {
                            lifecycleManager.releaseReasoner(ontologyId, meta.fallbackFrom());
                        }
                        lifecycleManager.releaseReasoner(ontologyId, meta.reasonerName());
                    } else {
                        lifecycleManager.releaseReasoner(ontologyId, effectiveReasoner);
                    }
                    // Cache successful result
                    putSourceConsistencyCacheEntry(cacheKey, success.data());
                } else {
                    ServiceResult.Error<Boolean> error = (ServiceResult.Error<Boolean>) result;
                    org.owl4agents.core.model.ReasonerCallMetadata meta = error.reasonerMetadata();
                    if (meta != null) {
                        if (meta.fallbackFrom() != null) {
                            lifecycleManager.releaseReasoner(ontologyId, meta.fallbackFrom());
                        }
                        // Release the fallback reasoner (ELK) even on timeout
                        if (error.error().code() == ErrorCode.REASONER_TIMEOUT) {
                            lifecycleManager.releaseReasoner(ontologyId, "ELK");
                        } else {
                            lifecycleManager.releaseReasoner(ontologyId, meta.reasonerName());
                        }
                    } else {
                        // REASONER_BUSY — no reasoner was acquired
                    }
                }
            } catch (Exception releaseEx) {
                // Release failures are best-effort; log and continue
                java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName())
                    .warning("Reasoner release failed: " + releaseEx.getMessage());
            }

            // v0.8.6 task 3.11a: REASONER_REJECTED_ONTOLOGY retry.
            // When ELK was selected as primary (size-aware branch for a large
            // non-EL ontology) and the wrapper returned REASONER_REJECTED_ONTOLOGY
            // (ELK rejected non-EL axioms — per D1's always-returns contract,
            // the wrapper catches UnsupportedAxiomException and maps it to this
            // code), retry with HermiT via call() — NOT callWithElkFallback —
            // to avoid a futile ELK retry loop (ELK already rejected the ontology).
            // ELK's reasoner was released above (acquired but now unusable).
            // HermiT's 30s timeout still applies via call(). If HermiT also
            // returns REASONER_REJECTED_ONTOLOGY, propagate the error to the
            // caller (no further fallback).
            if (!result.isSuccess() && "ELK".equalsIgnoreCase(effectiveReasoner)) {
                ServiceResult.Error<Boolean> rejectedError = (ServiceResult.Error<Boolean>) result;
                if (rejectedError.error().code() == ErrorCode.REASONER_REJECTED_ONTOLOGY) {
                    java.util.function.Supplier<ServiceResult<Boolean>> hermitSupplier = () -> {
                        try {
                            OWLReasonerAdapter hermitAdapter = lifecycleManager.getOrCreateReasoner(
                                ontologyId, "HermiT", ontologyFinal, detectedProfileFinal, false);
                            org.owl4agents.core.model.ConsistencyResult cr =
                                hermitAdapter.checkConsistency(ontologyId.id());
                            return ServiceResult.success(cr.consistent(), ResultMetadata.empty());
                        } catch (Exception e) {
                            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
                        }
                    };

                    ServiceResult<Boolean> hermitResult = reasonerCallWrapper.call(
                        "HermiT", ontologyId, hermitSupplier, reasonerTimeoutSec);

                    // Release HermiT based on metadata
                    try {
                        if (hermitResult.isSuccess()) {
                            ServiceResult.Success<Boolean> hermitSuccess =
                                (ServiceResult.Success<Boolean>) hermitResult;
                            org.owl4agents.core.model.ReasonerCallMetadata hermitMeta =
                                hermitSuccess.reasonerMetadata();
                            if (hermitMeta != null) {
                                lifecycleManager.releaseReasoner(ontologyId, hermitMeta.reasonerName());
                            } else {
                                lifecycleManager.releaseReasoner(ontologyId, "HermiT");
                            }
                            // Cache successful HermiT result (replaces the would-be ELK entry)
                            putSourceConsistencyCacheEntry(cacheKey, hermitSuccess.data());
                        } else {
                            ServiceResult.Error<Boolean> hermitError =
                                (ServiceResult.Error<Boolean>) hermitResult;
                            org.owl4agents.core.model.ReasonerCallMetadata hermitMeta =
                                hermitError.reasonerMetadata();
                            if (hermitMeta != null) {
                                lifecycleManager.releaseReasoner(ontologyId, hermitMeta.reasonerName());
                            } else {
                                lifecycleManager.releaseReasoner(ontologyId, "HermiT");
                            }
                            // If HermiT also rejected the ontology, propagate (no further fallback).
                            // Otherwise (timeout, internal error, etc.), also propagate.
                        }
                    } catch (Exception releaseEx) {
                        java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName())
                            .warning("HermiT retry release failed: " + releaseEx.getMessage());
                    }

                    result = hermitResult;
                }
            }

            return result;
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (OWLOntologyCreationException e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && (e.getMessage().contains("Unknown reasoner")
                    || e.getMessage().contains("PROFILE_NOT_SUPPORTED"))) {
                return ServiceResult.error(ErrorCode.PROFILE_NOT_SUPPORTED, e.getMessage());
            }
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    @Override
    public ServiceResult<EntailmentResult> checkAxiomEntailment(
            OWLOntology ontology, OntologyId ontologyId,
            OWLAxiom axiom, Optional<String> reasonerName) {
        if (ontology == null || axiom == null) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_PARAMETERS,
                "Ontology and axiom must not be null.");
        }

        // D1 stage 3 asserted fast-path: if the axiom is already in the
        // ontology's asserted axiom set, return ENTAILED immediately
        // without invoking the reasoner. This prevents false UNKNOWN
        // verdicts when a reasoner (e.g. ELK) does not support
        // isEntailed() for certain axiom types but the axiom is trivially
        // present in the ontology.
        try {
            if (ontology.containsAxiom(axiom, true)) {
                return ServiceResult.success(
                    new EntailmentResult(ontologyId.id(), axiom.getAxiomType().getName(),
                        EntailmentResult.ENTAILED, "asserted", "", ""),
                    ResultMetadata.empty());
            }
            // v0.8.5: DifferentIndividuals subset matching. The ontology
            // may assert a single AllDifferent axiom over N individuals
            // (e.g. DifferentIndividuals(America, England, France,
            // Germany, Italy)) while the query asks about a pair (e.g.
            // France vs Germany). containsAxiom returns false for the
            // 2-individual axiom because the asserted axiom has 5
            // individuals. Recognize this case as asserted, since the
            // pairwise different-from relationship is explicitly stated
            // in the source ontology.
            if (axiom instanceof OWLDifferentIndividualsAxiom queryDiff) {
                java.util.Set<OWLIndividual> queryInds = queryDiff.getIndividuals();
                for (OWLDifferentIndividualsAxiom assertedDiff :
                        ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS, Imports.INCLUDED)) {
                    if (assertedDiff.getIndividuals().containsAll(queryInds)) {
                        return ServiceResult.success(
                            new EntailmentResult(ontologyId.id(), axiom.getAxiomType().getName(),
                                EntailmentResult.ENTAILED, "asserted", "", ""),
                            ResultMetadata.empty());
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to reasoner check
        }

        // Resolve reasoner
        String detectedProfile = detectProfile(ontologyId, ontology);
        String effectiveReasoner = resolveReasonerName(reasonerName, detectedProfile, false, ontology);

        // v0.8.6 D1: Route through ReasonerCallWrapper with ELK fallback.
        final OWLOntology ontologyFinal = ontology;
        final String detectedProfileFinal = detectedProfile;
        final OWLAxiom axiomFinal = axiom;
        java.util.function.Supplier<ServiceResult<EntailmentResult>> primarySupplier = () -> {
            try {
                OWLReasonerAdapter adapter = lifecycleManager.getOrCreateReasoner(
                    ontologyId, effectiveReasoner, ontologyFinal, detectedProfileFinal, false);
                if (!adapter.isActive()) {
                    adapter.initialize(ontologyFinal);
                }
                try {
                    boolean entailed = adapter.getUnderlyingReasoner().isEntailed(axiomFinal);
                    return ServiceResult.success(
                        new EntailmentResult(ontologyId.id(), axiomFinal.getAxiomType().getName(),
                            entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                            "inferred", effectiveReasoner, ""),
                        ResultMetadata.empty());
                } catch (org.semanticweb.owlapi.model.OWLRuntimeException e) {
                    // ELK throws on unsupported axiom types — return UNSUPPORTED_AXIOM_TYPE
                    return ServiceResult.success(
                        new EntailmentResult(ontologyId.id(), axiomFinal.getAxiomType().getName(),
                            EntailmentResult.UNSUPPORTED_AXIOM_TYPE, "unsupported",
                            effectiveReasoner, e.getClass().getSimpleName()),
                        ResultMetadata.empty());
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && (e.getMessage().contains("Unknown reasoner")
                        || e.getMessage().contains("PROFILE_NOT_SUPPORTED"))) {
                    return ServiceResult.error(ErrorCode.PROFILE_NOT_SUPPORTED, e.getMessage());
                }
                return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
            } catch (Exception e) {
                return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
            }
        };
        java.util.function.Supplier<ServiceResult<EntailmentResult>> elkSupplier = () -> {
            try {
                OWLReasonerAdapter elkAdapter = lifecycleManager.getOrCreateReasoner(
                    ontologyId, "ELK", ontologyFinal, detectedProfileFinal, false);
                if (!elkAdapter.isActive()) {
                    elkAdapter.initialize(ontologyFinal);
                }
                try {
                    boolean entailed = elkAdapter.getUnderlyingReasoner().isEntailed(axiomFinal);
                    return ServiceResult.success(
                        new EntailmentResult(ontologyId.id(), axiomFinal.getAxiomType().getName(),
                            entailed ? EntailmentResult.ENTAILED : EntailmentResult.NOT_ENTAILED,
                            "inferred", "ELK", ""),
                        ResultMetadata.empty());
                } catch (org.semanticweb.owlapi.model.OWLRuntimeException e) {
                    return ServiceResult.success(
                        new EntailmentResult(ontologyId.id(), axiomFinal.getAxiomType().getName(),
                            EntailmentResult.UNSUPPORTED_AXIOM_TYPE, "unsupported",
                            "ELK", e.getClass().getSimpleName()),
                        ResultMetadata.empty());
                }
            } catch (Exception e) {
                return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
            }
        };

        try {
            ServiceResult<EntailmentResult> result = reasonerCallWrapper.callWithElkFallback(
                effectiveReasoner, ontologyId, primarySupplier, elkSupplier, reasonerTimeoutSec);

            // Release reasoner references based on metadata
            try {
                if (result.isSuccess()) {
                    ServiceResult.Success<EntailmentResult> success = (ServiceResult.Success<EntailmentResult>) result;
                    org.owl4agents.core.model.ReasonerCallMetadata meta = success.reasonerMetadata();
                    if (meta != null) {
                        if (meta.fallbackFrom() != null) {
                            lifecycleManager.releaseReasoner(ontologyId, meta.fallbackFrom());
                        }
                        lifecycleManager.releaseReasoner(ontologyId, meta.reasonerName());
                    } else {
                        lifecycleManager.releaseReasoner(ontologyId, effectiveReasoner);
                    }
                } else {
                    ServiceResult.Error<EntailmentResult> error = (ServiceResult.Error<EntailmentResult>) result;
                    org.owl4agents.core.model.ReasonerCallMetadata meta = error.reasonerMetadata();
                    if (meta != null) {
                        if (meta.fallbackFrom() != null) {
                            lifecycleManager.releaseReasoner(ontologyId, meta.fallbackFrom());
                        }
                        if (error.error().code() == ErrorCode.REASONER_TIMEOUT) {
                            lifecycleManager.releaseReasoner(ontologyId, "ELK");
                        } else {
                            lifecycleManager.releaseReasoner(ontologyId, meta.reasonerName());
                        }
                    }
                }
            } catch (Exception releaseEx) {
                java.util.logging.Logger.getLogger(ReasonerServiceImpl.class.getName())
                    .warning("Reasoner release failed: " + releaseEx.getMessage());
            }

            return result;
        } catch (ReasonerIncompatibleException e) {
            return ServiceResult.error(e.errorCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && (e.getMessage().contains("Unknown reasoner")
                    || e.getMessage().contains("PROFILE_NOT_SUPPORTED"))) {
                return ServiceResult.error(ErrorCode.PROFILE_NOT_SUPPORTED, e.getMessage());
            }
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.CLASSIFICATION_FAILED, e.getMessage());
        }
    }

    // ── v0.8.5 helpers ──────────────────────────────────────────────────

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Flatten an {@link org.owl4agents.core.model.InconsistencyExplanation} into
     * a list of Manchester-syntax axiom description strings (D10).
     */
    private static java.util.List<String> flattenExplanation(
            org.owl4agents.core.model.InconsistencyExplanation expl) {
        if (expl == null || expl.conflictingAxiomSets() == null) {
            return java.util.List.of();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (var set : expl.conflictingAxiomSets()) {
            if (set.axiomDescriptions() != null) {
                result.addAll(set.axiomDescriptions());
            }
        }
        return result;
    }

    /**
     * Compute a SHA-256 fingerprint of the source ontology file content.
     * Used as part of the source consistency cache key (D5).
     */
    private String computeFingerprint(OntologyId ontologyId, OWLOntology ontology) {
        try {
            Path ontologyPath = resolveOntologyPathInternal(ontologyId);
            if (ontologyPath != null && Files.exists(ontologyPath)) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (InputStream is = Files.newInputStream(ontologyPath)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        md.update(buf, 0, n);
                    }
                }
                byte[] hash = md.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            // fall through to ontology-based fingerprint
        }
        // Fallback: hash the axioms directly
        int hash = ontology.getAxioms(Imports.INCLUDED).hashCode();
        return Integer.toHexString(hash);
    }

    /**
     * Compute a hash of the sorted set of all direct and transitive import
     * IRIs from the source ontology (D5).
     */
    private String computeImportsState(OWLOntology ontology) {
        try {
            java.util.List<String> iris = ontology.getImportsDeclarations().stream()
                .map(decl -> decl.getIRI().toString())
                .sorted()
                .toList();
            if (iris.isEmpty()) return "none";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String iri : iris) {
                md.update(iri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) ',');
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String buildSourceConsistencyCacheKey(
            String ontologyId, String fingerprint, String reasonerName, String importsState) {
        return ontologyId + "|" + fingerprint + "|" + reasonerName + "|" + importsState;
    }

    private Boolean getSourceConsistencyCacheEntry(String key) {
        // v0.8.6 task 5.8: Caffeine cache — getIfPresent is thread-safe,
        // no explicit synchronization needed. Hit/miss counters are updated
        // by the caller (checkSourceOntologyConsistency) based on the
        // returned value (null = miss, non-null = hit).
        return sourceConsistencyCache.getIfPresent(key);
    }

    private void putSourceConsistencyCacheEntry(String key, Boolean value) {
        // v0.8.6 task 5.8: Caffeine handles LRU eviction internally via
        // W-TinyLFU policy. No manual eviction code needed (the previous
        // LinkedHashMap-based code removed the eldest entry manually).
        sourceConsistencyCache.put(key, value);
    }

    private void invalidateSourceConsistencyCache(String ontologyId) {
        // v0.8.6 task 5.8: Caffeine does not support prefix-based
        // invalidation directly. We iterate the keys and invalidate
        // matches. This is O(n) but n is bounded by maximumSize(200).
        String prefix = ontologyId + "|";
        java.util.List<String> keysToInvalidate = new java.util.ArrayList<>();
        for (String k : sourceConsistencyCache.asMap().keySet()) {
            if (k.startsWith(prefix)) {
                keysToInvalidate.add(k);
            }
        }
        for (String k : keysToInvalidate) {
            sourceConsistencyCache.invalidate(k);
        }
    }

    public long getSourceConsistencyCacheHits() {
        // v0.8.6 task 5.8: Prefer Caffeine's recorded stats when available;
        // fall back to the local counter for backward compatibility.
        long caffeineHits = sourceConsistencyCache.stats().hitCount();
        return Math.max(caffeineHits, sourceConsistencyCacheHits.get());
    }

    public long getSourceConsistencyCacheMisses() {
        long caffeineMisses = sourceConsistencyCache.stats().missCount();
        return Math.max(caffeineMisses, sourceConsistencyCacheMisses.get());
    }

    public long getSourceConsistencyCacheEvictions() {
        // v0.8.6 task 5.8: Caffeine records eviction count via stats().
        return sourceConsistencyCache.stats().evictionCount();
    }

    public int getSourceConsistencyCacheSize() {
        long size = sourceConsistencyCache.estimatedSize();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    /**
     * Resolve the canonical ontology file path for the given ontology ID.
     * Mirrors {@link OntologyCache}'s path resolution.
     */
    private Path resolveOntologyPathInternal(OntologyId ontologyId) {
        return Path.of(workspaceBasePath, workspaceName, "ontologies",
            ontologyId.id(), "canonical", "ontology.owl");
    }

    // ════════════════════════════════════════════════════════════════════════
    // v0.8.5 P1 fix: Cached exact-check session for warm performance
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Cached exact-check session: holds a base ontology copy (without any
     * claim axiom) and a reasoner initialized on it. Claim axioms are
     * added/removed incrementally via {@code applyChange} for O(ms) checks
     * instead of O(seconds) copy+init per claim.
     *
     * <p>Thread safety: {@link #opLock} serializes add/check/remove operations
     * so that only one claim is processed at a time per ontology (per the
     * design constraint that reasoner-using tool calls are serialized).
     */
    static final class CachedExactCheckSession {
        final TemporaryOntologyHandle baseHandle;
        final TransientReasonerSession session;
        final java.util.concurrent.locks.ReentrantLock opLock =
            new java.util.concurrent.locks.ReentrantLock();

        CachedExactCheckSession(TemporaryOntologyHandle baseHandle,
                                TransientReasonerSession session) {
            this.baseHandle = baseHandle;
            this.session = session;
        }

        void close() {
            try { session.close(); } catch (Exception ignored) {}
            try { baseHandle.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Invalidate (and close) the cached exact-check session for the given
     * ontology ID. Called on ontology reload.
     *
     * <p>Removes ALL reasoner sessions for this ontology (e.g., both
     * {@code (ontologyId, HermiT)} and {@code (ontologyId, Openllet)}).
     * Use {@link #invalidateExactCheckSessionCache(String, String)} when
     * only a specific reasoner's session should be invalidated.
     */
    private void invalidateExactCheckSessionCache(String ontologyId) {
        // Remove all entries whose key starts with "ontologyId|"
        java.util.List<String> keysToRemove = new java.util.ArrayList<>();
        for (String key : exactCheckSessionCache.keySet()) {
            if (key.startsWith(ontologyId + "|")) {
                keysToRemove.add(key);
            }
        }
        for (String key : keysToRemove) {
            CachedExactCheckSession cached = exactCheckSessionCache.remove(key);
            if (cached != null) {
                cached.close();
            }
        }
    }

    /**
     * Invalidate (and close) the cached exact-check session for a specific
     * (ontologyId, reasonerName) pair.
     *
     * <p>v0.8.8 P2: This precise invalidation avoids clearing unrelated
     * reasoner sessions. Per claim-verification spec, when a Stage 4 check
     * fails (timeout, exception, axiom removal failure, simplicity violation),
     * only the {@code (ontologyId, <DL reasoner after D1 override>)} session
     * should be invalidated — NOT the original claim-specified reasoner (e.g.,
     * invalidate {@code (hpo, HermiT)}, not {@code (hpo, ELK)}).
     *
     * <p>Note: the {@code (ontologyId, ELK)} session is never created under
     * D1 override (ELK is always overridden to HermiT/Openllet for Stage 4),
     * so there is no ELK session to preserve in practice. This method still
     * precisely targets only the specified reasoner to be safe.
     *
     * @param ontologyId the ontology ID
     * @param reasonerName the reasoner name (e.g., "HermiT" after D1 override)
     */
    private void invalidateExactCheckSessionCache(String ontologyId, String reasonerName) {
        String cacheKey = ontologyId + "|" + reasonerName;
        CachedExactCheckSession cached = exactCheckSessionCache.remove(cacheKey);
        if (cached != null) {
            cached.close();
        }
    }

    /**
     * Close all cached exact-check sessions. Called on full reload and shutdown.
     */
    private void closeAllExactCheckSessionCache() {
        java.util.Iterator<java.util.Map.Entry<String, CachedExactCheckSession>> it =
            exactCheckSessionCache.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, CachedExactCheckSession> entry = it.next();
            entry.getValue().close();
            it.remove();
        }
    }

    /**
     * Get or create a cached exact-check session for the given ontology and
     * reasoner. On cache miss, creates the base ontology copy and initializes
     * the reasoner (expensive for large ontologies). On cache hit, returns
     * the existing session (O(1)).
     *
     * @return an array of {@code [CachedExactCheckSession, temporaryCopyMs, reasonerInitMs]};
     *         on failure returns {@code null} (caller handles error)
     */
    private CachedExactCheckSession getOrCreateCachedSession(
            OWLOntology sourceOntology,
            String ontologyIdStr,
            String effectiveReasoner,
            java.time.Duration effectiveTimeout,
            long[] timingOut) {

        String cacheKey = ontologyIdStr + "|" + effectiveReasoner;
        CachedExactCheckSession cached = exactCheckSessionCache.get(cacheKey);
        if (cached != null) {
            timingOut[0] = 0; // temporaryCopyMs
            timingOut[1] = 0; // reasonerInitMs
            return cached;
        }

        // Cache miss: create base copy (without claim axiom)
        long tempCopyStart = System.nanoTime();
        ServiceResult<TemporaryOntologyHandle> baseResult =
            temporaryOntologyFactory.createBase(sourceOntology, TemporaryOntologyOptions.defaults());
        timingOut[0] = msSince(tempCopyStart);

        if (!baseResult.isSuccess()) {
            return null;
        }
        TemporaryOntologyHandle baseHandle =
            ((ServiceResult.Success<TemporaryOntologyHandle>) baseResult).data();

        // Initialize reasoner on base ontology
        long reasonerInitStart = System.nanoTime();
        ServiceResult<TransientReasonerSession> sessionResult =
            TransientReasonerSession.create(baseHandle.ontology(), effectiveReasoner, effectiveTimeout);
        timingOut[1] = msSince(reasonerInitStart);

        if (!sessionResult.isSuccess()) {
            baseHandle.close();
            return null;
        }
        TransientReasonerSession session =
            ((ServiceResult.Success<TransientReasonerSession>) sessionResult).data();

        CachedExactCheckSession newEntry = new CachedExactCheckSession(baseHandle, session);
        CachedExactCheckSession existing = exactCheckSessionCache.putIfAbsent(cacheKey, newEntry);
        if (existing != null) {
            // Another thread won the race; close our new session, use existing
            newEntry.close();
            timingOut[0] = 0;
            timingOut[1] = 0;
            return existing;
        }
        return newEntry;
    }
}