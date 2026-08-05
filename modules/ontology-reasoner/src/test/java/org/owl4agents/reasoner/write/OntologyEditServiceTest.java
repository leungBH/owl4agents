package org.owl4agents.reasoner.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.CatalogEntry;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.9.1 mcp-write-tools-expansion D3: OntologyEditService unit tests.
 *
 * <p>Covers axiom JSON parsing (10 supported types + 3 error cases),
 * addAxiom / removeAxiom staging, editEntity annotation edits,
 * createClass with optional superclasses, and merge from a registered
 * ontology_id or a server-local file_path (including traversal protection).</p>
 */
@DisplayName("v0.9.1 OntologyEditService (D3)")
class OntologyEditServiceTest {

    private static final String WS_NAME = "default";
    private static final String NS = "http://owl4agents.org/test/oes#";

    private OWLOntology buildBaseOntology() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS));
            OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
            OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
            ont.addAxiom(df.getOWLDeclarationAxiom(animal));
            ont.addAxiom(df.getOWLDeclarationAxiom(dog));
            ont.addAxiom(df.getOWLSubClassOfAxiom(dog, animal));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeCanonical(Path tmp, String ontologyId, OWLOntology ont) throws Exception {
        Path canonical = tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve(ontologyId).resolve("canonical").resolve("ontology.owl");
        Files.createDirectories(canonical.getParent());
        try (var out = Files.newOutputStream(canonical)) {
            ont.getOWLOntologyManager().saveOntology(ont, new OWLXMLDocumentFormat(), out);
        }
    }

    private record Services(
        OntologyCache cache,
        TemporaryOntologyFactory tempFactory,
        VersionHistoryStore versionHistoryStore,
        AuditLog auditLog,
        WriteTransactionService transactionService,
        OntologyEditService editService,
        CatalogStore catalogStore,
        HomeDirectoryResolver homeResolver
    ) {}

    private Services newServices(Path tmp) {
        OntologyCache cache = new OntologyCache(tmp.toString(), WS_NAME, 0);
        TemporaryOntologyFactory tempFactory = new TemporaryOntologyFactory();
        VersionHistoryStore vhs = new VersionHistoryStore(tmp.toString(), WS_NAME);
        AuditLog auditLog = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        WriteTransactionService txService = new WriteTransactionService(cache, tempFactory, vhs, auditLog);
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tmp);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        OntologyEditService editService = new OntologyEditService(
            txService, catalogStore, homeResolver, WorkspaceId.DEFAULT, tmp.toString());
        return new Services(cache, tempFactory, vhs, auditLog, txService, editService, catalogStore, homeResolver);
    }

    private Map<String, Object> axiomMap(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axiomType", type);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ── parseAxiom: 10 supported types ──

    @Test
    @DisplayName("parseAxiom SubClassOf success")
    void parseAxiomSubClassOf(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"));
        assertTrue(r.isSuccess());
        OWLAxiom axiom = ((ServiceResult.Success<OWLAxiom>) r).data();
        assertInstanceOf(OWLSubClassOfAxiom.class, axiom);
    }

    @Test
    @DisplayName("parseAxiom ClassAssertion success")
    void parseAxiomClassAssertion(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("ClassAssertion", "individual", NS + "felix", "class", NS + "Cat"));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLClassAssertionAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom Declaration success")
    void parseAxiomDeclaration(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("Declaration", "entity", NS + "Bird", "entityType", "class"));
        assertTrue(r.isSuccess());
        assertInstanceOf(OWLDeclarationAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom ObjectPropertyAssertion success")
    void parseAxiomObjectPropertyAssertion(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("ObjectPropertyAssertion",
                "subject", NS + "felix", "property", NS + "chases", "object", NS + "mouse"));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom DataPropertyAssertion success")
    void parseAxiomDataPropertyAssertion(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("DataPropertyAssertion",
                "subject", NS + "felix", "property", NS + "age", "value", 5));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom AnnotationAssertion success")
    void parseAxiomAnnotationAssertion(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("AnnotationAssertion",
                "subject", NS + "Cat", "property", NS + "label", "value", "A cat"));
        assertTrue(r.isSuccess());
        assertInstanceOf(OWLAnnotationAssertionAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom EquivalentClasses success")
    void parseAxiomEquivalentClasses(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("EquivalentClasses", "classes", List.of(NS + "Cat", NS + "Feline")));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom DisjointClasses success")
    void parseAxiomDisjointClasses(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("DisjointClasses", "classes", List.of(NS + "Cat", NS + "Dog")));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLDisjointClassesAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom SubObjectPropertyOf success")
    void parseAxiomSubObjectPropertyOf(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("SubObjectPropertyOf",
                "subject", NS + "chases", "object", NS + "interactsWith"));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    @Test
    @DisplayName("parseAxiom DifferentIndividuals success")
    void parseAxiomDifferentIndividuals(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("DifferentIndividuals", "individuals", List.of(NS + "felix", NS + "rex")));
        assertTrue(r.isSuccess());
        assertInstanceOf(org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom.class,
            ((ServiceResult.Success<OWLAxiom>) r).data());
    }

    // ── parseAxiom: error cases ──

    @Test
    @DisplayName("parseAxiom null axiom returns INVALID_AXIOM_ARGUMENTS")
    void parseAxiomNull(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(null);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_AXIOM_ARGUMENTS,
            ((ServiceResult.Error<OWLAxiom>) r).error().code());
    }

    @Test
    @DisplayName("parseAxiom missing axiomType returns INVALID_AXIOM_ARGUMENTS")
    void parseAxiomMissingType(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            Map.of("subject", NS + "Cat", "object", NS + "Animal"));
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_AXIOM_ARGUMENTS,
            ((ServiceResult.Error<OWLAxiom>) r).error().code());
    }

    @Test
    @DisplayName("parseAxiom unsupported axiomType returns INVALID_AXIOM_ARGUMENTS")
    void parseAxiomUnsupportedType(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        ServiceResult<OWLAxiom> r = svc.editService().parseAxiom(
            axiomMap("Foo", "subject", NS + "Cat"));
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_AXIOM_ARGUMENTS,
            ((ServiceResult.Error<OWLAxiom>) r).error().code());
    }

    // ── addAxiom ──

    @Test
    @DisplayName("addAxiom success: lazy tx creation, axiom staged, audit ok")
    void addAxiomSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-add");
        writeCanonical(tmp, "ont-add", buildBaseOntology());
        Services svc = newServices(tmp);
        Map<String, Object> axiomMap = axiomMap("SubClassOf",
            "subject", NS + "Cat", "object", NS + "Animal");

        ServiceResult<Map<String, Object>> r =
            svc.editService().addAxiom(oid, "tx-add", axiomMap, "alice");
        assertTrue(r.isSuccess());
        Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) r).data();
        assertEquals("tx-add", data.get("transactionId"));
        assertEquals(1, data.get("stagedAxiomCount"));

        WriteTransaction tx = svc.transactionService().getTransaction("tx-add");
        assertNotNull(tx);
        ServiceResult<OWLAxiom> parsed = svc.editService().parseAxiom(axiomMap);
        assertTrue(tx.stagingOntology().containsAxiom(
            ((ServiceResult.Success<OWLAxiom>) parsed).data()),
            "staging ontology must contain the staged axiom");

        List<AuditEntry> entries = svc.auditLog().query(oid, null, null, "add_axiom", "tx-add");
        assertFalse(entries.isEmpty(), "audit log must contain an add_axiom entry");
        assertEquals("ok", entries.get(0).result());
    }

    @Test
    @DisplayName("addAxiom null axiom returns INVALID_AXIOM_ARGUMENTS with rejected audit")
    void addAxiomNullAxiom(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-add-null");
        writeCanonical(tmp, "ont-add-null", buildBaseOntology());
        Services svc = newServices(tmp);

        ServiceResult<Map<String, Object>> r =
            svc.editService().addAxiom(oid, "tx-add-null", null, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_AXIOM_ARGUMENTS,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());

        List<AuditEntry> entries = svc.auditLog().query(oid, null, null, "add_axiom", "tx-add-null");
        assertFalse(entries.isEmpty(), "audit log must contain a rejected add_axiom entry");
        assertEquals("rejected", entries.get(0).result());
    }

    // ── removeAxiom ──

    @Test
    @DisplayName("removeAxiom success: axiom removed from staging, count incremented")
    void removeAxiomSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rm");
        writeCanonical(tmp, "ont-rm", buildBaseOntology());
        Services svc = newServices(tmp);
        Map<String, Object> axiomMap = axiomMap("SubClassOf",
            "subject", NS + "Cat", "object", NS + "Animal");

        svc.editService().addAxiom(oid, "tx-rm", axiomMap, "alice");
        ServiceResult<Map<String, Object>> r =
            svc.editService().removeAxiom(oid, "tx-rm", axiomMap, "alice");
        assertTrue(r.isSuccess());
        Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) r).data();
        assertEquals(2, data.get("stagedAxiomCount"),
            "removeAxiom must increment the staged operation count");

        WriteTransaction tx = svc.transactionService().getTransaction("tx-rm");
        assertNotNull(tx);
        ServiceResult<OWLAxiom> parsed = svc.editService().parseAxiom(axiomMap);
        assertFalse(tx.stagingOntology().containsAxiom(
            ((ServiceResult.Success<OWLAxiom>) parsed).data()),
            "staging ontology must NOT contain the removed axiom");
    }

    @Test
    @DisplayName("removeAxiom AXIOM_NOT_FOUND when axiom not present in staging")
    void removeAxiomNotFound(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rm-nf");
        writeCanonical(tmp, "ont-rm-nf", buildBaseOntology());
        Services svc = newServices(tmp);
        // Seed a transaction with a different axiom
        svc.editService().addAxiom(oid, "tx-rm-nf",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        // Try to remove an axiom that was never staged
        Map<String, Object> absent = axiomMap("SubClassOf",
            "subject", NS + "Bird", "object", NS + "Animal");
        ServiceResult<Map<String, Object>> r =
            svc.editService().removeAxiom(oid, "tx-rm-nf", absent, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.AXIOM_NOT_FOUND,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());

        List<AuditEntry> entries = svc.auditLog().query(oid, null, null, "remove_axiom", "tx-rm-nf");
        assertFalse(entries.isEmpty());
        assertEquals("rejected", entries.get(0).result());
    }

    // ── editEntity ──

    @Test
    @DisplayName("editEntity label success: RDFSLabel annotation assertion added")
    void editEntityLabelSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-edit");
        writeCanonical(tmp, "ont-edit", buildBaseOntology());
        Services svc = newServices(tmp);
        // Seed the entity in signature via a Declaration axiom
        svc.editService().addAxiom(oid, "tx-edit",
            axiomMap("Declaration", "entity", NS + "Cat", "entityType", "class"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().editEntity(
            oid, "tx-edit", NS + "Cat", "Feline", null, null, "alice");
        assertTrue(r.isSuccess());

        WriteTransaction tx = svc.transactionService().getTransaction("tx-edit");
        assertNotNull(tx);
        boolean hasLabel = tx.stagingOntology().getAnnotationAssertionAxioms(IRI.create(NS + "Cat"))
            .stream().anyMatch(a -> a.getProperty().isLabel());
        assertTrue(hasLabel, "staging ontology must have an RDFSLabel annotation on Cat");

        List<AuditEntry> entries = svc.auditLog().query(oid, null, null, "edit_entity", "tx-edit");
        assertFalse(entries.isEmpty());
        assertEquals("ok", entries.get(0).result());
    }

    @Test
    @DisplayName("editEntity ENTITY_NOT_FOUND when entity not in staging signature")
    void editEntityEntityNotFound(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-edit-nf");
        writeCanonical(tmp, "ont-edit-nf", buildBaseOntology());
        Services svc = newServices(tmp);
        // Create a tx first via addAxiom
        svc.editService().addAxiom(oid, "tx-edit-nf",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().editEntity(
            oid, "tx-edit-nf", NS + "NonExistent", "Label", null, null, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.ENTITY_NOT_FOUND,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());
    }

    @Test
    @DisplayName("editEntity INVALID_EDIT_ARGUMENTS when no edit field is set")
    void editEntityInvalidEditArguments(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-edit-invalid");
        writeCanonical(tmp, "ont-edit-invalid", buildBaseOntology());
        Services svc = newServices(tmp);
        // Create a tx first via addAxiom
        svc.editService().addAxiom(oid, "tx-edit-invalid",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().editEntity(
            oid, "tx-edit-invalid", NS + "Cat", null, null, null, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_EDIT_ARGUMENTS,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());
    }

    // ── createClass ──

    @Test
    @DisplayName("createClass success with superclass: Declaration + SubClassOf staged")
    void createClassWithSuper(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-cc-super");
        writeCanonical(tmp, "ont-cc-super", buildBaseOntology());
        Services svc = newServices(tmp);
        // Create a tx first via addAxiom
        svc.editService().addAxiom(oid, "tx-cc-super",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().createClass(
            oid, "tx-cc-super", NS + "Bird", List.of(NS + "Animal"), "alice");
        assertTrue(r.isSuccess());

        WriteTransaction tx = svc.transactionService().getTransaction("tx-cc-super");
        assertNotNull(tx);
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass bird = df.getOWLClass(IRI.create(NS + "Bird"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        assertTrue(tx.stagingOntology().containsAxiom(df.getOWLDeclarationAxiom(bird)),
            "staging must contain Declaration(Bird)");
        assertTrue(tx.stagingOntology().containsAxiom(df.getOWLSubClassOfAxiom(bird, animal)),
            "staging must contain SubClassOf(Bird, Animal)");
    }

    @Test
    @DisplayName("createClass success without superclass: SubClassOf(Thing) staged")
    void createClassNoSuper(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-cc-nosuper");
        writeCanonical(tmp, "ont-cc-nosuper", buildBaseOntology());
        Services svc = newServices(tmp);
        // Create a tx first via addAxiom
        svc.editService().addAxiom(oid, "tx-cc-nosuper",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().createClass(
            oid, "tx-cc-nosuper", NS + "Fish", null, "alice");
        assertTrue(r.isSuccess());

        WriteTransaction tx = svc.transactionService().getTransaction("tx-cc-nosuper");
        assertNotNull(tx);
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass fish = df.getOWLClass(IRI.create(NS + "Fish"));
        assertTrue(tx.stagingOntology().containsAxiom(
            df.getOWLSubClassOfAxiom(fish, df.getOWLThing())),
            "staging must contain SubClassOf(Fish, OWLThing)");
    }

    @Test
    @DisplayName("createClass CLASS_ALREADY_EXISTS when IRI is in staging signature")
    void createClassAlreadyExists(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-cc-exists");
        writeCanonical(tmp, "ont-cc-exists", buildBaseOntology());
        Services svc = newServices(tmp);
        // Dog is declared in the base ontology → in staging signature
        svc.editService().addAxiom(oid, "tx-cc-exists",
            axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"), "alice");

        ServiceResult<Map<String, Object>> r = svc.editService().createClass(
            oid, "tx-cc-exists", NS + "Dog", null, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.CLASS_ALREADY_EXISTS,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());
    }

    // ── merge ──

    @Test
    @DisplayName("merge from registered ontology_id: axioms staged from source")
    void mergeFromRegisteredOntologyId(@TempDir Path tmp) throws Exception {
        OntologyId targetOid = new OntologyId("ont-merge-target");
        writeCanonical(tmp, "ont-merge-target", buildBaseOntology());
        Services svc = newServices(tmp);

        // Write source ontology to canonical layout
        OWLOntology sourceOnt = buildBaseOntology();
        writeCanonical(tmp, "src", sourceOnt);
        Path srcCanonical = tmp.resolve(WS_NAME).resolve("ontologies").resolve("src")
            .resolve("canonical").resolve("ontology.owl");

        // Ensure catalog workspace directory exists before registering source
        Files.createDirectories(tmp.resolve("workspaces").resolve(WS_NAME));

        // Register source in the catalog
        ServiceResult<Void> addResult = svc.catalogStore().addEntry(WorkspaceId.DEFAULT, new CatalogEntry(
            new OntologyId("src"), "src", srcCanonical, srcCanonical,
            Instant.now(), tmp.resolve(WS_NAME).resolve("ontologies").resolve("src")
                .resolve("metadata.json")));
        assertTrue(addResult.isSuccess(), "catalog addEntry must succeed");

        ServiceResult<Map<String, Object>> r = svc.editService().merge(
            targetOid, "tx-merge-reg", "src", "alice");
        assertTrue(r.isSuccess());
        Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) r).data();
        int mergedCount = (Integer) data.get("mergedAxiomCount");
        assertTrue(mergedCount > 0, "mergedAxiomCount must be > 0");
        assertEquals("ontology_id", data.get("sourceKind"));

        WriteTransaction tx = svc.transactionService().getTransaction("tx-merge-reg");
        assertNotNull(tx);
        // Verify staging contains all source axioms
        for (OWLAxiom ax : sourceOnt.getAxioms()) {
            assertTrue(tx.stagingOntology().containsAxiom(ax),
                "staging must contain source axiom: " + ax);
        }
    }

    @Test
    @DisplayName("merge from file_path: source loaded from absolute path")
    void mergeFromFilePath(@TempDir Path tmp) throws Exception {
        OntologyId targetOid = new OntologyId("ont-merge-fp");
        writeCanonical(tmp, "ont-merge-fp", buildBaseOntology());
        Services svc = newServices(tmp);

        // Write source ontology to <tmp>/imports/source.owl
        Path importsDir = tmp.resolve("imports");
        Files.createDirectories(importsDir);
        Path sourceFile = importsDir.resolve("source.owl");
        OWLOntology sourceOnt = buildBaseOntology();
        try (var out = Files.newOutputStream(sourceFile)) {
            sourceOnt.getOWLOntologyManager().saveOntology(sourceOnt, new OWLXMLDocumentFormat(), out);
        }

        ServiceResult<Map<String, Object>> r = svc.editService().merge(
            targetOid, "tx-merge-fp", sourceFile.toString(), "alice");
        assertTrue(r.isSuccess());
        Map<String, Object> data = ((ServiceResult.Success<Map<String, Object>>) r).data();
        assertEquals("file_path", data.get("sourceKind"));
        assertTrue((Integer) data.get("mergedAxiomCount") > 0);
    }

    @Test
    @DisplayName("merge file_path traversal: path outside allowed roots rejected")
    void mergeFilePathTraversal(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-merge-traversal");
        writeCanonical(tmp, "ont-merge-traversal", buildBaseOntology());
        Services svc = newServices(tmp);

        // Use a path outside the allowed roots (a sibling of tmp that does not exist)
        String outsidePath = tmp.getParent()
            .resolve("owl4agents-outside-" + System.nanoTime() + ".owl")
            .toString();

        ServiceResult<Map<String, Object>> r = svc.editService().merge(
            oid, "tx-merge-traversal", outsidePath, "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.MERGE_SOURCE_INVALID,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());

        List<AuditEntry> entries = svc.auditLog().query(oid, null, null, "merge", "tx-merge-traversal");
        assertFalse(entries.isEmpty(), "audit log must contain a rejected merge entry");
        assertEquals("rejected", entries.get(0).result());
    }

    @Test
    @DisplayName("merge MERGE_SOURCE_INVALID for unknown source (not registered, not a file)")
    void mergeUnknownSource(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-merge-unknown");
        writeCanonical(tmp, "ont-merge-unknown", buildBaseOntology());
        Services svc = newServices(tmp);

        ServiceResult<Map<String, Object>> r = svc.editService().merge(
            oid, "tx-merge-unknown", "not-a-registered-id-and-not-a-file", "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.MERGE_SOURCE_INVALID,
            ((ServiceResult.Error<Map<String, Object>>) r).error().code());
    }
}
