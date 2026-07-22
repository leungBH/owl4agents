package org.owl4agents.shacl;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 SH-007 integration tests for the SHACL validation pipeline.
 *
 * <p>Exercises end-to-end scenarios that go beyond single-constraint unit
 * tests: ShapeRegistry lifecycle (register → resolve → invalidate → conflict
 * → force-overwrite → mtime invalidation → JSON persistence), multi-constraint
 * validation reports, and parity between the inline {@code validate()} path
 * and the {@code validateRegisteredShapes()} path.</p>
 *
 * <p>CLI/MCP parity is verified structurally: the CLI and MCP paths both
 * use {@link ShaclJsonSerializer} for canonical output, so equivalent inputs
 * (same data, same shapes — once inline, once registered) produce byte-for-byte
 * equivalent JSON bodies (modulo elapsedMs which is non-deterministic).</p>
 */
@DisplayName("SHACL integration tests")
class ShaclIntegrationTests {

    @TempDir
    Path tempDir;

    private FileShapeRegistry registry;
    private JenaShaclValidationService service;

    @BeforeEach
    void setUp() {
        registry = new FileShapeRegistry(tempDir.resolve("registry.json"));
        service = new JenaShaclValidationService(registry);
    }

    private Model turtle(String content) {
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new java.io.ByteArrayInputStream(
            content.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null, Lang.TTL);
        return m;
    }

    private Path writeShapesFile(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    // ── 1. ShapeRegistry lifecycle: register → resolve → invalidate ──

    @Test
    @DisplayName("INT-001: register a ShapeSet then resolve it via validateRegisteredShapes")
    void registerAndResolveShapeSet() throws Exception {
        Path shapesFile = writeShapesFile("person.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        var registerResult = registry.register("person", shapesFile, "test", false, false);
        assertTrue(registerResult.isSuccess(), "register should succeed");
        ShapeSet ss = ((org.owl4agents.core.ServiceResult.Success<ShapeSet>) registerResult).data();
        assertEquals("person", ss.id());
        assertNotNull(ss.checksum());
        assertEquals(64, ss.checksum().length(), "SHA256 hex digest is 64 chars");

        // Validate data against the registered ShapeSet.
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        var validateResult = service.validateRegisteredShapes("person", data,
            ShaclValidationOptions.defaults());
        assertTrue(validateResult.isSuccess());
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) validateResult).data();
        assertTrue(report.conforms());
        assertEquals(Optional.of("person"), report.shapeSetId());
    }

    // ── 2. ShapeSet id conflict without --force ──

    @Test
    @DisplayName("INT-002: registering a conflicting ShapeSet without force returns SHAPE_SET_ID_CONFLICT")
    void shapeSetIdConflictWithoutForce() throws Exception {
        Path shapes1 = writeShapesFile("v1.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        Path shapes2 = writeShapesFile("v2.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 2 ] .
            """);
        var r1 = registry.register("id-conflict", shapes1, "test", false, false);
        assertTrue(r1.isSuccess());
        var r2 = registry.register("id-conflict", shapes2, "test", false, false);
        assertFalse(r2.isSuccess());
        var error = ((org.owl4agents.core.ServiceResult.Error<ShapeSet>) r2).error();
        assertEquals(org.owl4agents.core.ErrorCode.SHAPE_SET_ID_CONFLICT, error.code());
    }

    // ── 3. ShapeSet id conflict with --force overwrites ──

    @Test
    @DisplayName("INT-003: registering a conflicting ShapeSet with force=true overwrites the existing entry")
    void shapeSetIdConflictWithForce() throws Exception {
        Path shapes1 = writeShapesFile("v1.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        Path shapes2 = writeShapesFile("v2.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 2 ] .
            """);
        var r1 = registry.register("force-test", shapes1, "test", false, false);
        assertTrue(r1.isSuccess());
        String checksum1 = ((org.owl4agents.core.ServiceResult.Success<ShapeSet>) r1).data().checksum();

        var r2 = registry.register("force-test", shapes2, "test", true, false);
        assertTrue(r2.isSuccess(), "force=true should overwrite");
        String checksum2 = ((org.owl4agents.core.ServiceResult.Success<ShapeSet>) r2).data().checksum();
        assertNotEquals(checksum1, checksum2, "checksum should differ after overwrite");

