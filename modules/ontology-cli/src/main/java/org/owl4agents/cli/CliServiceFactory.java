package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;
import org.owl4agents.owlapi.EntitySignatureCacheManager;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.owlapi.OntologyImporter;
import org.owl4agents.owlapi.OntologySummaryExtractor;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.query.*;
import org.owl4agents.reasoner.ReasonerServiceImpl;
import org.owl4agents.reasoner.ReasonerLifecycleManager;
import org.owl4agents.retrieval.*;
import org.owl4agents.storage.*;
import org.owl4agents.validation.ConsistencyAnalysisService;
import org.owl4agents.validation.ClaimVerificationService;
import org.owl4agents.validation.ClaimWorkflowService;
import org.owl4agents.validation.EvidenceGroundingService;
import org.owl4agents.validation.EvidenceContextBuilder;
// v0.8.7 Pipeline CLI: overlay + shacl + toolcall services.
import org.owl4agents.overlay.TransientOntologyOverlayService;
import org.owl4agents.overlay.TransientOntologyOverlayServiceImpl;
import org.owl4agents.shacl.FileShapeRegistry;
import org.owl4agents.shacl.JenaShaclValidationService;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.shacl.SampleShapeSeeder;
import org.owl4agents.toolcall.SampleContractSeeder;
import org.owl4agents.toolcall.ToolContractRegistry;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Factory that creates service instances based on CLI options.
 * This ensures CLI commands share the same service instances,
 * mirroring the MCP adapter behavior.
 */
public class CliServiceFactory {

    private final String workspaceName;
    private final String homeDirectory;

    private HomeDirectoryResolver homeResolver;
    private WorkspaceInitializer workspaceInitializer;
    private CatalogStore catalogStore;
    private OntologyImporter ontologyImporter;
    private OntologySummaryExtractor summaryExtractor;
    private SparqlValidator sparqlValidator;
    private SparqlExecutor sparqlExecutor;
    private SparqlSafetyGuard sparqlSafetyGuard;
    private ReasonerServiceImpl reasonerService;
    private SemanticDeepeningService semanticDeepeningService;
    private ConsistencyAnalysisService consistencyAnalysisService;
    private EntitySignatureCacheManager entitySignatureCacheManager;
    private OntologyCache ontologyCache;
    private ClaimVerificationService claimVerificationService;
    private EvidenceGroundingService evidenceGroundingService;
    private ClaimWorkflowService claimWorkflowService;
    private EvidenceContextBuilder evidenceContextBuilder;
    // v0.8.7 Pipeline CLI: lazy-initialized services for toolcall-validate.
    private TransientOntologyOverlayService overlayService;
    private ShapeRegistry shapeRegistry;
    private ShaclValidationService shaclValidationService;
    private ToolContractRegistry toolContractRegistry;
    // v0.9.1 mcp-write-tools-expansion: lazy-initialized services for the
    // 8 transactional write tools + 3 readonly observation tools.
    private org.owl4agents.reasoner.TemporaryOntologyFactory temporaryOntologyFactory;
    private org.owl4agents.reasoner.write.VersionHistoryStore versionHistoryStore;
    private org.owl4agents.reasoner.write.AuditLog auditLog;
    private org.owl4agents.reasoner.write.WriteTransactionService writeTransactionService;
    private org.owl4agents.reasoner.write.OntologyEditService ontologyEditService;

    public CliServiceFactory(String workspaceName, String homeDirectory) {
        this.workspaceName = workspaceName;
        this.homeDirectory = homeDirectory;
    }

    public WorkspaceId getWorkspaceId() {
        return new WorkspaceId(workspaceName);
    }

    public HomeDirectoryResolver getHomeResolver() {
        if (homeResolver == null) {
            if (homeDirectory != null) {
                homeResolver = new HomeDirectoryResolver(Path.of(homeDirectory));
            } else {
                homeResolver = new HomeDirectoryResolver();
            }
        }
        return homeResolver;
    }

    public WorkspaceInitializer getWorkspaceInitializer() {
        if (workspaceInitializer == null) {
            workspaceInitializer = new WorkspaceInitializer(getHomeResolver());
        }
        return workspaceInitializer;
    }

    public CatalogStore getCatalogStore() {
        if (catalogStore == null) {
            catalogStore = new CatalogStore(getHomeResolver());
        }
        return catalogStore;
    }

    public OntologyImporter getOntologyImporter() {
        if (ontologyImporter == null) {
            ontologyImporter = new OntologyImporter(getHomeResolver(), getCatalogStore());
        }
        return ontologyImporter;
    }

    public OntologySummaryExtractor getSummaryExtractor() {
        if (summaryExtractor == null) {
            summaryExtractor = new OntologySummaryExtractor();
        }
        return summaryExtractor;
    }

