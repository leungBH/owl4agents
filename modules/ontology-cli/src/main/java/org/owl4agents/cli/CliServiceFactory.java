package org.owl4agents.cli;

import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.*;
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
import org.owl4agents.validation.EvidenceGroundingService;

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
    private ClaimVerificationService claimVerificationService;
    private EvidenceGroundingService evidenceGroundingService;

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
    public ReasonerServiceImpl getReasonerService() {
        if (reasonerService == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            reasonerService = new ReasonerServiceImpl(getCatalogStore(), workspaceBasePath, workspaceName);
        }
        return reasonerService;
    }

    /**
     * Get the semantic deepening service instance.
     * Same path resolution logic as ReasonerServiceImpl.
     */
    public SemanticDeepeningService getSemanticDeepeningService() {
        if (semanticDeepeningService == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            semanticDeepeningService = new SemanticDeepeningService(workspaceBasePath);
        }
        return semanticDeepeningService;
    }

    /**
     * Get the consistency analysis service instance.
     * Same path resolution logic as ReasonerServiceImpl.
     */
    public ConsistencyAnalysisService getConsistencyAnalysisService() {
        if (consistencyAnalysisService == null) {
            String workspaceBasePath = getHomeResolver().resolveHomeDirectory()
                .resolve("workspaces").toString();
            ReasonerLifecycleManager lifecycleManager = getReasonerService().getLifecycleManager();
            consistencyAnalysisService = new ConsistencyAnalysisService(lifecycleManager, workspaceBasePath);
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