        // The new shape (minCount 2) should now be in effect.
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        var validateResult = service.validateRegisteredShapes("force-test", data,
            ShaclValidationOptions.defaults());
        assertTrue(validateResult.isSuccess());
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) validateResult).data();
        assertFalse(report.conforms(), "minCount=2 should fail with only 1 name");
    }

    // ── 4. Cache invalidation on file modification ──

    @Test
    @DisplayName("INT-004: modifying the shapes file on disk invalidates the cache and picks up new constraints")
    void cacheInvalidationOnFileModification() throws Exception {
        Path shapesFile = writeShapesFile("cache-test.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        var r1 = registry.register("cache-test", shapesFile, "test", false, false);
        assertTrue(r1.isSuccess());

        // First validation: conforms (minCount 1 met)
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        var v1 = service.validateRegisteredShapes("cache-test", data, ShaclValidationOptions.defaults());
        assertTrue(((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) v1).data().conforms());

        // Modify the shapes file in place: bump minCount to 2.
        // Sleep briefly to ensure mtime changes (filesystem mtime granularity).
        Thread.sleep(50);
        Files.writeString(shapesFile, """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 2 ] .
            """);

        // Second validation: should detect new minCount=2 and fail
        var v2 = service.validateRegisteredShapes("cache-test", data, ShaclValidationOptions.defaults());
        assertTrue(v2.isSuccess());
        ShaclValidationReport report2 = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) v2).data();
        assertFalse(report2.conforms(), "after file modification, minCount=2 should fail");
    }

    // ── 5. JSON persistence: reload registry from disk ──

    @Test
    @DisplayName("INT-005: registry.json is persisted to disk and can be reloaded by a new FileShapeRegistry instance")
    void registryJsonPersistenceAndReload() throws Exception {
        Path shapesFile = writeShapesFile("persist.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        var r1 = registry.register("persist-test", shapesFile, "test-domain", false, true);
        assertTrue(r1.isSuccess());

        // Verify registry.json was written
        Path registryJson = tempDir.resolve("registry.json");
        assertTrue(Files.exists(registryJson), "registry.json must exist after register()");

        // Create a new registry instance pointing at the same file
        FileShapeRegistry reloaded = new FileShapeRegistry(registryJson);
        Optional<ShapeSet> opt = reloaded.get("persist-test");
        assertTrue(opt.isPresent(), "reloaded registry should contain 'persist-test'");
        ShapeSet ss = opt.get();
        assertEquals("test-domain", ss.domain());
        assertTrue(ss.requiresInference(), "requiresInference flag should be persisted");
        assertEquals(shapesFile.toAbsolutePath().normalize(), ss.sourcePath());
    }

    // ── 6. CLI/MCP parity: inline validate() and validateRegisteredShapes() produce equivalent reports ──

    @Test
    @DisplayName("INT-006: inline validate() and validateRegisteredShapes() produce equivalent reports (CLI/MCP parity)")
    void cliMcpParityInlineVsRegistered() throws Exception {
        // The CLI's --shapes <file> path loads shapes inline and calls validate().
        // The MCP tool's shape_set_id path calls validateRegisteredShapes().
        // Both must produce equivalent reports for the same data + shapes.
        String shapesTtl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;
                sh:property [ sh:path ex:age ; sh:maxInclusive 120 ] .
            """;
        String dataTtl = """
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:bob a ex:Person ; ex:name "Bob" ; ex:age "150"^^xsd:integer .
            """;

        // Path A: inline validate()
        Model shapesModel = turtle(shapesTtl);
        Model dataModel = turtle(dataTtl);
        var inlineResult = service.validate(dataModel, shapesModel, ShaclValidationOptions.defaults());
        assertTrue(inlineResult.isSuccess());
        ShaclValidationReport inlineReport = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) inlineResult).data();

        // Path B: register the same shapes, then validateRegisteredShapes()
        Path shapesFile = writeShapesFile("parity.ttl", shapesTtl);
        var regResult = registry.register("parity", shapesFile, "test", false, false);
        assertTrue(regResult.isSuccess());
        var registeredResult = service.validateRegisteredShapes("parity", dataModel,
            ShaclValidationOptions.defaults());
        assertTrue(registeredResult.isSuccess());
        ShaclValidationReport registeredReport = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) registeredResult).data();

        // The two reports must be equivalent modulo shapeSetId and elapsedMs.
        assertEquals(inlineReport.conforms(), registeredReport.conforms());
        assertEquals(inlineReport.violations().size(), registeredReport.violations().size(),
            "violation count must match between inline and registered paths");
        assertEquals(inlineReport.warnings().size(), registeredReport.warnings().size());
        assertEquals(inlineReport.infos().size(), registeredReport.infos().size());
        assertEquals("1.0", inlineReport.schemaVersion());
        assertEquals("1.0", registeredReport.schemaVersion());
        assertTrue(inlineReport.shapeSetId().isEmpty(), "inline report has no shapeSetId");
        assertEquals(Optional.of("parity"), registeredReport.shapeSetId());

        // Constraint components must match in order
        for (int i = 0; i < inlineReport.violations().size(); i++) {
            ShaclViolation iv = inlineReport.violations().get(i);
            ShaclViolation rv = registeredReport.violations().get(i);
            assertEquals(iv.sourceConstraintComponent(), rv.sourceConstraintComponent(),
                "constraint component must match at index " + i);
            assertEquals(iv.focusNode(), rv.focusNode(),
                "focus node must match at index " + i);
            assertEquals(iv.severity(), rv.severity(),
                "severity must match at index " + i);
        }
    }

    // ── 7. CLI/MCP parity: canonical JSON output is structurally identical ──

    @Test
    @DisplayName("INT-007: ShaclJsonSerializer produces equivalent Maps for inline and registered reports")
    void cliMcpParityJsonOutput() throws Exception {
        String shapesTtl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:DeviceShape a sh:NodeShape ;
                sh:targetClass ex:Device ;
                sh:property [ sh:path ex:label ; sh:minCount 1 ] .
            """;
        String dataTtl = """
            @prefix ex: <http://example.org/> .
            ex:dev1 a ex:Device .
            """;

        Model shapesModel = turtle(shapesTtl);
        Model dataModel = turtle(dataTtl);
        var inlineResult = service.validate(dataModel, shapesModel, ShaclValidationOptions.defaults());
        ShaclValidationReport inlineReport = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) inlineResult).data();

        Path shapesFile = writeShapesFile("json-parity.ttl", shapesTtl);
        registry.register("json-parity", shapesFile, "test", false, false);
        var registeredResult = service.validateRegisteredShapes("json-parity", dataModel,
            ShaclValidationOptions.defaults());
        ShaclValidationReport registeredReport = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) registeredResult).data();

        Map<String, Object> inlineJson = ShaclJsonSerializer.reportToMap(inlineReport);
        Map<String, Object> registeredJson = ShaclJsonSerializer.reportToMap(registeredReport);

        // Schema-version and conform must match
        assertEquals(inlineJson.get("schemaVersion"), registeredJson.get("schemaVersion"));
        assertEquals(inlineJson.get("conforms"), registeredJson.get("conforms"));

        // shapeSetId must differ (inline=null, registered="json-parity")
        assertNull(inlineJson.get("shapeSetId"));
        assertEquals("json-parity", registeredJson.get("shapeSetId"));

        // elapsedMs is non-deterministic; just verify it's a Number
        assertInstanceOf(Number.class, inlineJson.get("elapsedMs"));
        assertInstanceOf(Number.class, registeredJson.get("elapsedMs"));

        // Violation structure must match (10 fields each)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inlineViolations = (List<Map<String, Object>>) inlineJson.get("violations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> registeredViolations = (List<Map<String, Object>>) registeredJson.get("violations");
        assertEquals(inlineViolations.size(), registeredViolations.size());
        for (int i = 0; i < inlineViolations.size(); i++) {
            assertEquals(10, inlineViolations.get(i).size(), "violation map must have exactly 10 keys");
            assertEquals(10, registeredViolations.get(i).size(), "violation map must have exactly 10 keys");
            // Constraint component and focus node must match
            assertEquals(inlineViolations.get(i).get("sourceConstraintComponent"),
                registeredViolations.get(i).get("sourceConstraintComponent"));
            assertEquals(inlineViolations.get(i).get("focusNode"),
                registeredViolations.get(i).get("focusNode"));
            assertEquals(inlineViolations.get(i).get("severity"),
                registeredViolations.get(i).get("severity"));
        }
    }

    // ── 8. Resolve non-existent ShapeSet returns SHAPE_SET_NOT_FOUND ──

    @Test
    @DisplayName("INT-008: validateRegisteredShapes with unknown shape_set_id returns SHAPE_SET_NOT_FOUND")
    void unknownShapeSetIdReturnsError() {
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person .
            """);
        var result = service.validateRegisteredShapes("does-not-exist", data,
            ShaclValidationOptions.defaults());
        assertFalse(result.isSuccess());
        var error = ((org.owl4agents.core.ServiceResult.Error<ShaclValidationReport>) result).error();
        assertEquals(org.owl4agents.core.ErrorCode.SHAPE_SET_NOT_FOUND, error.code());
    }

    // ── 9. Multi-violation report ──

    @Test
    @DisplayName("INT-009: a single data graph with multiple violations produces a multi-entry report")
    void multiViolationReport() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ; sh:datatype xsd:string ] ;
                sh:property [ sh:path ex:age ; sh:minInclusive 0 ; sh:maxInclusive 120 ] ;
                sh:property [ sh:path ex:email ; sh:nodeKind sh:IRI ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:bad a ex:Person ;
                ex:age "200"^^xsd:integer ;
                ex:email "not-an-iri" .
            """);
        // Missing name (minCount), age too high (maxInclusive), email wrong kind (nodeKind).
        var result = service.validate(data, shapes, ShaclValidationOptions.defaults());
        assertTrue(result.isSuccess());
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) result).data();
        assertFalse(report.conforms());
        assertTrue(report.violations().size() >= 3,
            "expected at least 3 violations (minCount, maxInclusive, nodeKind), got: " + report.violations().size());
    }

    // ── 10. invalidate() forces re-resolve on next call ──

    @Test
    @DisplayName("INT-010: invalidate() forces the cache to be cleared; next resolve reloads from disk")
    void invalidateForcesReload() throws Exception {
        Path shapesFile = writeShapesFile("inv.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:S a sh:NodeShape ; sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        registry.register("inv", shapesFile, "test", false, false);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        // First call: loads and caches
        var v1 = service.validateRegisteredShapes("inv", data, ShaclValidationOptions.defaults());
        assertTrue(((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) v1).data().conforms());

        // Invalidate the cache
        var inv = registry.invalidate("inv");
        assertTrue(inv.isSuccess());

        // Second call: should re-resolve from disk (still conforms)
        var v2 = service.validateRegisteredShapes("inv", data, ShaclValidationOptions.defaults());
        assertTrue(v2.isSuccess());
        assertTrue(((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) v2).data().conforms());
    }

    // ── 11. list() returns all registered ShapeSets ──

    @Test
    @DisplayName("INT-011: list() returns all registered ShapeSets in registration order")
    void listReturnsAllShapeSets() throws Exception {
        Path f1 = writeShapesFile("a.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:A a sh:NodeShape ; sh:targetClass ex:A .
            """);
        Path f2 = writeShapesFile("b.ttl", """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:B a sh:NodeShape ; sh:targetClass ex:B .
            """);
        registry.register("a", f1, "domain-a", false, false);
        registry.register("b", f2, "domain-b", false, false);
        List<ShapeSet> list = registry.list();
        assertEquals(2, list.size());
        // Verify both IDs are present
        List<String> ids = list.stream().map(ShapeSet::id).toList();
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }

    // ── 12. get() returns Optional.empty() for unknown id ──

    @Test
    @DisplayName("INT-012: get() returns Optional.empty() for unknown id")
    void getReturnsEmptyForUnknownId() {
        Optional<ShapeSet> opt = registry.get("non-existent");
        assertTrue(opt.isEmpty());
        // Also null safety
        assertTrue(registry.get(null).isEmpty());
    }
}