    public SparqlValidator getSparqlValidator() {
        if (sparqlValidator == null) {
            sparqlValidator = new SparqlValidator();
        }
        return sparqlValidator;
    }

    public SparqlExecutor getSparqlExecutor() {
        if (sparqlExecutor == null) {
            sparqlExecutor = new SparqlExecutor();
        }
        return sparqlExecutor;
    }

    public SparqlSafetyGuard getSparqlSafetyGuard() {
        if (sparqlSafetyGuard == null) {
            sparqlSafetyGuard = new SparqlSafetyGuard();
        }
        return sparqlSafetyGuard;
    }

    /**
     * Load an ontology from the catalog by ontology ID.
     * Returns the loaded OWLOntology or throws if not found.
     */
    public OWLOntology loadOntology(OntologyId ontologyId) throws OWLOntologyCreationException {
        CatalogEntry entry = findCatalogEntry(ontologyId);
        Path canonicalPath = entry.canonicalPath();

        if (!Files.exists(canonicalPath)) {
            throw new OWLOntologyCreationException("Canonical ontology file not found: " + canonicalPath);
        }

        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(canonicalPath.toFile());
    }

    /**
     * Find a catalog entry by ontology ID.
     */
    public CatalogEntry findCatalogEntry(OntologyId ontologyId) {
        ServiceResult<CatalogEntry> result = getCatalogStore().findEntry(getWorkspaceId(), ontologyId);
        if (!result.isSuccess()) {
            var error = ((ServiceResult.Error<CatalogEntry>) result).error();
            throw new RuntimeException("Ontology not found: " + error.message());
        }
        return ((ServiceResult.Success<CatalogEntry>) result).data();
    }

    /**
     * Create an EntityIndex for the given ontology.
     */
    public EntityIndex createEntityIndex(OntologyId ontologyId) throws OWLOntologyCreationException {
        OWLOntology ontology = loadOntology(ontologyId);
        EntityIndex index = new EntityIndex();
        index.buildFromOntology(ontology);
        return index;
    }

    /**
     * Create an EntitySearchService for the given ontology.
     */
    public EntitySearchService createEntitySearchService(OntologyId ontologyId) throws OWLOntologyCreationException {
        EntityIndex index = createEntityIndex(ontologyId);
        return new EntitySearchService(index, ontologyId);
    }

    /**
     * Create a QaContextService for the given ontology.
     */
    public QaContextService createQaContextService(OntologyId ontologyId) throws OWLOntologyCreationException {
        OWLOntology ontology = loadOntology(ontologyId);
        EntityIndex index = new EntityIndex();
        index.buildFromOntology(ontology);
        return new QaContextService(index, ontologyId, ontology);
    }

    /**
     * Get the reasoner service instance.
     * ReasonerServiceImpl resolves ontology paths as workspaceBasePath/{workspaceName}/ontologies/{id}/...
     * The importer stores ontologies at homeDir/workspaces/{workspaceName}/ontologies/{id}/...
     * So we pass homeDir/workspaces as the base path and the workspace name.
     */
    /**
     * Get the shared OntologyCache instance (lazy-initialized).
     * Independent of getReasonerService() to avoid initialization order coupling.
     */
    private OntologyCache getOntologyCache() {
        if (ontologyCache == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            ontologyCache = new OntologyCache(workspaceBasePath, workspaceName);
        }
        return ontologyCache;
    }

    /**
     * Public accessor for benchmark warm-up.
     */
    public OntologyCache getSharedOntologyCache() {
        return getOntologyCache();
    }

    public ReasonerServiceImpl getReasonerService() {
        if (reasonerService == null) {
            String workspaceBasePath = getOntologyCache().getWorkspaceBasePath();
            reasonerService = new ReasonerServiceImpl(
                getCatalogStore(), workspaceBasePath, workspaceName, getOntologyCache(),
                getEntitySignatureCacheManager());
        }
        return reasonerService;
    }

    /**
     * Get the semantic deepening service instance.
     * Calls getOntologyCache() directly (NOT getReasonerService()) to
     * avoid implicit initialization order coupling.
     */
    public SemanticDeepeningService getSemanticDeepeningService() {
        if (semanticDeepeningService == null) {
            String workspaceBasePath = getOntologyCache().getWorkspaceBasePath();
            semanticDeepeningService = new SemanticDeepeningService(workspaceBasePath, getOntologyCache());
        }
        return semanticDeepeningService;
    }

    /**
     * Get the shared EntitySignatureCacheManager instance (lazy-initialized).
     * Registered as an OntologyReloadListener on the shared OntologyCache.
     */
    private EntitySignatureCacheManager getEntitySignatureCacheManager() {
        if (entitySignatureCacheManager == null) {
            entitySignatureCacheManager = new EntitySignatureCacheManager();
            getOntologyCache().addReloadListener(entitySignatureCacheManager);
        }
        return entitySignatureCacheManager;
    }

