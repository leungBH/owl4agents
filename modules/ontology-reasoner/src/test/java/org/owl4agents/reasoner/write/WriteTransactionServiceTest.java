package org.owl4agents.reasoner.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.owlapi.OntologyCache;
import org.owl4agents.reasoner.TemporaryOntologyFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.9.1 mcp-write-tools-expansion D2: WriteTransactionService unit tests.
 *
 * <p>Covers lazy getOrCreate, conflict detection, commit swap (canonical
 * file + cache invalidation + version snapshot + audit), rollback, and
 * rollbackToVersion.</p>
 */
@DisplayName("v0.9.1 WriteTransactionService (D2)")
class WriteTransactionServiceTest {

    private static final String WS_NAME = "default";
    private static final String NS = "http://owl4agents.org/test/wts#";

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

    private void writeCanonical(Path workspaceRoot, String ontologyId, OWLOntology ont) throws Exception {
        Path canonical = workspaceRoot.resolve(WS_NAME).resolve("ontologies")
            .resolve(ontologyId).resolve("canonical").resolve("ontology.owl");
        Files.createDirectories(canonical.getParent());
        try (var out = Files.newOutputStream(canonical)) {
            ont.getOWLOntologyManager().saveOntology(ont, new OWLXMLDocumentFormat(), out);
        }
    }

    private WriteTransactionService newService(Path tmp) {
        OntologyCache cache = new OntologyCache(tmp.toString(), WS_NAME, 0);
        TemporaryOntologyFactory tempFactory = new TemporaryOntologyFactory();
        VersionHistoryStore vhs = new VersionHistoryStore(tmp.toString(), WS_NAME);
        AuditLog auditLog = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        return new WriteTransactionService(cache, tempFactory, vhs, auditLog);
    }

