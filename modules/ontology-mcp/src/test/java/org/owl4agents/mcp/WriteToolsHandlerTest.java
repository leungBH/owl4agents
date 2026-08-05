package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ResultMetadata;
import org.owl4agents.core.ServiceError;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.owl4agents.reasoner.write.AuditLog;
import org.owl4agents.reasoner.write.OntologyEditService;
import org.owl4agents.reasoner.write.VersionHistoryStore;
import org.owl4agents.reasoner.write.WriteTransaction;
import org.owl4agents.reasoner.write.WriteTransactionService;
import org.owl4agents.shacl.Severity;
import org.owl4agents.shacl.ShaclValidationOptions;
import org.owl4agents.shacl.ShaclValidationReport;
import org.owl4agents.shacl.ShaclValidationService;
import org.owl4agents.shacl.ShaclViolation;
import org.owl4agents.shacl.ShapeRegistry;
import org.owl4agents.shacl.ShapeSet;
import org.owl4agents.storage.CatalogStore;
import org.owl4agents.storage.HomeDirectoryResolver;

import org.apache.jena.rdf.model.Model;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.9.1 mcp-write-tools-expansion D6: WriteToolsHandler unit tests.
 *
 * <p>Covers the 8 write tool dispatch (addAxiom / commit / rollback / etc.),
 * SHACL-on-commit orchestration (violation blocks, warning does not),
 * the 3 readonly observation tools (diff / version_history / audit_log),
 * and the recordImportOutcome post-import hook.</p>
 */
@DisplayName("v0.9.1 WriteToolsHandler (D6)")
class WriteToolsHandlerTest {

    private static final String WS_NAME = "default";
    private static final String NS = "http://owl4agents.org/test/wth#";

    // ── Stubs (no Mockito on classpath) ──

    static final class StubShapeRegistry implements ShapeRegistry {
        private final List<ShapeSet> shapes;

        StubShapeRegistry(List<ShapeSet> shapes) {
            this.shapes = shapes;
        }

