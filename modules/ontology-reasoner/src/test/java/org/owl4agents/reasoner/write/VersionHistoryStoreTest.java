package org.owl4agents.reasoner.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
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
 * v0.9.1 mcp-write-tools-expansion D4: VersionHistoryStore unit tests.
 *
 * <p>Covers snapshot creation, listHistory ordering + sanitization,
 * findSnapshot, rollbackToVersion, content-addressed dedup, and pruning
 * (with baseline protection).</p>
 */
@DisplayName("v0.9.1 VersionHistoryStore (D4)")
class VersionHistoryStoreTest {

    private static final String WS_NAME = "default";
    private static final String NS = "http://owl4agents.org/test/vhs#";

    private OWLOntology buildOntology(String suffix) {
        return buildOntology(suffix, 0);
    }

    private OWLOntology buildOntology(String suffix, int extraAxioms) {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(NS + suffix));
            OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
            OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
            ont.addAxiom(df.getOWLDeclarationAxiom(animal));
            ont.addAxiom(df.getOWLDeclarationAxiom(dog));
            ont.addAxiom(df.getOWLSubClassOfAxiom(dog, animal));
            for (int i = 0; i < extraAxioms; i++) {
                OWLClass extra = df.getOWLClass(IRI.create(NS + "Extra" + i));
                ont.addAxiom(df.getOWLDeclarationAxiom(extra));
            }
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createSnapshot writes blob + metadata and returns full snapshot record")
    void createSnapshotWritesBlobAndMetadata(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-1");
        OWLOntology ont = buildOntology("ont-1");

        ServiceResult<VersionSnapshot> r = store.createSnapshot(
            oid, ont, "alice", null, "Initial import");

        assertTrue(r.isSuccess());
        VersionSnapshot snap = ((ServiceResult.Success<VersionSnapshot>) r).data();
        assertNotNull(snap.versionId());
        assertEquals("alice", snap.author());
        assertNull(snap.parentVersionId(), "import baseline has null parent");
        assertEquals("Initial import", snap.changeSummary());
        assertTrue(snap.axiomCount() > 0);
        assertNotNull(snap.contentChecksum());
        assertEquals(64, snap.contentChecksum().length(), "SHA-256 hex is 64 chars");
        assertNotNull(snap.snapshotPath());

        Path versionsDir = tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve("ont-1").resolve("versions");
        assertTrue(Files.exists(versionsDir.resolve(snap.snapshotPath())),
            "snapshot blob file must exist");
        assertTrue(Files.exists(versionsDir.resolve("versions.json")),
            "metadata file must exist");
    }

    @Test
    @DisplayName("listHistory returns newest first and omits snapshotPath")
    void listHistoryNewestFirstAndSanitized(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-list");

        ServiceResult<VersionSnapshot> s1 = store.createSnapshot(
            oid, buildOntology("list1"), "alice", null, "first");
        ServiceResult<VersionSnapshot> s2 = store.createSnapshot(
            oid, buildOntology("list2", 1), "bob",
            ((ServiceResult.Success<VersionSnapshot>) s1).data().versionId(), "second");

        ServiceResult<List<VersionSnapshot>> r = store.listHistory(oid, 10);
        assertTrue(r.isSuccess());
        List<VersionSnapshot> list = ((ServiceResult.Success<List<VersionSnapshot>>) r).data();
        assertEquals(2, list.size());
        // Newest first
        assertEquals(((ServiceResult.Success<VersionSnapshot>) s2).data().versionId(),
            list.get(0).versionId());
        // snapshotPath must be null (sanitized for tool response)
        assertNull(list.get(0).snapshotPath(), "listHistory must omit snapshotPath");
        assertNull(list.get(1).snapshotPath(), "listHistory must omit snapshotPath");
    }

    @Test
    @DisplayName("findSnapshot returns full record including snapshotPath")
    void findSnapshotReturnsFullPath(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-find");
        ServiceResult<VersionSnapshot> created = store.createSnapshot(
            oid, buildOntology("find"), "alice", null, "baseline");

        String vid = ((ServiceResult.Success<VersionSnapshot>) created).data().versionId();
        ServiceResult<VersionSnapshot> found = store.findSnapshot(oid, vid);
        assertTrue(found.isSuccess());
        assertNotNull(((ServiceResult.Success<VersionSnapshot>) found).data().snapshotPath(),
            "findSnapshot must include snapshotPath for internal use");
    }