    /**
     * Get the consistency analysis service instance.
     */
    public ConsistencyAnalysisService getConsistencyAnalysisService() {
        if (consistencyAnalysisService == null) {
            String workspaceBasePath = getOntologyCache().getWorkspaceBasePath();
            ReasonerLifecycleManager lifecycleManager = getReasonerService().getLifecycleManager();
            consistencyAnalysisService = new ConsistencyAnalysisService(
                lifecycleManager, workspaceBasePath, getOntologyCache(),
                getEntitySignatureCacheManager());
        }
        return consistencyAnalysisService;
    }

    /**
     * Get the claim verification service instance.
     */
    public ClaimVerificationService getClaimVerificationService() {
        if (claimVerificationService == null) {
            claimVerificationService = new ClaimVerificationService(
                getReasonerService(),
                getConsistencyAnalysisService(),
                getSemanticDeepeningService(),
                getCatalogStore(),
                getWorkspaceId()
            );
        }
        return claimVerificationService;
    }

    /**
     * Get the evidence grounding service instance.
     */
    public EvidenceGroundingService getEvidenceGroundingService() {
        if (evidenceGroundingService == null) {
            evidenceGroundingService = new EvidenceGroundingService(
                getReasonerService(),
                getConsistencyAnalysisService()
            );
        }
        return evidenceGroundingService;
    }

    /**
     * Get the claim workflow service instance for v0.5 batch verification.
     */
    public ClaimWorkflowService getClaimWorkflowService() {
        if (claimWorkflowService == null) {
            claimWorkflowService = new ClaimWorkflowService(
                getClaimVerificationService(),
                getEvidenceGroundingService(),
                getCatalogStore(),
                getWorkspaceId(),
                getReasonerService()
            );
        }
        return claimWorkflowService;
    }

    /**
     * Get the evidence context builder instance for v0.5 context generation.
     */
    public EvidenceContextBuilder getEvidenceContextBuilder() {
        if (evidenceContextBuilder == null) {
            evidenceContextBuilder = new EvidenceContextBuilder();
        }
        return evidenceContextBuilder;
    }

    // ── v0.8.7 Pipeline CLI service accessors ──

    /**
     * Get the shared {@link TransientOntologyOverlayService} instance
     * (lazy-initialized). Reuses the shared {@link OntologyCache} so the
     * overlay resolves base ontologies through the same cache as the
     * reasoner service.
     */
    public TransientOntologyOverlayService getOverlayService() {
        if (overlayService == null) {
            overlayService = new TransientOntologyOverlayServiceImpl(getOntologyCache());
        }
        return overlayService;
    }

    /**
     * Get the shared {@link ShapeRegistry} instance (lazy-initialized).
     * Used by the Pipeline CLI for stage 7 SHACL validation and by v0.9.1
     * SHACL-on-commit (via {@link org.owl4agents.mcp.WriteToolsHandler}).
     *
     * <p>Resolves the shapes directory from the {@link HomeDirectoryResolver}
     * so that {@code owl4agents.home} system property / {@code OWL4AGENTS_HOME}
     * env var are respected. This enables test isolation (parity tests set
     * {@code owl4agents.home} to a temp directory).</p>
     */
    public ShapeRegistry getShapeRegistry() {
        if (shapeRegistry == null) {
            Path shapesDir = getHomeResolver().resolveHomeDirectory()
                .resolve(".owl4agents").resolve("shapes");
            shapeRegistry = new FileShapeRegistry(shapesDir.resolve("registry.json"));
            // Issue #4: seed a sample SHACL shape file on first startup
            // so new users have a working starting point. The seeder does
            // NOT auto-register the shape set; the user must run
            // shacl-register to register it.
            SampleShapeSeeder.seedIfEmpty(shapesDir);
        }
        return shapeRegistry;
    }

    /**
     * Get the shared {@link ShaclValidationService} instance
     * (lazy-initialized). Wired with the shared {@link ShapeRegistry}.
     */
    public ShaclValidationService getShaclValidationService() {
        if (shaclValidationService == null) {
            shaclValidationService = new JenaShaclValidationService(getShapeRegistry());
        }
        return shaclValidationService;
    }

    /**
     * Get the shared {@link ToolContractRegistry} instance
     * (lazy-initialized). Loads contracts from
     * {@code ~/.owl4agents/contracts/<toolName>.json} with mtime-based
     * hot-reload.
     */
    public ToolContractRegistry getToolContractRegistry() {
        if (toolContractRegistry == null) {
            toolContractRegistry = new ToolContractRegistry();
            // Issue #4: seed sample contracts on first startup so the
            // pipeline has working defaults instead of an empty registry
            // (which always returned decision=reject). Reload after
            // seeding so the newly written files are loaded into the
            // in-memory cache.
            SampleContractSeeder.seedIfEmpty(toolContractRegistry.contractsDirectory());
            toolContractRegistry.reloadAll();
        }
        return toolContractRegistry;
    }