        @Override
        public ServiceResult<ShapeSet> register(String id, Path shapesFile, String domain,
                                                boolean force, boolean requiresInference) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_ID_CONFLICT);
        }

        @Override
        public ServiceResult<Model> resolve(String shapeSetId) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND);
        }

        @Override
        public ServiceResult<Void> invalidate(String shapeSetId) {
            return ServiceResult.error(ErrorCode.SHAPE_SET_NOT_FOUND);
        }

        @Override
        public List<ShapeSet> list() {
            return shapes;
        }

        @Override
        public Optional<ShapeSet> get(String shapeSetId) {
            return Optional.empty();
        }
    }

    static final class StubShaclValidationService implements ShaclValidationService {
        ShaclValidationReport nextReport = ShaclValidationReport.empty(1L);
        ServiceError nextError = null;

        @Override
        public ServiceResult<ShaclValidationReport> validate(
            Model dataGraph, Model shapesGraph, ShaclValidationOptions options) {
            return ServiceResult.success(ShaclValidationReport.empty(1L), ResultMetadata.empty());
        }

        @Override
        public ServiceResult<ShaclValidationReport> validateRegisteredShapes(
            String shapeSetId, Model dataGraph, ShaclValidationOptions options) {
            if (nextError != null) {
                return ServiceResult.error(nextError);
            }
            return ServiceResult.success(nextReport, ResultMetadata.empty());
        }
    }

    // ── Setup helpers ──

    private record Services(
        WriteToolsHandler handler,
        WriteTransactionService transactionService,
        OntologyEditService editService,
        VersionHistoryStore versionHistoryStore,
        AuditLog auditLog,
        StubShapeRegistry shapeRegistry,
        StubShaclValidationService shaclService
    ) {}

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

    private Services newServices(Path tmp) {
        return newServices(tmp, new StubShapeRegistry(List.of()), new StubShaclValidationService());
    }

    private Services newServices(Path tmp, StubShapeRegistry shapeRegistry,
                                 StubShaclValidationService shaclService) {
        OntologyCache cache = new OntologyCache(tmp.toString(), WS_NAME, 0);
        TemporaryOntologyFactory tempFactory = new TemporaryOntologyFactory();
        VersionHistoryStore vhs = new VersionHistoryStore(tmp.toString(), WS_NAME);
        AuditLog auditLog = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        WriteTransactionService txService = new WriteTransactionService(cache, tempFactory, vhs, auditLog);
        HomeDirectoryResolver homeResolver = new HomeDirectoryResolver(tmp);
        CatalogStore catalogStore = new CatalogStore(homeResolver);
        OntologyEditService editService = new OntologyEditService(
            txService, catalogStore, homeResolver, WorkspaceId.DEFAULT, tmp.toString());
        WriteToolsHandler handler = new WriteToolsHandler(
            txService, editService, vhs, auditLog, shaclService, shapeRegistry,
            cache, catalogStore, homeResolver, WorkspaceId.DEFAULT, tmp.toString());
        return new Services(handler, txService, editService, vhs, auditLog, shapeRegistry, shaclService);
    }

    private Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> axiomMap(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axiomType", type);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> resp) {
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorObj(Map<String, Object> resp) {
        return (Map<String, Object>) resp.get("error");
    }

    private Map<String, Object> seedAddAxiom(Services svc, String oid, String txId) {
        return svc.handler().execute("ontology_add_axiom", args(
            "ontology_id", oid,
            "transaction_id", txId,
            "axiom", axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"),
            "author", "alice"));
    }

    // ── Test cases ──

    @Test
    @DisplayName("doAddAxiom dispatch: success with transactionId and stagedAxiomCount")
    void doAddAxiomDispatch(@TempDir Path tmp) throws Exception {
        writeCanonical(tmp, "ont-1", buildBaseOntology());
        Services svc = newServices(tmp);

        Map<String, Object> resp = svc.handler().execute("ontology_add_axiom", args(
            "ontology_id", "ont-1",
            "transaction_id", "tx-1",
            "axiom", axiomMap("SubClassOf", "subject", NS + "Cat", "object", NS + "Animal"),
            "author", "alice"));
        assertEquals("success", resp.get("status"));
        assertEquals("tx-1", data(resp).get("transactionId"));
        assertEquals(1, data(resp).get("stagedAxiomCount"));
    }

    @Test
    @DisplayName("doCommit with no shapes registered: success, versionId + 64-char checksum")
    void doCommitNoShapes(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-commit");
        writeCanonical(tmp, "ont-commit", buildBaseOntology());
        Services svc = newServices(tmp);
        seedAddAxiom(svc, "ont-commit", "tx-commit");

        Map<String, Object> resp = svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-commit",
            "transaction_id", "tx-commit",
            "message", "add Cat",
            "author", "alice"));
        assertEquals("success", resp.get("status"));
        Map<String, Object> d = data(resp);
        assertNotNull(d.get("versionId"));
        String checksum = (String) d.get("contentChecksum");
        assertNotNull(checksum);
        assertEquals(64, checksum.length(), "SHA-256 hex is 64 chars");

        List<?> entries = svc.auditLog().queryAsMaps(oid, null, null, "commit", "tx-commit");
        assertFalse(entries.isEmpty(), "audit log must contain a commit entry");
        Map<String, Object> first = (Map<String, Object>) entries.get(0);
        assertEquals("ok", first.get("result"));
    }

    @Test
    @DisplayName("doCommit with SHACL Violation: COMMIT_SHACL_VIOLATION, tx stays open")
    void doCommitShaclViolation(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-commit-vio");
        writeCanonical(tmp, "ont-commit-vio", buildBaseOntology());
        StubShaclValidationService shacl = new StubShaclValidationService();
        ShaclViolation violation = new ShaclViolation(
            null, "shape1", "sh:minCount", "http://x#Foo",
            null, null, Severity.Violation, "msg", List.of(), "");
        shacl.nextReport = new ShaclValidationReport(
            false, List.of(violation), List.of(), List.of(), 1L, Optional.of("ss"), "1.0");
        StubShapeRegistry shapes = new StubShapeRegistry(List.of(
            new ShapeSet("ss", "1.0.0", "default", Path.of("shapes.ttl"), "abc", true, true, false)));
        Services svc = newServices(tmp, shapes, shacl);
        seedAddAxiom(svc, "ont-commit-vio", "tx-vio");

        Map<String, Object> resp = svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-commit-vio",
            "transaction_id", "tx-vio",
            "message", "m",
            "author", "alice"));
        assertEquals("error", resp.get("status"));
        assertEquals("COMMIT_SHACL_VIOLATION", errorObj(resp).get("code"));

        WriteTransaction tx = svc.transactionService().getTransaction("tx-vio");
        assertNotNull(tx, "transaction must remain open after SHACL violation");

        List<?> entries = svc.auditLog().queryAsMaps(oid, null, null, "commit", "tx-vio");
        assertFalse(entries.isEmpty());
        Map<String, Object> first = (Map<String, Object>) entries.get(0);
        assertEquals("shacl_violation", first.get("result"));
    }

    @Test
    @DisplayName("doCommit with SHACL Warning: success (warnings do not block)")
    void doCommitShaclWarning(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-commit-warn");
        writeCanonical(tmp, "ont-commit-warn", buildBaseOntology());
        StubShaclValidationService shacl = new StubShaclValidationService();
        ShaclViolation warning = new ShaclViolation(
            null, "shape1", "sh:minCount", "http://x#Foo",
            null, null, Severity.Warning, "msg", List.of(), "");
        shacl.nextReport = new ShaclValidationReport(
            true, List.of(), List.of(warning), List.of(), 1L, Optional.of("ss"), "1.0");
        StubShapeRegistry shapes = new StubShapeRegistry(List.of(
            new ShapeSet("ss", "1.0.0", "default", Path.of("shapes.ttl"), "abc", true, true, false)));
        Services svc = newServices(tmp, shapes, shacl);
        seedAddAxiom(svc, "ont-commit-warn", "tx-warn");

        Map<String, Object> resp = svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-commit-warn",
            "transaction_id", "tx-warn",
            "message", "m",
            "author", "alice"));
        assertEquals("success", resp.get("status"));
        assertTrue(data(resp).containsKey("warnings"),
            "data must include a 'warnings' key when warnings were produced");
    }

    @Test
    @DisplayName("doRollback: success, transaction removed")
    void doRollback(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rb");
        writeCanonical(tmp, "ont-rb", buildBaseOntology());
        Services svc = newServices(tmp);
        seedAddAxiom(svc, "ont-rb", "tx-rb");

        Map<String, Object> resp = svc.handler().execute("ontology_rollback", args(
            "ontology_id", "ont-rb",
            "transaction_id", "tx-rb",
            "author", "alice"));
        assertEquals("success", resp.get("status"));
        assertNotNull(data(resp).get("transactionId"));
        assertNull(svc.transactionService().getTransaction("tx-rb"),
            "transaction must be removed after rollback");
    }

    @Test
    @DisplayName("doDiff committed vs transaction: added contains SubClassOf Cat")
    void doDiffCommittedVsTransaction(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-diff");
        writeCanonical(tmp, "ont-diff", buildBaseOntology());
        Services svc = newServices(tmp);
        seedAddAxiom(svc, "ont-diff", "tx-diff");

        Map<String, Object> resp = svc.handler().execute("ontology_diff", args(
            "ontology_id", "ont-diff",
            "from", "committed",
            "to", "transaction:tx-diff"));
        assertEquals("success", resp.get("status"));
        Map<String, Object> d = data(resp);
        List<?> added = (List<?>) d.get("added");
        List<?> removed = (List<?>) d.get("removed");
        assertTrue(removed.isEmpty(), "removed must be empty");
        assertEquals(1, d.get("addedCount"));
        assertTrue(added.stream().anyMatch(s -> s.toString().contains("Cat")),
            "added must contain a SubClassOf Cat axiom string");
    }

    @Test
    @DisplayName("doVersionHistory: returns at least 1 version after a commit")
    void doVersionHistory(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-vh");
        writeCanonical(tmp, "ont-vh", buildBaseOntology());
        Services svc = newServices(tmp);
        seedAddAxiom(svc, "ont-vh", "tx-vh");
        svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-vh", "transaction_id", "tx-vh",
            "message", "v1", "author", "alice"));

        Map<String, Object> resp = svc.handler().execute("ontology_version_history", args(
            "ontology_id", "ont-vh"));
        assertEquals("success", resp.get("status"));
        Map<String, Object> d = data(resp);
        int count = (Integer) d.get("count");
        assertTrue(count >= 1, "version_history must have at least 1 entry");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) d.get("versions");
        assertNotNull(versions.get(0).get("versionId"));
    }

    @Test
    @DisplayName("doAuditLog: returns entries with operation field")
    void doAuditLog(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-al");
        writeCanonical(tmp, "ont-al", buildBaseOntology());
        Services svc = newServices(tmp);
        seedAddAxiom(svc, "ont-al", "tx-al");

        Map<String, Object> resp = svc.handler().execute("ontology_audit_log", args(
            "ontology_id", "ont-al"));
        assertEquals("success", resp.get("status"));
        Map<String, Object> d = data(resp);
        int count = (Integer) d.get("count");
        assertTrue(count >= 1, "audit_log must have at least 1 entry");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) d.get("entries");
        assertNotNull(entries.get(0).get("operation"));
    }

    @Test
    @DisplayName("recordImportOutcome success: baseline snapshot + audit ok")
    void recordImportOutcomeSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-import");
        writeCanonical(tmp, "ont-import", buildBaseOntology());
        Services svc = newServices(tmp);

        svc.handler().recordImportOutcome("ont-import", true, null, "mcp");

        Map<String, Object> vhResp = svc.handler().execute("ontology_version_history",
            args("ontology_id", "ont-import"));
        assertEquals("success", vhResp.get("status"));
        Map<String, Object> vhData = data(vhResp);
        assertEquals(1, vhData.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) vhData.get("versions");
        assertNull(versions.get(0).get("parentVersionId"),
            "import baseline must have parentVersionId=null");

        Map<String, Object> alResp = svc.handler().execute("ontology_audit_log",
            args("ontology_id", "ont-import"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data(alResp).get("entries");
        assertTrue(entries.stream().anyMatch(e ->
            "import".equals(e.get("operation")) && "ok".equals(e.get("result"))),
            "audit_log must contain an import result=ok entry");
    }

    @Test
    @DisplayName("recordImportOutcome failure: audit rejected, no snapshot")
    void recordImportOutcomeFailure(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-import-fail");
        Services svc = newServices(tmp);

        svc.handler().recordImportOutcome("ont-import-fail", false, "parse error", "mcp");

        Map<String, Object> alResp = svc.handler().execute("ontology_audit_log",
            args("ontology_id", "ont-import-fail"));
        assertEquals("success", alResp.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data(alResp).get("entries");
        assertTrue(entries.stream().anyMatch(e ->
            "import".equals(e.get("operation")) && "rejected".equals(e.get("result"))),
            "audit_log must contain an import result=rejected entry");

        Map<String, Object> vhResp = svc.handler().execute("ontology_version_history",
            args("ontology_id", "ont-import-fail"));
        assertEquals("success", vhResp.get("status"));
        assertEquals(0, data(vhResp).get("count"),
            "version_history must be empty on import failure");
    }

    @Test
    @DisplayName("Unknown tool returns INVALID_ARGUMENTS")
    void unknownTool(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        Map<String, Object> resp = svc.handler().execute("ontology_bogus", Map.of());
        assertEquals("error", resp.get("status"));
        assertEquals("INVALID_ARGUMENTS", errorObj(resp).get("code"));
    }

    @Test
    @DisplayName("Missing ontology_id returns error")
    void missingOntologyId(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        Map<String, Object> resp = svc.handler().execute("ontology_add_axiom", Map.of());
        assertEquals("error", resp.get("status"));
        assertEquals("INVALID_ARGUMENTS", errorObj(resp).get("code"));
    }

    @Test
    @DisplayName("Missing transaction_id returns error")
    void missingTransactionId(@TempDir Path tmp) {
        Services svc = newServices(tmp);
        Map<String, Object> resp = svc.handler().execute("ontology_add_axiom",
            Map.of("ontology_id", "ont-1"));
        assertEquals("error", resp.get("status"));
        assertEquals("INVALID_ARGUMENTS", errorObj(resp).get("code"));
    }

    @Test
    @DisplayName("doRollbackToVersion: restores from first versionId")
    void doRollbackToVersion(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rbv");
        writeCanonical(tmp, "ont-rbv", buildBaseOntology());
        Services svc = newServices(tmp);

        // Commit version 1
        seedAddAxiom(svc, "ont-rbv", "tx-rbv-1");
        Map<String, Object> c1 = svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-rbv", "transaction_id", "tx-rbv-1",
            "message", "v1", "author", "alice"));
        assertEquals("success", c1.get("status"));
        String v1 = (String) data(c1).get("versionId");

        // Commit version 2 (different content)
        svc.handler().execute("ontology_add_axiom", args(
            "ontology_id", "ont-rbv", "transaction_id", "tx-rbv-2",
            "axiom", axiomMap("SubClassOf", "subject", NS + "Bird", "object", NS + "Animal"),
            "author", "bob"));
        Map<String, Object> c2 = svc.handler().execute("ontology_commit", args(
            "ontology_id", "ont-rbv", "transaction_id", "tx-rbv-2",
            "message", "v2", "author", "bob"));
        assertEquals("success", c2.get("status"));

        // Rollback to version 1
        Map<String, Object> resp = svc.handler().execute("ontology_rollback_to_version", args(
            "ontology_id", "ont-rbv",
            "version_id", v1,
            "author", "carol"));
        assertEquals("success", resp.get("status"));
        assertEquals(v1, data(resp).get("restoredFrom"));
    }
}