    @Test
    @DisplayName("findSnapshot returns VERSION_NOT_FOUND for unknown versionId")
    void findSnapshotUnknownReturnsError(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-find-unknown");
        ServiceResult<VersionSnapshot> r = store.findSnapshot(oid, "does-not-exist");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.VERSION_NOT_FOUND,
            ((ServiceResult.Error<VersionSnapshot>) r).error().code());
    }

    @Test
    @DisplayName("content-addressed dedup: identical content reuses snapshotPath")
    void contentAddressedDedupReusesPath(@TempDir Path tmp) throws Exception {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-dedup");
        // Two identical ontologies (same axioms) → same checksum → dedup
        OWLOntology ont1 = buildOntology("dedup");
        OWLOntology ont2 = buildOntology("dedup"); // same content, different manager

        ServiceResult<VersionSnapshot> s1 = store.createSnapshot(oid, ont1, "alice", null, "v1");
        ServiceResult<VersionSnapshot> s2 = store.createSnapshot(oid, ont2, "bob",
            ((ServiceResult.Success<VersionSnapshot>) s1).data().versionId(), "v2");

        VersionSnapshot snap1 = ((ServiceResult.Success<VersionSnapshot>) s1).data();
        VersionSnapshot snap2 = ((ServiceResult.Success<VersionSnapshot>) s2).data();
        assertEquals(snap1.contentChecksum(), snap2.contentChecksum(),
            "identical content must produce identical checksums");
        assertEquals(snap1.snapshotPath(), snap2.snapshotPath(),
            "dedup must reuse the same blob path");
        assertNotEquals(snap1.versionId(), snap2.versionId(),
            "each snapshot still has its own versionId");

        // Only one blob file should exist on disk
        Path versionsDir = tmp.resolve(WS_NAME).resolve("ontologies")
            .resolve("ont-dedup").resolve("versions");
        long blobCount;
        try (var stream = Files.list(versionsDir)) {
            blobCount = stream.filter(p -> p.getFileName().toString().endsWith(".owx")).count();
        }
        assertEquals(1, blobCount, "dedup must not write a duplicate blob");
    }

    @Test
    @DisplayName("rollbackToVersion creates a new version sharing the original blob")
    void rollbackToVersionCreatesNewVersion(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-rb");

        ServiceResult<VersionSnapshot> s1 = store.createSnapshot(
            oid, buildOntology("rb1"), "alice", null, "baseline");
        String baselineVid = ((ServiceResult.Success<VersionSnapshot>) s1).data().versionId();

        ServiceResult<VersionSnapshot> rb = store.rollbackToVersion(oid, baselineVid, "carol");
        assertTrue(rb.isSuccess());
        VersionSnapshot newSnap = ((ServiceResult.Success<VersionSnapshot>) rb).data();
        assertNotEquals(baselineVid, newSnap.versionId(),
            "rollback must create a NEW version, not return the original");
        assertEquals(((ServiceResult.Success<VersionSnapshot>) s1).data().contentChecksum(),
            newSnap.contentChecksum(),
            "rollback content must match the target snapshot");
        assertEquals(((ServiceResult.Success<VersionSnapshot>) s1).data().snapshotPath(),
            newSnap.snapshotPath(),
            "rollback must reuse the original blob (content-addressed dedup)");
    }

    @Test
    @DisplayName("rollbackToVersion returns VERSION_NOT_FOUND for unknown versionId")
    void rollbackToVersionUnknownReturnsError(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-rb-unknown");
        ServiceResult<VersionSnapshot> r = store.rollbackToVersion(oid, "nope", "carol");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.VERSION_NOT_FOUND,
            ((ServiceResult.Error<VersionSnapshot>) r).error().code());
    }

    @Test
    @DisplayName("pruning keeps maxSnapshots and never prunes the import baseline")
    void pruningKeepsMaxAndProtectsBaseline(@TempDir Path tmp) {
        // maxSnapshots=3 forces pruning after the 3rd snapshot
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME, 3);
        OntologyId oid = new OntologyId("ont-prune");

        // Baseline (parentVersionId=null) — must NEVER be pruned
        ServiceResult<VersionSnapshot> baseline = store.createSnapshot(
            oid, buildOntology("prune-base"), "alice", null, "baseline");
        String baselineVid = ((ServiceResult.Success<VersionSnapshot>) baseline).data().versionId();

        // Add 5 more distinct snapshots (each with different content via extraAxioms)
        String prev = baselineVid;
        for (int i = 1; i <= 5; i++) {
            ServiceResult<VersionSnapshot> s = store.createSnapshot(
                oid, buildOntology("prune-" + i, i), "alice", prev, "v" + i);
            prev = ((ServiceResult.Success<VersionSnapshot>) s).data().versionId();
        }

        ServiceResult<List<VersionSnapshot>> r = store.listHistory(oid, 200);
        assertTrue(r.isSuccess());
        List<VersionSnapshot> list = ((ServiceResult.Success<List<VersionSnapshot>>) r).data();
        assertTrue(list.size() <= 3,
            "history must be pruned to <= maxSnapshots=3; got " + list.size());

        // Baseline must still be present
        assertTrue(list.stream().anyMatch(s -> s.versionId().equals(baselineVid)),
            "import baseline must never be pruned");
    }

    @Test
    @DisplayName("createSnapshot with null ontologyId returns INVALID_ARGUMENTS")
    void createSnapshotNullOntologyIdReturnsError(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        ServiceResult<VersionSnapshot> r = store.createSnapshot(
            null, buildOntology("null"), "alice", null, "x");
        assertFalse(r.isSuccess());
        assertEquals(ErrorCode.INVALID_ARGUMENTS,
            ((ServiceResult.Error<VersionSnapshot>) r).error().code());
    }

    @Test
    @DisplayName("listHistory respects limit cap")
    void listHistoryRespectsLimit(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-limit");
        for (int i = 0; i < 5; i++) {
            store.createSnapshot(oid, buildOntology("lim-" + i, i), "alice", null, "v" + i);
        }
        ServiceResult<List<VersionSnapshot>> r = store.listHistory(oid, 2);
        assertTrue(r.isSuccess());
        assertEquals(2, ((ServiceResult.Success<List<VersionSnapshot>>) r).data().size());
    }

    @Test
    @DisplayName("resolveCanonicalOntologyPath returns expected layout")
    void resolveCanonicalOntologyPath(@TempDir Path tmp) {
        VersionHistoryStore store = new VersionHistoryStore(tmp.toString(), WS_NAME);
        OntologyId oid = new OntologyId("ont-path");
        Path p = store.resolveCanonicalOntologyPath(oid);
        assertTrue(p.endsWith("ontologies/ont-path/canonical/ontology.owl"));
    }
}
