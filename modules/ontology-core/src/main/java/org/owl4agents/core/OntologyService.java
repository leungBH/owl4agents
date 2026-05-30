package org.owl4agents.core;

import org.owl4agents.core.model.*;
import org.owl4agents.core.evidence.EvidenceMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The shared application service interface for v0.1 owl4agents operations.
 * Both CLI and MCP adapters route through this interface.
 * All operations are readonly except for workspace initialization and ontology import.
 */
public interface OntologyService {

    // ── Workspace operations ──

    /**
     * Initialize a workspace with the given ID.
     * If the workspace already exists, it is left intact and reported as already initialized.
     */
    ServiceResult<Void> initWorkspace(WorkspaceId workspaceId);

    /**
     * Import a local OWL/RDF file into the workspace.
     * The original source file is preserved without overwriting it.
     * The ontology is stored under the given user-provided ontology ID.
     */
    ServiceResult<Void> importOntology(OntologyId ontologyId, Path sourceFilePath);

    /**
     * List all imported ontologies in the workspace catalog.
     */
    ServiceResult<List<CatalogEntry>> listOntologies(WorkspaceId workspaceId);

    // ── Ontology summary ──

    /**
     * Get an ontology summary containing ontology IRI, version IRI, imports,
     * profile information, and entity counts.
     */
    ServiceResult<OntologySummary> getSummary(OntologyId ontologyId);

    // ── Entity search ──

    /**
     * Search ontology entities by text query.
     * Matches by IRI, prefixed name, label, comment, type, and alias index.
     */
    ServiceResult<SearchResult> searchEntities(OntologyId ontologyId, String query);

    // ── Entity context ──

    /**
     * Get readonly class context including subclasses, superclasses,
     * equivalent classes, disjoint classes, and basic restrictions.
     */
    ServiceResult<ClassContext> getClassContext(OntologyId ontologyId, EntityId classIri);

    /**
     * Get readonly object property context including domain, range,
     * inverse properties, and hierarchy.
     */
    ServiceResult<ObjectPropertyContext> getObjectPropertyContext(OntologyId ontologyId, EntityId propertyIri);

    /**
     * Get readonly data property context including domain, range,
     * datatype, and hierarchy.
     */
    ServiceResult<DataPropertyContext> getDataPropertyContext(OntologyId ontologyId, EntityId propertyIri);

    /**
     * Get readonly individual context including explicit types,
     * object property assertions, and data property assertions.
     */
    ServiceResult<IndividualContext> getIndividualContext(OntologyId ontologyId, EntityId individualIri);

    /**
     * Get readonly entity context dispatching to the appropriate
     * class, property, or individual context based on entity type.
     */
    ServiceResult<EntityContext> getEntityContext(OntologyId ontologyId, EntityId entityIri);

    // ── SPARQL ──

    /**
     * Validate a SPARQL query without executing it.
     */
    ServiceResult<ValidationResult> validateSparql(String query);

    /**
     * Execute a readonly SPARQL SELECT query over an imported ontology.
     */
    ServiceResult<SelectResult> executeSelect(OntologyId ontologyId, String query, GraphScope scope);

    /**
     * Execute a readonly SPARQL ASK query over an imported ontology.
     */
    ServiceResult<AskResult> executeAsk(OntologyId ontologyId, String query, GraphScope scope);

    /**
     * Execute a readonly SPARQL CONSTRUCT query over an imported ontology.
     */
    ServiceResult<ConstructResult> executeConstruct(OntologyId ontologyId, String query, GraphScope scope);

    /**
     * Execute a readonly SPARQL DESCRIBE query over an imported ontology.
     */
    ServiceResult<DescribeResult> executeDescribe(OntologyId ontologyId, String query, GraphScope scope);

    // ── QA context ──

    /**
     * Generate ontology-grounded QA context from a question and ontology.
     * Returns matched entities, relevant context, and evidence metadata.
     */
    ServiceResult<QaContext> getQaContext(OntologyId ontologyId, String question,
                                          Optional<Integer> maxEntities, Optional<Integer> maxDepth);
}