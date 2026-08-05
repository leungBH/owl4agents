package org.owl4agents.reasoner.write;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * v0.9.1 mcp-write-tools-expansion: 8 transactional edit operations.
 *
 * <p>Operates on a {@link WriteTransaction}'s staging ontology (an isolated
 * {@link OWLOntologyManager} copy seeded from the committed state). Every
 * successful edit appends an {@link AuditEntry} via
 * {@link WriteTransactionService#recordEditAudit}; rejected edits append
 * a {@code result=rejected} audit entry.</p>
 *
 * <p>Axiom JSON shape (v0.8.7 ToolCallCandidate-compatible): a JSON object
 * with an {@code axiomType} discriminator and structural fields. Supported
 * types:</p>
 * <ul>
 *   <li>{@code SubClassOf} — {subject (IRI), object (IRI)}</li>
 *   <li>{@code EquivalentClasses} — {classes (list of IRI)}</li>
 *   <li>{@code DisjointClasses} — {classes (list of IRI)}</li>
 *   <li>{@code ClassAssertion} — {individual (IRI), class (IRI)}</li>
 *   <li>{@code ObjectPropertyAssertion} — {subject, property, object (IRIs)}</li>
 *   <li>{@code DataPropertyAssertion} — {subject, property, value (literal)}</li>
 *   <li>{@code SubObjectPropertyOf} — {subject, object (property IRIs)}</li>
 *   <li>{@code DifferentIndividuals} — {individuals (list of IRI)}</li>
 *   <li>{@code Declaration} — {entity (IRI), entityType ("class"|"objectProperty"|"dataProperty"|"individual"|"annotationProperty")}</li>
 *   <li>{@code AnnotationAssertion} — {subject (IRI), property (IRI), value (literal)}</li>
 * </ul>
 *
 * <p>The merge operation loads axioms from a source ontology identified by
 * a registered {@code ontology_id} (resolved via {@link CatalogStore}) or
 * a server-local {@code file_path} subject to the v0.8.7 path traversal
 * protection pattern (reused from OntologyImportToolHandler).</p>
 */
public final class OntologyEditService {

    private final WriteTransactionService transactionService;
    private final CatalogStore catalogStore;
    private final HomeDirectoryResolver homeResolver;
    private final WorkspaceId workspaceId;
    private final List<Path> allowedRoots;
    private final OWLDataFactory dataFactory;

    /**
     * @param transactionService back-reference for audit + transaction lookup
     * @param catalogStore       catalog for merge-by-ontology_id resolution
     * @param homeResolver       home resolver for default allowed roots
     * @param workspaceId        workspace for catalog operations
     * @param allowedRootsCsv    CSV of allowed roots for merge file_path; null/blank
     *                           defaults to {@code <workspace>/imports/}
     */
    public OntologyEditService(WriteTransactionService transactionService,
                               CatalogStore catalogStore,
                               HomeDirectoryResolver homeResolver,
                               WorkspaceId workspaceId,
                               String allowedRootsCsv) {
        this.transactionService = transactionService;
        this.catalogStore = catalogStore;
        this.homeResolver = homeResolver;
        this.workspaceId = workspaceId;
        this.allowedRoots = resolveAllowedRoots(allowedRootsCsv);
        this.dataFactory = OWLManager.getOWLDataFactory();
    }

    /**
     * add_axiom: stage a single axiom onto a transaction.
     */
    public ServiceResult<Map<String, Object>> addAxiom(
            OntologyId ontologyId, String transactionId, Object axiomObj, String author) {
        if (axiomObj == null) {
            reject(ontologyId, transactionId, "add_axiom", author,
                "axiom is required", null, null);
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "axiom is required");
        }
        ServiceResult<OWLAxiom> parsed = parseAxiom(axiomObj);
        if (!parsed.isSuccess()) {
            reject(ontologyId, transactionId, "add_axiom", author,
                "Malformed axiom: " + ((ServiceResult.Error<OWLAxiom>) parsed).error().message(),
                null, axiomObj);
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                ((ServiceResult.Error<OWLAxiom>) parsed).error().message());
        }
        OWLAxiom axiom = ((ServiceResult.Success<OWLAxiom>) parsed).data();

        ServiceResult<WriteTransaction> txResult = transactionService.getOrCreate(transactionId, ontologyId);
        if (!txResult.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<WriteTransaction>) txResult).error());
        }
        WriteTransaction tx = ((ServiceResult.Success<WriteTransaction>) txResult).data();
        tx.touch();
        tx.stagingManager().addAxiom(tx.stagingOntology(), axiom);
        tx.recordStagedOperation("add_axiom", axiomObj, null, axiomObj);
        int staged = tx.stagedOperationCount();

        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            "add_axiom", author, null, axiomObj, null, "ok",
            "staged #" + staged));
        return ServiceResult.success(Map.of(
            "transactionId", transactionId,
            "stagedAxiomCount", staged,
            "stagedOperationIndex", staged
        ), org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * remove_axiom: remove a single axiom from a transaction's staging ontology.
     * Returns AXIOM_NOT_FOUND if the axiom is not present.
     */
    public ServiceResult<Map<String, Object>> removeAxiom(
            OntologyId ontologyId, String transactionId, Object axiomObj, String author) {
        if (axiomObj == null) {
            reject(ontologyId, transactionId, "remove_axiom", author,
                "axiom is required", null, null);
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "axiom is required");
        }
        ServiceResult<OWLAxiom> parsed = parseAxiom(axiomObj);
        if (!parsed.isSuccess()) {
            reject(ontologyId, transactionId, "remove_axiom", author,
                "Malformed axiom: " + ((ServiceResult.Error<OWLAxiom>) parsed).error().message(),
                null, axiomObj);
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                ((ServiceResult.Error<OWLAxiom>) parsed).error().message());
        }
        OWLAxiom axiom = ((ServiceResult.Success<OWLAxiom>) parsed).data();

        WriteTransaction tx = transactionService.getTransaction(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }
        tx.touch();
        if (!tx.stagingOntology().containsAxiom(axiom)) {
            reject(ontologyId, transactionId, "remove_axiom", author,
                "Axiom not present in staging ontology", axiomObj, null);
            return ServiceResult.error(ErrorCode.AXIOM_NOT_FOUND,
                "Axiom not present in staging ontology");
        }
        tx.stagingManager().removeAxiom(tx.stagingOntology(), axiom);
        tx.recordStagedOperation("remove_axiom", axiomObj, axiomObj, null);
        int staged = tx.stagedOperationCount();
        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            "remove_axiom", author, axiomObj, null, null, "ok",
            "staged #" + staged));
        return ServiceResult.success(Map.of(
            "transactionId", transactionId,
            "stagedAxiomCount", staged,
            "stagedOperationIndex", staged
        ), org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * edit_entity: apply annotation edits (label, comment, annotations map)
     * to an entity in the staging ontology. Returns ENTITY_NOT_FOUND if the
     * entity is not declared, INVALID_EDIT_ARGUMENTS if no edit field is set.
     */
    public ServiceResult<Map<String, Object>> editEntity(
            OntologyId ontologyId, String transactionId, String entityIri,
            String label, String comment, Object annotationsObj, String author) {
        if (label == null && comment == null && annotationsObj == null) {
            reject(ontologyId, transactionId, "edit_entity", author,
                "At least one of label / comment / annotations must be provided",
                null, null);
            return ServiceResult.error(ErrorCode.INVALID_EDIT_ARGUMENTS);
        }
        if (entityIri == null || entityIri.isBlank()) {
            reject(ontologyId, transactionId, "edit_entity", author,
                "entity IRI is required", null, null);
            return ServiceResult.error(ErrorCode.INVALID_EDIT_ARGUMENTS,
                "entity IRI is required");
        }
        WriteTransaction tx = transactionService.getTransaction(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }
        tx.touch();
        IRI iri = IRI.create(entityIri);
        // Verify entity exists in staging ontology signature.
        if (!tx.stagingOntology().containsEntityInSignature(iri)) {
            reject(ontologyId, transactionId, "edit_entity", author,
                "Entity not in staging ontology: " + entityIri, null, null);
            return ServiceResult.error(ErrorCode.ENTITY_NOT_FOUND,
                "Entity not in staging ontology: " + entityIri);
        }
        OWLEntity entity = resolveEntity(tx.stagingOntology(), iri);
        if (entity == null) {
            reject(ontologyId, transactionId, "edit_entity", author,
                "Entity not in staging ontology: " + entityIri, null, null);
            return ServiceResult.error(ErrorCode.ENTITY_NOT_FOUND,
                "Entity not in staging ontology: " + entityIri);
        }
        Map<String, Object> before = collectAnnotations(tx.stagingOntology(), entity);
        List<OWLAnnotationAssertionAxiom> toRemove = new ArrayList<>(tx.stagingOntology()
            .getAnnotationAssertionAxioms(entity.getIRI()));
        for (OWLAnnotationAssertionAxiom a : toRemove) {
            OWLAnnotationProperty p = a.getProperty();
            if (p.isLabel() || p.isComment() || isCustomAnnotation(p)) {
                tx.stagingManager().removeAxiom(tx.stagingOntology(), a);
            }
        }
        List<OWLAnnotation> newAnnotations = new ArrayList<>();
        if (label != null) {
            OWLAnnotation ann = dataFactory.getOWLAnnotation(
                dataFactory.getRDFSLabel(), dataFactory.getOWLLiteral(label));
            newAnnotations.add(ann);
            OWLAnnotationAssertionAxiom ax = dataFactory.getOWLAnnotationAssertionAxiom(
                dataFactory.getRDFSLabel(), entity.getIRI(), dataFactory.getOWLLiteral(label));
            tx.stagingManager().addAxiom(tx.stagingOntology(), ax);
        }
        if (comment != null) {
            OWLAnnotationAssertionAxiom ax = dataFactory.getOWLAnnotationAssertionAxiom(
                dataFactory.getRDFSComment(), entity.getIRI(), dataFactory.getOWLLiteral(comment));
            tx.stagingManager().addAxiom(tx.stagingOntology(), ax);
        }
        if (annotationsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> annMap = (Map<String, Object>) annotationsObj;
            for (Map.Entry<String, Object> e : annMap.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                OWLAnnotationProperty p = dataFactory.getOWLAnnotationProperty(IRI.create(e.getKey()));
                OWLLiteral lit = toLiteral(e.getValue());
                if (lit == null) continue;
                OWLAnnotationAssertionAxiom ax = dataFactory.getOWLAnnotationAssertionAxiom(
                    p, entity.getIRI(), lit);
                tx.stagingManager().addAxiom(tx.stagingOntology(), ax);
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        if (label != null) after.put("label", label);
        if (comment != null) after.put("comment", comment);
        if (annotationsObj instanceof Map) after.put("annotations", annotationsObj);

        tx.recordStagedOperation("edit_entity", entityIri, before, after);
        int staged = tx.stagedOperationCount();
        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            "edit_entity", author, before, after, null, "ok",
            "entity=" + entityIri + " staged #" + staged));
        return ServiceResult.success(Map.of(
            "transactionId", transactionId,
            "entity", entityIri,
            "stagedAxiomCount", staged,
            "stagedOperationIndex", staged
        ), org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * create_class: declare a new class with optional superclasses.
     * Returns CLASS_ALREADY_EXISTS if the class IRI is already in the staging ontology.
     */
    public ServiceResult<Map<String, Object>> createClass(
            OntologyId ontologyId, String transactionId, String classIri,
            Object superObj, String author) {
        if (classIri == null || classIri.isBlank()) {
            reject(ontologyId, transactionId, "create_class", author,
                "class IRI is required", null, null);
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "class IRI is required");
        }
        WriteTransaction tx = transactionService.getTransaction(transactionId);
        if (tx == null) {
            return ServiceResult.error(ErrorCode.TRANSACTION_NOT_FOUND);
        }
        tx.touch();
        IRI iri = IRI.create(classIri);
        OWLClass cls = dataFactory.getOWLClass(iri);
        if (tx.stagingOntology().containsEntityInSignature(iri)) {
            reject(ontologyId, transactionId, "create_class", author,
                "Class already exists: " + classIri, null, null);
            return ServiceResult.error(ErrorCode.CLASS_ALREADY_EXISTS,
                "Class already exists: " + classIri);
        }
        OWLDeclarationAxiom decl = dataFactory.getOWLDeclarationAxiom(cls);
        tx.stagingManager().addAxiom(tx.stagingOntology(), decl);

        List<String> supers = new ArrayList<>();
        if (superObj instanceof List) {
            for (Object o : (List<?>) superObj) {
                if (o != null) supers.add(o.toString());
            }
        }
        if (supers.isEmpty()) {
            OWLSubClassOfAxiom sc = dataFactory.getOWLSubClassOfAxiom(
                cls, dataFactory.getOWLThing());
            tx.stagingManager().addAxiom(tx.stagingOntology(), sc);
        } else {
            for (String s : supers) {
                OWLClass sup = dataFactory.getOWLClass(IRI.create(s));
                OWLSubClassOfAxiom sc = dataFactory.getOWLSubClassOfAxiom(cls, sup);
                tx.stagingManager().addAxiom(tx.stagingOntology(), sc);
            }
        }
        tx.recordStagedOperation("create_class", classIri, null, Map.of(
            "class", classIri, "super", supers));
        int staged = tx.stagedOperationCount();
        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            "create_class", author, null,
            Map.of("class", classIri, "super", supers), null, "ok",
            "staged #" + staged));
        return ServiceResult.success(Map.of(
            "transactionId", transactionId,
            "class", classIri,
            "stagedAxiomCount", staged,
            "stagedOperationIndex", staged
        ), org.owl4agents.core.ResultMetadata.empty());
    }

    /**
     * merge: stage all axioms from a source ontology into a target transaction.
     * Source may be a registered ontology_id (resolved via catalog) or a
     * server-local file_path subject to path traversal protection.
     */
    public ServiceResult<Map<String, Object>> merge(
            OntologyId ontologyId, String transactionId, String source, String author) {
        if (source == null || source.isBlank()) {
            reject(ontologyId, transactionId, "merge", author,
                "source is required", null, null);
            return ServiceResult.error(ErrorCode.MERGE_SOURCE_INVALID,
                "source is required");
        }
        // Resolve source: try as registered ontology_id first, then as file_path.
        OWLOntology sourceOntology;
        String sourceKind;
        try {
            Optional<OWLOntology> registered = loadRegisteredOntology(source);
            if (registered.isPresent()) {
                sourceOntology = registered.get();
                sourceKind = "ontology_id";
            } else {
                Optional<OWLOntology> fromFile = loadFromFile(source, ontologyId, transactionId, author);
                if (fromFile.isEmpty()) {
                    // loadFromFile already audited a rejected entry.
                    return ServiceResult.error(ErrorCode.MERGE_SOURCE_INVALID,
                        "Source is neither a registered ontology_id nor a valid file_path: " + source);
                }
                sourceOntology = fromFile.get();
                sourceKind = "file_path";
            }
        } catch (Exception e) {
            reject(ontologyId, transactionId, "merge", author,
                "Failed to load source: " + e.getMessage(), null, null);
            return ServiceResult.error(ErrorCode.MERGE_SOURCE_INVALID,
                "Failed to load source: " + e.getMessage());
        }

        ServiceResult<WriteTransaction> txResult = transactionService.getOrCreate(transactionId, ontologyId);
        if (!txResult.isSuccess()) {
            return ServiceResult.error(((ServiceResult.Error<WriteTransaction>) txResult).error());
        }
        WriteTransaction tx = ((ServiceResult.Success<WriteTransaction>) txResult).data();
        tx.touch();

        // Stage every axiom from source into target staging ontology.
        // Use addAxioms(Collection) so the manager fires a single batch event.
        Set<OWLAxiom> axioms = sourceOntology.getAxioms();
        tx.stagingManager().addAxioms(tx.stagingOntology(), axioms);
        tx.recordStagedOperation("merge", source, null, Map.of(
            "source", source, "sourceKind", sourceKind, "axiomCount", axioms.size()));
        int staged = tx.stagedOperationCount();
        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            "merge", author, null,
            Map.of("source", source, "sourceKind", sourceKind, "axiomCount", axioms.size()),
            null, "ok", "staged #" + staged));
        return ServiceResult.success(Map.of(
            "transactionId", transactionId,
            "source", source,
            "sourceKind", sourceKind,
            "mergedAxiomCount", axioms.size(),
            "stagedAxiomCount", staged,
            "stagedOperationIndex", staged
        ), org.owl4agents.core.ResultMetadata.empty());
    }

    // ── Axiom JSON parsing ──

    /**
     * Parse an axiom JSON object into an {@link OWLAxiom}.
     * Recognizes axiomType discriminator and structural fields.
     */
    @SuppressWarnings("unchecked")
    public ServiceResult<OWLAxiom> parseAxiom(Object axiomObj) {
        if (!(axiomObj instanceof Map)) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "axiom must be a JSON object");
        }
        Map<String, Object> map = (Map<String, Object>) axiomObj;
        Object typeObj = map.get("axiomType");
        if (typeObj == null) {
            // Also accept "type" as alias for ToolCallCandidate compatibility.
            typeObj = map.get("type");
        }
        if (!(typeObj instanceof String)) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "axiom is missing axiomType (string)");
        }
        String axiomType = (String) typeObj;
        try {
            OWLAxiom axiom = switch (axiomType) {
                case "SubClassOf" -> parseSubClassOf(map);
                case "EquivalentClasses" -> parseEquivalentClasses(map);
                case "DisjointClasses" -> parseDisjointClasses(map);
                case "ClassAssertion" -> parseClassAssertion(map);
                case "ObjectPropertyAssertion" -> parseObjectPropertyAssertion(map);
                case "DataPropertyAssertion" -> parseDataPropertyAssertion(map);
                case "SubObjectPropertyOf" -> parseSubObjectPropertyOf(map);
                case "DifferentIndividuals" -> parseDifferentIndividuals(map);
                case "Declaration" -> parseDeclaration(map);
                case "AnnotationAssertion" -> parseAnnotationAssertion(map);
                default -> null;
            };
            if (axiom == null) {
                return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                    "Unsupported axiomType: " + axiomType);
            }
            return ServiceResult.success(axiom, org.owl4agents.core.ResultMetadata.empty());
        } catch (Exception e) {
            return ServiceResult.error(ErrorCode.INVALID_AXIOM_ARGUMENTS,
                "Failed to parse axiom " + axiomType + ": " + e.getMessage());
        }
    }

    private OWLAxiom parseSubClassOf(Map<String, Object> m) {
        String subj = str(m, "subject");
        String obj = str(m, "object");
        if (subj == null || obj == null) {
            throw new IllegalArgumentException("SubClassOf requires 'subject' and 'object' IRIs");
        }
        return dataFactory.getOWLSubClassOfAxiom(
            dataFactory.getOWLClass(IRI.create(subj)),
            dataFactory.getOWLClass(IRI.create(obj)));
    }

    private OWLAxiom parseEquivalentClasses(Map<String, Object> m) {
        List<String> classes = strList(m, "classes");
        if (classes.size() < 2) {
            throw new IllegalArgumentException("EquivalentClasses requires 'classes' list with >=2 IRIs");
        }
        Set<OWLClass> set = new java.util.LinkedHashSet<>();
        for (String c : classes) set.add(dataFactory.getOWLClass(IRI.create(c)));
        return dataFactory.getOWLEquivalentClassesAxiom(set);
    }

    private OWLAxiom parseDisjointClasses(Map<String, Object> m) {
        List<String> classes = strList(m, "classes");
        if (classes.size() < 2) {
            throw new IllegalArgumentException("DisjointClasses requires 'classes' list with >=2 IRIs");
        }
        Set<OWLClass> set = new java.util.LinkedHashSet<>();
        for (String c : classes) set.add(dataFactory.getOWLClass(IRI.create(c)));
        return dataFactory.getOWLDisjointClassesAxiom(set);
    }

    private OWLAxiom parseClassAssertion(Map<String, Object> m) {
        String ind = str(m, "individual");
        String cls = str(m, "class");
        if (ind == null || cls == null) {
            throw new IllegalArgumentException("ClassAssertion requires 'individual' and 'class' IRIs");
        }
        return dataFactory.getOWLClassAssertionAxiom(
            dataFactory.getOWLClass(IRI.create(cls)),
            dataFactory.getOWLNamedIndividual(IRI.create(ind)));
    }

    private OWLAxiom parseObjectPropertyAssertion(Map<String, Object> m) {
        String subj = str(m, "subject");
        String prop = str(m, "property");
        String obj = str(m, "object");
        if (subj == null || prop == null || obj == null) {
            throw new IllegalArgumentException("ObjectPropertyAssertion requires 'subject', 'property', 'object' IRIs");
        }
        return dataFactory.getOWLObjectPropertyAssertionAxiom(
            dataFactory.getOWLObjectProperty(IRI.create(prop)),
            dataFactory.getOWLNamedIndividual(IRI.create(subj)),
            dataFactory.getOWLNamedIndividual(IRI.create(obj)));
    }

    private OWLAxiom parseDataPropertyAssertion(Map<String, Object> m) {
        String subj = str(m, "subject");
        String prop = str(m, "property");
        Object value = m.get("value");
        if (subj == null || prop == null || value == null) {
            throw new IllegalArgumentException("DataPropertyAssertion requires 'subject', 'property', 'value'");
        }
        OWLLiteral lit = toLiteral(value);
        if (lit == null) {
            throw new IllegalArgumentException("DataPropertyAssertion value must be string/number/boolean");
        }
        return dataFactory.getOWLDataPropertyAssertionAxiom(
            dataFactory.getOWLDataProperty(IRI.create(prop)),
            dataFactory.getOWLNamedIndividual(IRI.create(subj)),
            lit);
    }

    private OWLAxiom parseSubObjectPropertyOf(Map<String, Object> m) {
        String subj = str(m, "subject");
        String obj = str(m, "object");
        if (subj == null || obj == null) {
            throw new IllegalArgumentException("SubObjectPropertyOf requires 'subject' and 'object' IRIs");
        }
        return dataFactory.getOWLSubObjectPropertyOfAxiom(
            dataFactory.getOWLObjectProperty(IRI.create(subj)),
            dataFactory.getOWLObjectProperty(IRI.create(obj)));
    }

    private OWLAxiom parseDifferentIndividuals(Map<String, Object> m) {
        List<String> inds = strList(m, "individuals");
        if (inds.size() < 2) {
            throw new IllegalArgumentException("DifferentIndividuals requires 'individuals' list with >=2 IRIs");
        }
        Set<OWLIndividual> set = new java.util.LinkedHashSet<>();
        for (String i : inds) set.add(dataFactory.getOWLNamedIndividual(IRI.create(i)));
        return dataFactory.getOWLDifferentIndividualsAxiom(set);
    }

    private OWLAxiom parseDeclaration(Map<String, Object> m) {
        String entityIri = str(m, "entity");
        String entityType = str(m, "entityType");
        if (entityIri == null || entityType == null) {
            throw new IllegalArgumentException("Declaration requires 'entity' IRI and 'entityType'");
        }
        IRI iri = IRI.create(entityIri);
        return switch (entityType) {
            case "class" -> dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(iri));
            case "objectProperty" -> dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLObjectProperty(iri));
            case "dataProperty" -> dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLDataProperty(iri));
            case "individual" -> dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLNamedIndividual(iri));
            case "annotationProperty" -> dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLAnnotationProperty(iri));
            default -> throw new IllegalArgumentException("Unsupported entityType: " + entityType);
        };
    }

    private OWLAxiom parseAnnotationAssertion(Map<String, Object> m) {
        String subj = str(m, "subject");
        String prop = str(m, "property");
        Object value = m.get("value");
        if (subj == null || prop == null || value == null) {
            throw new IllegalArgumentException("AnnotationAssertion requires 'subject', 'property', 'value'");
        }
        OWLLiteral lit = toLiteral(value);
        if (lit == null) {
            throw new IllegalArgumentException("AnnotationAssertion value must be string/number/boolean");
        }
        return dataFactory.getOWLAnnotationAssertionAxiom(
            dataFactory.getOWLAnnotationProperty(IRI.create(prop)),
            IRI.create(subj),
            lit);
    }

    // ── Helpers ──

    private void reject(OntologyId ontologyId, String transactionId, String op,
                        String author, String reason, Object before, Object after) {
        transactionService.recordEditAudit(ontologyId, new AuditEntry(
            null, Instant.now(), ontologyId.id(), transactionId,
            op, author, before, after, null, "rejected", reason));
    }

    private Optional<OWLOntology> loadRegisteredOntology(String ontologyIdStr) {
        try {
            OntologyId srcId = new OntologyId(ontologyIdStr);
            ServiceResult<CatalogEntry> entry = catalogStore.findEntry(workspaceId, srcId);
            if (!entry.isSuccess()) return Optional.empty();
            CatalogEntry cat = ((ServiceResult.Success<CatalogEntry>) entry).data();
            if (!Files.exists(cat.canonicalPath())) return Optional.empty();
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            return Optional.of(m.loadOntologyFromOntologyDocument(cat.canonicalPath().toFile()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<OWLOntology> loadFromFile(String filePathStr,
                                                OntologyId ontologyId,
                                                String transactionId,
                                                String author) throws IOException {
        Path requested = Path.of(filePathStr);
        Path resolved;
        try {
            resolved = requested.toRealPath();
        } catch (IOException realPathEx) {
            try {
                resolved = requested.normalize().toAbsolutePath();
            } catch (Exception normEx) {
                reject(ontologyId, transactionId, "merge", author,
                    "Could not resolve file_path: " + filePathStr, null, null);
                return Optional.empty();
            }
        }
        if (resolved.toString().contains("..")) {
            reject(ontologyId, transactionId, "merge", author,
                "file_path contains '..' components after normalization: " + filePathStr,
                null, null);
            return Optional.empty();
        }
        if (!isInsideAllowedRoots(resolved)) {
            reject(ontologyId, transactionId, "merge", author,
                "file_path resolves outside the configured allowed roots: " + resolved,
                null, null);
            return Optional.empty();
        }
        if (!Files.exists(resolved)) {
            reject(ontologyId, transactionId, "merge", author,
                "Source file does not exist: " + resolved, null, null);
            return Optional.empty();
        }
        try {
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            return Optional.of(m.loadOntologyFromOntologyDocument(resolved.toFile()));
        } catch (Exception e) {
            reject(ontologyId, transactionId, "merge", author,
                "Failed to load source ontology: " + e.getMessage(), null, null);
            return Optional.empty();
        }
    }

    private List<Path> resolveAllowedRoots(String csv) {
        if (csv == null || csv.isBlank()) {
            Path defaults = homeResolver.resolveWorkspaceDirectory(workspaceId)
                .resolve("imports");
            return List.of(defaults);
        }
        List<Path> roots = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                roots.add(Path.of(trimmed).normalize().toAbsolutePath());
            }
        }
        return roots.isEmpty()
            ? List.of(homeResolver.resolveWorkspaceDirectory(workspaceId).resolve("imports"))
            : roots;
    }

    private boolean isInsideAllowedRoots(Path candidate) {
        Path normalized = candidate.normalize().toAbsolutePath();
        for (Path root : allowedRoots) {
            Path normalizedRoot = root.normalize().toAbsolutePath();
            if (normalized.startsWith(normalizedRoot)) {
                return true;
            }
        }
        return false;
    }

    private OWLEntity resolveEntity(OWLOntology ont, IRI iri) {
        if (ont.containsClassInSignature(iri)) return dataFactory.getOWLClass(iri);
        if (ont.containsObjectPropertyInSignature(iri)) return dataFactory.getOWLObjectProperty(iri);
        if (ont.containsDataPropertyInSignature(iri)) return dataFactory.getOWLDataProperty(iri);
        if (ont.containsIndividualInSignature(iri)) return dataFactory.getOWLNamedIndividual(iri);
        if (ont.containsAnnotationPropertyInSignature(iri)) return dataFactory.getOWLAnnotationProperty(iri);
        if (ont.containsDatatypeInSignature(iri)) return dataFactory.getOWLDatatype(iri);
        return null;
    }

    private Map<String, Object> collectAnnotations(OWLOntology ont, OWLEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (OWLAnnotationAssertionAxiom ax : ont.getAnnotationAssertionAxioms(entity.getIRI())) {
            String prop = ax.getProperty().getIRI().toString();
            Object value = ax.getValue() instanceof OWLLiteral lit ? lit.getLiteral() : ax.getValue().toString();
            out.put(prop, value);
        }
        return out;
    }

    private boolean isCustomAnnotation(OWLAnnotationProperty p) {
        return !p.isLabel() && !p.isComment() && !p.getIRI().toString().equals("http://www.w3.org/2004/02/skos/core#prefLabel");
    }

    private OWLLiteral toLiteral(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return dataFactory.getOWLLiteral(s);
        if (value instanceof Boolean b) return dataFactory.getOWLLiteral(b);
        if (value instanceof Integer i) return dataFactory.getOWLLiteral(i);
        if (value instanceof Long l) return dataFactory.getOWLLiteral(l);
        if (value instanceof Double d) return dataFactory.getOWLLiteral(d);
        if (value instanceof Float f) return dataFactory.getOWLLiteral(f);
        if (value instanceof Number n) return dataFactory.getOWLLiteral(n.doubleValue());
        return dataFactory.getOWLLiteral(value.toString());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }
}
