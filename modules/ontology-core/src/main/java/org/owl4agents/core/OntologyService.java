package org.owl4agents.core;

import org.owl4agents.core.model.*;
import org.owl4agents.core.evidence.EvidenceMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared application service interface for v0.1 and v0.2 owl4agents operations.
 * Both CLI and MCP adapters route through this interface.
 * All operations are readonly except for workspace initialization, ontology import, and reasoner lifecycle.
 */
public interface OntologyService {

    // ── Workspace operations ──

    ServiceResult<Void> initWorkspace(WorkspaceId workspaceId);

    ServiceResult<Void> importOntology(OntologyId ontologyId, Path sourceFilePath);

    ServiceResult<List<CatalogEntry>> listOntologies(WorkspaceId workspaceId);

    // ── Ontology summary ──

    ServiceResult<OntologySummary> getSummary(OntologyId ontologyId);

    // ── Entity search ──

    ServiceResult<SearchResult> searchEntities(OntologyId ontologyId, String query);

    // ── Entity context (v0.1) ──

    ServiceResult<ClassContext> getClassContext(OntologyId ontologyId, EntityId classIri);

    ServiceResult<ObjectPropertyContext> getObjectPropertyContext(OntologyId ontologyId, EntityId propertyIri);

    ServiceResult<DataPropertyContext> getDataPropertyContext(OntologyId ontologyId, EntityId propertyIri);

    ServiceResult<IndividualContext> getIndividualContext(OntologyId ontologyId, EntityId individualIri);

    ServiceResult<EntityContext> getEntityContext(OntologyId ontologyId, EntityId entityIri);

    // ── SPARQL (v0.1 + v0.2 scope extension) ──

    ServiceResult<ValidationResult> validateSparql(String query);

    ServiceResult<SelectResult> executeSelect(OntologyId ontologyId, String query, GraphScope scope);

    ServiceResult<AskResult> executeAsk(OntologyId ontologyId, String query, GraphScope scope);

    ServiceResult<ConstructResult> executeConstruct(OntologyId ontologyId, String query, GraphScope scope);

    ServiceResult<DescribeResult> executeDescribe(OntologyId ontologyId, String query, GraphScope scope);

    // ── QA context (v0.1) ──

    ServiceResult<QaContext> getQaContext(OntologyId ontologyId, String question,
                                          Optional<Integer> maxEntities, Optional<Integer> maxDepth);

    // ── Reasoner operations (v0.2) ──

    ServiceResult<ReasonerListResult> listReasoners();

    ServiceResult<ReasoningReport> runReasoner(OntologyId ontologyId, Optional<String> reasonerName);

    ServiceResult<ClassificationResult> classify(OntologyId ontologyId, Optional<String> reasonerName);

    ServiceResult<RealizationResult> realize(OntologyId ontologyId, Optional<String> reasonerName);

    ServiceResult<ConsistencyResult> checkConsistency(OntologyId ontologyId, Optional<String> reasonerName);

    ServiceResult<List<String>> getUnsatClasses(OntologyId ontologyId);

    ServiceResult<InconsistencyExplanation> explainInconsistency(OntologyId ontologyId, Optional<String> reasonerName);

    ServiceResult<UnsatClassExplanation> explainUnsatClass(OntologyId ontologyId, String classIRI, Optional<String> reasonerName);

    ServiceResult<ReasoningReport> getReasoningReport(OntologyId ontologyId);

    ServiceResult<InferredFactsResult> getInferredFacts(OntologyId ontologyId, Optional<String> entityIRI);

    ServiceResult<EntailmentResult> checkEntailment(OntologyId ontologyId, String axiomType,
                                                     Map<String, String> parameters, Optional<String> reasonerName);

    // ── Consistency-analysis (v0.2) ──

    ServiceResult<ClassCompatibilityResult> checkClassCompatibility(OntologyId ontologyId, String class1IRI, String class2IRI);

    ServiceResult<MembershipResult> checkIndividualMembership(OntologyId ontologyId, String individualIRI, String classIRI, Optional<String> reasonerName);

    ServiceResult<RelationAssertionResult> checkRelationAssertion(OntologyId ontologyId, String sourceIRI, String propertyIRI, String targetIRI, Optional<String> reasonerName);

    ServiceResult<ScopeDescription> getScope(OntologyId ontologyId);

    // ── Semantic-deepening (v0.2) ──

    ServiceResult<ImportClosureResult> getImportClosure(OntologyId ontologyId);

    ServiceResult<ClassRestrictionsResult> getClassRestrictions(OntologyId ontologyId, String classIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyCharacteristicsResult> getPropertyCharacteristics(OntologyId ontologyId, String propertyIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getEquivalentProperties(OntologyId ontologyId, String propertyIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getDisjointProperties(OntologyId ontologyId, String propertyIRI, Optional<Boolean> includeInferred);

    ServiceResult<DatatypeConstraintsResult> getDatatypeConstraints(OntologyId ontologyId, String datatypeIRI);

    ServiceResult<LiteralValidationResult> validateLiteral(OntologyId ontologyId, String literalValue, String datatypeIRI, Optional<String> propertyIRI);

    ServiceResult<PropertyAxiomsResult> findRelationsBetweenEntities(OntologyId ontologyId, String sourceIRI, String targetIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getObjectPropertyAssertions(OntologyId ontologyId, String individualIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getDataPropertyAssertions(OntologyId ontologyId, String individualIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getSameIndividuals(OntologyId ontologyId, String individualIRI, Optional<Boolean> includeInferred);

    ServiceResult<PropertyAxiomsResult> getDifferentIndividuals(OntologyId ontologyId, String individualIRI, Optional<Boolean> includeInferred);
}