    // ── v0.9.1 mcp-write-tools-expansion CLI service accessors ──

    /**
     * v0.9.1: Get the shared {@link org.owl4agents.reasoner.TemporaryOntologyFactory}
     * instance (lazy-initialized). Used by {@link #getWriteTransactionService()}
     * to create isolated staging ontologies per transaction.
     */
    public org.owl4agents.reasoner.TemporaryOntologyFactory getTemporaryOntologyFactory() {
        if (temporaryOntologyFactory == null) {
            temporaryOntologyFactory = new org.owl4agents.reasoner.TemporaryOntologyFactory();
        }
        return temporaryOntologyFactory;
    }

    /**
     * v0.9.1: Get the shared {@link org.owl4agents.reasoner.write.VersionHistoryStore}
     * instance (lazy-initialized). Storage root is
     * {@code <workspace>/ontologies/<id>/versions/}.
     */
    public org.owl4agents.reasoner.write.VersionHistoryStore getVersionHistoryStore() {
        if (versionHistoryStore == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            versionHistoryStore = new org.owl4agents.reasoner.write.VersionHistoryStore(
                workspaceBasePath, workspaceName);
        }
        return versionHistoryStore;
    }

    /**
     * v0.9.1: Get the shared {@link org.owl4agents.reasoner.write.AuditLog}
     * instance (lazy-initialized). Storage root is
     * {@code <workspace>/ontologies/<id>/audit.jsonl}.
     */
    public org.owl4agents.reasoner.write.AuditLog getAuditLog() {
        if (auditLog == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            auditLog = new org.owl4agents.reasoner.write.AuditLog(workspaceBasePath, workspaceName);
        }
        return auditLog;
    }

    /**
     * v0.9.1: Get the shared {@link org.owl4agents.reasoner.write.WriteTransactionService}
     * instance (lazy-initialized). Wired with the shared {@link OntologyCache},
     * a fresh {@link org.owl4agents.reasoner.TemporaryOntologyFactory}, and the
     * shared {@link #getVersionHistoryStore()} / {@link #getAuditLog()}.
     */
    public org.owl4agents.reasoner.write.WriteTransactionService getWriteTransactionService() {
        if (writeTransactionService == null) {
            writeTransactionService = new org.owl4agents.reasoner.write.WriteTransactionService(
                getOntologyCache(), getTemporaryOntologyFactory(),
                getVersionHistoryStore(), getAuditLog());
        }
        return writeTransactionService;
    }

    /**
     * v0.9.1: Get the shared {@link org.owl4agents.reasoner.write.OntologyEditService}
     * instance (lazy-initialized). Wired with the shared
     * {@link #getWriteTransactionService()}, {@link #getCatalogStore()},
     * {@link #getHomeResolver()}, and the workspace id. {@code allowedRootsCsv}
     * is null (defaults to the workspace {@code imports/} subdirectory).
     */
    public org.owl4agents.reasoner.write.OntologyEditService getOntologyEditService() {
        if (ontologyEditService == null) {
            ontologyEditService = new org.owl4agents.reasoner.write.OntologyEditService(
                getWriteTransactionService(), getCatalogStore(), getHomeResolver(),
                getWorkspaceId(), null);
        }
        return ontologyEditService;
    }

    /**
     * Convert an ontology to a Jena Model for SPARQL execution.
     */
    public org.apache.jena.rdf.model.Model createJenaModel(OntologyId ontologyId) throws OWLOntologyCreationException {
        OWLOntology ontology = loadOntology(ontologyId);
        // Convert OWL API ontology to Jena model
        org.apache.jena.rdf.model.Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        // Use OWL API's RDFRenderer to get triples
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        org.semanticweb.owlapi.formats.RDFJsonLDDocumentFormat format = new org.semanticweb.owlapi.formats.RDFJsonLDDocumentFormat();
        try {
            ontology.getOWLOntologyManager().saveOntology(ontology, format, baos);
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
            model.read(bais, null, "JSON-LD");
        } catch (Exception e) {
            // Fallback: try RDF/XML
            try {
                baos.reset();
                org.semanticweb.owlapi.formats.RDFXMLDocumentFormat rdfFormat = new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
                ontology.getOWLOntologyManager().saveOntology(ontology, rdfFormat, baos);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
                model.read(bais, null, "RDF/XML");
            } catch (Exception ex) {
                throw new OWLOntologyCreationException("Failed to convert ontology to Jena model: " + ex.getMessage());
            }
        }
        return model;
    }
}