    @Test
    @DisplayName("getOrCreate lazily creates a transaction seeded from committed state")
    void getOrCreateLazilyCreates(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-1");
        writeCanonical(tmp, "ont-1", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        ServiceResult<WriteTransaction> r = svc.getOrCreate("tx-A", oid);
        assertTrue(r.isSuccess());
        WriteTransaction tx = ((ServiceResult.Success<WriteTransaction>) r).data();
        assertEquals("tx-A", tx.transactionId());
        assertEquals(oid, tx.ontologyId());
        assertTrue(tx.stagingOntology().getAxiomCount() >= 3,
            "staging ontology must be seeded from committed state");
    }

    @Test
    @DisplayName("getOrCreate is idempotent: same txId returns the same transaction")
    void getOrCreateIsIdempotent(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-2");
        writeCanonical(tmp, "ont-2", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        ServiceResult<WriteTransaction> r1 = svc.getOrCreate("tx-B", oid);
        ServiceResult<WriteTransaction> r2 = svc.getOrCreate("tx-B", oid);
        assertTrue(r1.isSuccess() && r2.isSuccess());
        assertSame(((ServiceResult.Success<WriteTransaction>) r1).data(),
            ((ServiceResult.Success<WriteTransaction>) r2).data(),
            "same txId must return the same transaction instance");
    }

    @Test
    @DisplayName("getOrCreate with blank transactionId returns INVALID_ARGUMENTS")
    void getOrCreateBlankTxId(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        ServiceResult<WriteTransaction> r = svc.getOrCreate("", new OntologyId("ont-x"));
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<WriteTransaction>) r).error().code());
    }

    // v0.9.1 P1-1 secondary fix: missing ontology_id must return
    // ONTOLOGY_NOT_FOUND (matches readonly tools' behavior) instead of the
    // prior INVALID_ARGUMENTS that misled clients.
    @Test
    @DisplayName("getOrCreate with non-existent ontology returns ONTOLOGY_NOT_FOUND")
    void getOrCreateMissingOntologyReturnsNotFound(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        // No canonical file written for "definitely_missing" — getOrCreate
        // will try to load it via OntologyCache and fail.
        ServiceResult<WriteTransaction> r = svc.getOrCreate("tx-missing",
            new OntologyId("definitely_missing"));
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND,
            ((ServiceResult.Error<WriteTransaction>) r).error().code(),
            "missing ontology should return ONTOLOGY_NOT_FOUND, not INVALID_ARGUMENTS");
    }

    @Test
    @DisplayName("getOrCreate rejects ontology mismatch for an existing transaction")
    void getOrCreateOntologyMismatch(@TempDir Path tmp) throws Exception {
        OntologyId a = new OntologyId("ont-a");
        OntologyId b = new OntologyId("ont-b");
        writeCanonical(tmp, "ont-a", buildBaseOntology());
        writeCanonical(tmp, "ont-b", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        svc.getOrCreate("tx-M", a);
        ServiceResult<WriteTransaction> r = svc.getOrCreate("tx-M", b);
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<WriteTransaction>) r).error().code());
    }

    @Test
    @DisplayName("getTransaction returns null for unknown txId")
    void getTransactionUnknownReturnsNull(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        assertNull(svc.getTransaction("never-created"));
    }

    @Test
    @DisplayName("checkConflict returns TRANSACTION_NOT_FOUND for unknown txId")
    void checkConflictUnknownTx(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        ServiceResult<Void> r = svc.checkConflict("unknown");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.TRANSACTION_NOT_FOUND,
            ((ServiceResult.Error<Void>) r).error().code());
    }

    @Test
    @DisplayName("commit success: writes canonical file, creates snapshot, removes tx")
    void commitSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-commit");
        writeCanonical(tmp, "ont-commit", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        svc.getOrCreate("tx-C", oid);
        // Stage an edit: add a new subclass axiom
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLSubClassOfAxiom newAxiom = df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "Cat")),
            df.getOWLClass(IRI.create(NS + "Animal")));

        WriteTransaction tx = svc.getTransaction("tx-C");
        tx.stagingManager().addAxiom(tx.stagingOntology(), newAxiom);

        ServiceResult<VersionSnapshot> r = svc.commit("tx-C", "add Cat subclass", "alice");
        assertTrue(r.isSuccess());
        VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) r).data();
        assertNotNull(snap.versionId());
        assertEquals("add Cat subclass", snap.changeSummary());
        assertEquals("alice", snap.author());

        // Transaction must be removed after commit
        assertNull(svc.getTransaction("tx-C"));

        // Canonical file must be updated (contains the new axiom)
        Path canonical = tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve("ont-commit").resolve("canonical").resolve("ontology.owl");
        String content = Files.readString(canonical);
        assertTrue(content.contains("Cat") || content.contains(NS + "Cat"),
            "committed canonical file must contain the staged axiom");

        // History must contain the snapshot
        VersionHistoryStore vhs = new VersionHistoryStore(tmp.toString(), WS_NAME);
        ServiceResult<List<VersionSnapshot>> hist = vhs.listHistory(oid, 10);
        assertTrue(hist.isSuccess());
        assertEquals(1, ((ServiceResult.Success<List<VersionSnapshot>>) hist).data().size());
    }

    @Test
    @DisplayName("commit returns TRANSACTION_NOT_FOUND for unknown txId")
    void commitUnknownTx(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        ServiceResult<VersionSnapshot> r = svc.commit("unknown", "msg", "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.TRANSACTION_NOT_FOUND,
            ((ServiceResult.Error<VersionSnapshot>) r).error().code());
    }

    @Test
    @DisplayName("commit returns TRANSACTION_CONFLICT when committed state changed after seed")
    void commitConflict(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-conflict");
        writeCanonical(tmp, "ont-conflict", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        // tx-1 seeds with counter=0, stages an edit, but does NOT commit yet
        svc.getOrCreate("tx-1", oid);
        WriteTransaction tx1 = svc.getTransaction("tx-1");
        OWLDataFactory df = tx1.stagingOntology().getOWLOntologyManager().getOWLDataFactory();
        tx1.stagingManager().addAxiom(tx1.stagingOntology(),
            df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "Cat")),
                df.getOWLClass(IRI.create(NS + "Animal"))));

        // tx-2 seeds with counter=0, commits immediately (counter 0→1)
        svc.getOrCreate("tx-2", oid);
        ServiceResult<VersionSnapshot> r2 = svc.commit("tx-2", "tx-2 commit", "bob");
        assertTrue(r2.isSuccess(), "tx-2 must commit first (no conflict)");

        // Now committing tx-1 (committedVersion=0, current=1) → CONFLICT
        ServiceResult<VersionSnapshot> r1 = svc.commit("tx-1", "tx-1 commit", "alice");
        assertFalse(r1.isSuccess());
        assertEquals(ErrorCode.TRANSACTION_CONFLICT,
            ((ServiceResult.Error<VersionSnapshot>) r1).error().code());

        // tx-1 must still be present (conflict keeps the transaction open)
        assertNotNull(svc.getTransaction("tx-1"),
            "conflict must NOT remove the transaction");
    }

    @Test
    @DisplayName("checkConflict detects stale transaction before commit")
    void checkConflictDetectsStale(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-cc");
        writeCanonical(tmp, "ont-cc", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        svc.getOrCreate("tx-stale", oid);
        // Bump the counter by committing a different transaction
        svc.getOrCreate("tx-other", oid);
        svc.commit("tx-other", "other", "bob");

        ServiceResult<Void> r = svc.checkConflict("tx-stale");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.TRANSACTION_CONFLICT,
            ((ServiceResult.Error<Void>) r).error().code());
    }

    @Test
    @DisplayName("rollback success: removes tx and appends audit")
    void rollbackSuccess(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rb");
        writeCanonical(tmp, "ont-rb", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        svc.getOrCreate("tx-RB", oid);
        ServiceResult<Void> r = svc.rollback("tx-RB", "alice");
        assertTrue(r.isSuccess());
        assertNull(svc.getTransaction("tx-RB"), "rollback must remove the transaction");

        // Audit log must contain the rollback entry
        AuditLog auditLog = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        List<AuditEntry> entries = auditLog.query(oid, null, null, "rollback", null);
        assertEquals(1, entries.size());
        assertEquals("ok", entries.get(0).result());
    }

    @Test
    @DisplayName("rollback returns TRANSACTION_NOT_FOUND for unknown txId")
    void rollbackUnknownTx(@TempDir Path tmp) {
        WriteTransactionService svc = newService(tmp);
        ServiceResult<Void> r = svc.rollback("unknown", "alice");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.TRANSACTION_NOT_FOUND,
            ((ServiceResult.Error<Void>) r).error().code());
    }

    @Test
    @DisplayName("rollbackToVersion restores committed state and creates a new version")
    void rollbackToVersionRestoresState(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-rbv");
        writeCanonical(tmp, "ont-rbv", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        // Commit once to create a baseline snapshot
        svc.getOrCreate("tx-baseline", oid);
        ServiceResult<VersionSnapshot> c1 = svc.commit("tx-baseline", "baseline", "alice");
        String baselineVid = ((ServiceResult.Success<VersionSnapshot>) c1).data().versionId();

        // Commit a second version (different content)
        svc.getOrCreate("tx-v2", oid);
        WriteTransaction tx2 = svc.getTransaction("tx-v2");
        OWLDataFactory df = tx2.stagingOntology().getOWLOntologyManager().getOWLDataFactory();
        tx2.stagingManager().addAxiom(tx2.stagingOntology(),
            df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "Extra"))));
        ServiceResult<VersionSnapshot> c2 = svc.commit("tx-v2", "v2", "bob");
        assertTrue(c2.isSuccess());

        // Rollback to the baseline
        ServiceResult<VersionSnapshot> rb = svc.rollbackToVersion(oid, baselineVid, "carol");
        assertTrue(rb.isSuccess());
        VersionSnapshot restored = ((ServiceResult.Success<VersionSnapshot>) rb).data();
        assertNotEquals(baselineVid, restored.versionId(),
            "rollback creates a NEW version, not the original");

        // Canonical file must now contain the baseline content (no "Extra")
        Path canonical = tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve("ont-rbv").resolve("canonical").resolve("ontology.owl");
        String content = Files.readString(canonical);
        assertFalse(content.contains(NS + "Extra"),
            "canonical file must reflect the rolled-back (baseline) content");
    }

    @Test
    @DisplayName("recordEditAudit appends an entry to the audit log")
    void recordEditAuditAppends(@TempDir Path tmp) throws Exception {
        OntologyId oid = new OntologyId("ont-audit");
        writeCanonical(tmp, "ont-audit", buildBaseOntology());
        WriteTransactionService svc = newService(tmp);

        svc.recordEditAudit(oid, new AuditEntry(
            null, java.time.Instant.now(), oid.id(), "tx-edit",
            "add_axiom", "alice", null, null, null, "ok", "test"));

        AuditLog auditLog = new AuditLog(tmp.toString(), WS_NAME, true, 104_857_600L, 10);
        List<AuditEntry> entries = auditLog.query(oid, null, null, "add_axiom", "tx-edit");
        assertEquals(1, entries.size());
        assertEquals("alice", entries.get(0).author());
    }
}
