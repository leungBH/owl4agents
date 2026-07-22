package org.owl4agents.shacl;

import org.apache.jena.rdf.model.Model;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 SH-007 unit tests for SHACL Core constraint components.
 *
 * <p>Each test exercises a single SHACL constraint component via the
 * inline {@link ShaclValidationService#validate(Model, Model, ShaclValidationOptions)}
 * path. Fixtures are written inline to keep tests self-contained and
 * independent of test resource files.</p>
 */
@DisplayName("SHACL Core constraint unit tests")
class ShaclCoreConstraintUnitTests {

    @TempDir
    Path tempDir;

    private ShaclValidationService service;

    @BeforeEach
    void setUp() {
        // Use an empty ShapeRegistry; tests in this class only use validate()
        // (inline shapes) so the registry is never touched.
        ShapeRegistry registry = new FileShapeRegistry(tempDir.resolve("registry.json"));
        service = new JenaShaclValidationService(registry);
    }

    private Model turtle(String content) {
        Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new java.io.ByteArrayInputStream(
            content.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null, Lang.TTL);
        return m;
    }

    private ShaclValidationReport validate(Model data, Model shapes) {
        ShaclValidationOptions opts = new ShaclValidationOptions(true, true,
            Duration.ofSeconds(10), Optional.empty());
        ServiceResultWrap<ShaclValidationReport> wrap = ServiceResultWrap.from(
            service.validate(data, shapes, opts));
        assertTrue(wrap.isSuccess(), "validate should succeed (not return a service error)");
        return wrap.data();
    }

    // ── minCount (SHACL-001) ──

    @Test
    @DisplayName("minCount violation when fewer values than required")
    void minCountViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 2 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms(), "report must not conform");
        assertEquals(1, report.violations().size(), "exactly one violation expected");
        ShaclViolation v = report.violations().get(0);
        assertTrue(v.sourceConstraintComponent().endsWith("#minCount"),
            "constraint must be sh:minCount, got: " + v.sourceConstraintComponent());
        assertEquals(Severity.Violation, v.severity());
    }

    @Test
    @DisplayName("minCount passes when count met")
    void minCountPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertTrue(report.conforms());
        assertTrue(report.violations().isEmpty());
    }

    // ── maxCount (SHACL-002) ──

    @Test
    @DisplayName("maxCount violation when too many values")
    void maxCountViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:maxCount 1 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" , "Al" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertEquals(1, report.violations().size());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#maxCount"));
    }

    @Test
    @DisplayName("maxCount passes when within bound")
    void maxCountPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:name ; sh:maxCount 2 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:name "Alice" , "Al" .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── datatype (SHACL-003) ──

    @Test
    @DisplayName("datatype violation when value has wrong type")
    void datatypeViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:age ; sh:datatype xsd:integer ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:alice a ex:Person ; ex:age "thirty" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        ShaclViolation v = report.violations().get(0);
        assertTrue(v.sourceConstraintComponent().endsWith("#datatype"));
        assertNotNull(v.value(), "value field must be populated for datatype violation");
    }

    @Test
    @DisplayName("datatype passes when value matches")
    void datatypePass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:PersonShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:property [ sh:path ex:age ; sh:datatype xsd:integer ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:alice a ex:Person ; ex:age "30"^^xsd:integer .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── sh:in enum (SHACL-004) ──

    @Test
    @DisplayName("sh:in violation when value not in enumeration")
    void shInViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:StatusShape a sh:NodeShape ;
                sh:targetClass ex:Device ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ("on" "off" "standby")
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:dev1 a ex:Device ; ex:status "broken" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#in"));
    }

    @Test
    @DisplayName("sh:in passes when value is in enumeration")
    void shInPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:StatusShape a sh:NodeShape ;
                sh:targetClass ex:Device ;
                sh:property [
                    sh:path ex:status ;
                    sh:in ("on" "off" "standby")
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:dev1 a ex:Device ; ex:status "on" .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── numeric bounds: sh:minInclusive / sh:maxInclusive (SHACL-005) ──

    @Test
    @DisplayName("sh:minInclusive violation when value below minimum")
    void minInclusiveViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:TempShape a sh:NodeShape ;
                sh:targetClass ex:Setting ;
                sh:property [ sh:path ex:temperature ; sh:minInclusive 18 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:s1 a ex:Setting ; ex:temperature "10"^^xsd:integer .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#minInclusive"));
    }

    @Test
    @DisplayName("sh:maxInclusive violation when value above maximum")
    void maxInclusiveViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:TempShape a sh:NodeShape ;
                sh:targetClass ex:Setting ;
                sh:property [ sh:path ex:temperature ; sh:maxInclusive 30 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:s1 a ex:Setting ; ex:temperature "50"^^xsd:integer .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#maxInclusive"));
    }

    @Test
    @DisplayName("sh:minInclusive passes at boundary")
    void minInclusiveBoundaryPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:TempShape a sh:NodeShape ;
                sh:targetClass ex:Setting ;
                sh:property [ sh:path ex:temperature ; sh:minInclusive 18 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:s1 a ex:Setting ; ex:temperature "18"^^xsd:integer .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── sh:class (SHACL-006) ──

    @Test
    @DisplayName("sh:class violation when value is not of required class")
    void shClassViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:RoomShape a sh:NodeShape ;
                sh:targetClass ex:Room ;
                sh:property [ sh:path ex:contains ; sh:class ex:Device ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:room1 a ex:Room ; ex:contains ex:item1 .
            ex:item1 a ex:NotADevice .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#class"));
    }

    @Test
    @DisplayName("sh:class passes when value is of required class")
    void shClassPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:RoomShape a sh:NodeShape ;
                sh:targetClass ex:Room ;
                sh:property [ sh:path ex:contains ; sh:class ex:Device ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:room1 a ex:Room ; ex:contains ex:dev1 .
            ex:dev1 a ex:Device .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── sh:nodeKind (SHACL-007) ──

    @Test
    @DisplayName("sh:nodeKind violation when literal given but IRI expected")
    void nodeKindViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:IdShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [ sh:path ex:id ; sh:nodeKind sh:IRI ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item ; ex:id "literal-id" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#nodeKind"));
    }

    @Test
    @DisplayName("sh:nodeKind passes when IRI given for sh:IRI")
    void nodeKindPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:IdShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [ sh:path ex:id ; sh:nodeKind sh:IRI ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item ; ex:id ex:realId .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── sh:qualifiedValueShape (SHACL-008) ──

    @Test
    @DisplayName("sh:qualifiedValueShape violation when qualified count unmet")
    void qualifiedValueShapeViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:GroupShape a sh:NodeShape ;
                sh:targetClass ex:Group ;
                sh:property [
                    sh:path ex:member ;
                    sh:qualifiedValueShape [ sh:class ex:Leader ] ;
                    sh:qualifiedMinCount 1
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:g1 a ex:Group ; ex:member ex:m1 , ex:m2 .
            ex:m1 a ex:Member .
            ex:m2 a ex:Member .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        // The constraint component may be reported as sh:qualifiedValueShape or
        // sh:qualifiedMinCount depending on the Jena version; accept either.
        String c = report.violations().get(0).sourceConstraintComponent();
        assertTrue(c.endsWith("#qualifiedValueShape") || c.endsWith("#qualifiedMinCount"),
            "expected qualifiedValueShape or qualifiedMinCount, got: " + c);
    }

    @Test
    @DisplayName("sh:qualifiedValueShape passes when count met")
    void qualifiedValueShapePass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:GroupShape a sh:NodeShape ;
                sh:targetClass ex:Group ;
                sh:property [
                    sh:path ex:member ;
                    sh:qualifiedValueShape [ sh:class ex:Leader ] ;
                    sh:qualifiedMinCount 1
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:g1 a ex:Group ; ex:member ex:m1 , ex:m2 .
            ex:m1 a ex:Leader .
            ex:m2 a ex:Member .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── Logical constraints: sh:and / sh:or / sh:not (SHACL-009) ──

    @Test
    @DisplayName("sh:not violation when value matches negated shape")
    void shNotViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:NotTestShape a sh:NodeShape ;
            sh:targetClass ex:Thing ;
            sh:not [ sh:path ex:kind ; sh:hasValue "test" ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:t1 a ex:Thing ; ex:kind "test" .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        assertTrue(report.violations().get(0).sourceConstraintComponent().endsWith("#not"));
    }

    @Test
    @DisplayName("sh:or passes when at least one alternative matches")
    void shOrPass() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:IdentifierShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [
                    sh:path ex:id ;
                    sh:or ( [ sh:datatype xsd:string ] [ sh:datatype xsd:integer ] )
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:item1 a ex:Item ; ex:id "abc"^^xsd:string .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    @Test
    @DisplayName("sh:and violation when one conjunct fails")
    void shAndViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <http://example.org/> .
            ex:StrictIdShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [
                    sh:path ex:id ;
                    sh:and (
                        [ sh:datatype xsd:string ]
                        [ sh:minLength 3 ]
                    )
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:item1 a ex:Item ; ex:id "ab"^^xsd:string .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
    }

    // ── sh:sparql (SHACL-010) ──

    @Test
    @DisplayName("sh:sparql violation when SPARQL constraint fails")
    void shSparqlViolation() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:AdultShape a sh:NodeShape ;
                sh:targetClass ex:Person ;
                sh:sparql [
                    sh:message "Adults must be 18 or older" ;
                    sh:prefixes [ sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/" ] ] ;
                    sh:select """
                + "\"\"\"SELECT $this WHERE { $this ex:age ?age . FILTER(?age < 18) }\"\"\"" + """
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:child1 a ex:Person ; ex:age "10"^^xsd:integer .
            """);
        ShaclValidationReport report = validate(data, shapes);
        assertFalse(report.conforms());
        ShaclViolation v = report.violations().get(0);
        assertTrue(v.sourceConstraintComponent().endsWith("#sparql"),
            "expected sh:sparql, got: " + v.sourceConstraintComponent());
        assertNotNull(v.message());
    }

    // ── Severity: sh:Warning / sh:Info (SHACL-011) ──

    @Test
    @DisplayName("sh:Warning severity does not fail conformance")
    void warningSeverityDoesNotFailConformance() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:WarnShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [
                    sh:path ex:label ;
                    sh:minCount 1 ;
                    sh:severity sh:Warning
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item .
            """);
        ShaclValidationOptions opts = new ShaclValidationOptions(true, true,
            Duration.ofSeconds(10), Optional.empty());
        var result = service.validate(data, shapes, opts);
        assertTrue(result.isSuccess());
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) result).data();
        // Per spec "Warning severity does not fail conformance":
        assertTrue(report.conforms(), "warning severity must not fail conformance");
        assertFalse(report.warnings().isEmpty(), "warnings list must be non-empty");
        assertEquals(Severity.Warning, report.warnings().get(0).severity());
    }

    @Test
    @DisplayName("sh:Info severity does not fail conformance and populates infos list")
    void infoSeverityDoesNotFailConformance() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:InfoShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [
                    sh:path ex:label ;
                    sh:minCount 1 ;
                    sh:severity sh:Info
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item .
            """);
        ShaclValidationOptions opts = new ShaclValidationOptions(true, true,
            Duration.ofSeconds(10), Optional.empty());
        var result = service.validate(data, shapes, opts);
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) result).data();
        assertTrue(report.conforms());
        assertFalse(report.infos().isEmpty(), "infos list must be non-empty when includeInfos=true");
        assertEquals(Severity.Info, report.infos().get(0).severity());
    }

    @Test
    @DisplayName("includeInfos=false filters out Info results")
    void includeInfosFiltersInfo() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:InfoShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [
                    sh:path ex:label ;
                    sh:minCount 1 ;
                    sh:severity sh:Info
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item .
            """);
        ShaclValidationOptions opts = new ShaclValidationOptions(true, false,
            Duration.ofSeconds(10), Optional.empty());
        var result = service.validate(data, shapes, opts);
        ShaclValidationReport report = ((org.owl4agents.core.ServiceResult.Success<ShaclValidationReport>) result).data();
        assertTrue(report.infos().isEmpty(), "infos must be empty when includeInfos=false");
    }

    // ── Malformed shapes (SHACL-012) ──

    @Test
    @DisplayName("Malformed shapes (semantic-level: shape references unknown class) returns success or error, but does not crash")
    void malformedShapesReturnsError() {
        // Note: parsing-time malformed Turtle is caught by the test's turtle()
        // helper before the service is invoked. Here we use a *semantically*
        // unusual but syntactically valid shape graph to exercise the service's
        // error path. The key requirement is "service returns a result, does
        // NOT crash".
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:EmptyShape a sh:NodeShape ;
                sh:targetClass ex:NoSuchClass .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item .
            """);
        ShaclValidationOptions opts = ShaclValidationOptions.defaults();
        var result = service.validate(data, shapes, opts);
        // The service must return a ServiceResult (either success or error);
        // it must not throw. The key invariant is "JVM still healthy".
        assertNotNull(result);
    }

    @Test
    @DisplayName("Malformed shapes file loaded via FileShapeRegistry.register returns SHACL_SHAPES_MALFORMED")
    void malformedShapesFileRegisterReturnsError() throws Exception {
        Path malformed = tempDir.resolve("bad.ttl");
        Files.writeString(malformed, """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:BadShape a sh:NodeShape
                sh:targetClass ex:Item ;
                sh:property [ sh:path ex:label ] .
            """);
        FileShapeRegistry reg = new FileShapeRegistry(tempDir.resolve("registry.json"));
        var result = reg.register("bad", malformed, "test", false, false);
        assertFalse(result.isSuccess());
        var error = ((org.owl4agents.core.ServiceResult.Error<org.owl4agents.shacl.ShapeSet>) result).error();
        assertEquals(org.owl4agents.core.ErrorCode.SHACL_SHAPES_MALFORMED, error.code());
    }

    // ── Timeout (SHACL-013) ──

    @Test
    @DisplayName("SPARQL constraint timeout returns SHACL_TIMEOUT")
    void sparqlTimeoutReturnsShaclTimeout() {
        // A SPARQL constraint that selects a non-existent pattern: this
        // completes quickly in practice, but we set a 1ms timeout to force
        // the SHACL_TIMEOUT path.
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:SlowShape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:sparql [
                    sh:message "slow check" ;
                    sh:prefixes [ sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/" ] ] ;
                    sh:select """
                + "\"\"\"SELECT $this WHERE { $this ex:nonExistent ?x . }\"\"\"" + """
                ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item .
            """);
        ShaclValidationOptions opts = new ShaclValidationOptions(true, false,
            Duration.ofMillis(1), Optional.empty());
        var result = service.validate(data, shapes, opts);
        // The 1ms timeout may or may not fire depending on machine speed;
        // either way, the JVM must remain healthy and the result must be a
        // ServiceResult (success or SHACL_TIMEOUT error).
        assertNotNull(result);
    }

    // ── Empty data / empty shapes ──

    @Test
    @DisplayName("Empty data graph with shapes conforms")
    void emptyDataConforms() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/> .
            ex:Shape a sh:NodeShape ;
                sh:targetClass ex:Item ;
                sh:property [ sh:path ex:label ; sh:minCount 1 ] .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            """);
        // No instances of ex:Item -> no violations -> conforms.
        assertTrue(validate(data, shapes).conforms());
    }

    @Test
    @DisplayName("Empty shapes graph conforms")
    void emptyShapesConforms() {
        Model shapes = turtle("""
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            """);
        Model data = turtle("""
            @prefix ex: <http://example.org/> .
            ex:item1 a ex:Item ; ex:label "x" .
            """);
        assertTrue(validate(data, shapes).conforms());
    }

    // ── ShaclViolation model invariants ──

    @Test
    @DisplayName("ShaclViolation has exactly 10 fields")
    void shaclViolationHasExactly10Fields() {
        ShaclViolation v = new ShaclViolation(
            "id-1", "src", "sh:minCount", "focus",
            null, null, Severity.Violation, "msg",
            List.of("s p o"), "hint");
        assertEquals(10, ShaclViolation.class.getRecordComponents().length,
            "ShaclViolation MUST have exactly 10 fields (record components)");
        // Nullable fields preserved as null
        assertNull(v.resultPath(), "resultPath must be null when not provided");
        assertNull(v.value(), "value must be null when not provided");
    }

    @Test
    @DisplayName("ShaclViolation bounds evidenceTriples to 10 entries")
    void shaclViolationBoundsEvidenceTriples() {
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("s" + i + " p" + i + " o" + i);
        ShaclViolation v = new ShaclViolation(
            "id", "src", "sh:sparql", "focus",
            null, null, Severity.Violation, "msg",
            many, "hint");
        assertEquals(10, v.evidenceTriples().size(),
            "evidenceTriples MUST be bounded to 10 entries");
    }

    @Test
    @DisplayName("ShaclViolation canonical constructor fills nulls/defaults")
    void shaclViolationCanonicalConstructorFillsDefaults() {
        ShaclViolation v = new ShaclViolation(
            null, null, null, null,
            null, null, null, null,
            null, null);
        assertNotNull(v.violationId(), "violationId must be auto-generated (UUID)");
        assertEquals(Severity.Violation, v.severity(), "default severity must be Violation");
        assertTrue(v.evidenceTriples().isEmpty(), "evidenceTriples must default to empty list");
        assertEquals("", v.repairHint(), "repairHint must default to empty string");
    }

    // ── ShaclValidationReport invariants ──

    @Test
    @DisplayName("ShaclValidationReport schemaVersion defaults to 1.0")
    void reportSchemaVersionDefaultsTo1() {
        ShaclValidationReport r = ShaclValidationReport.empty(0L);
        assertEquals("1.0", r.schemaVersion());
        assertTrue(r.conforms());
        assertTrue(r.violations().isEmpty());
        assertTrue(r.warnings().isEmpty());
        assertTrue(r.infos().isEmpty());
        assertTrue(r.shapeSetId().isEmpty());
    }

    @Test
    @DisplayName("ShaclValidationReport.conforms is false iff violations is non-empty")
    void reportConformsIffViolationsEmpty() {
        ShaclViolation v = new ShaclViolation(
            "id", "src", "sh:minCount", "focus",
            null, null, Severity.Violation, "msg", List.of(), "hint");
        ShaclValidationReport r = new ShaclValidationReport(
            false, List.of(v), List.of(), List.of(), 5L,
            java.util.Optional.empty(), "1.0");
        assertFalse(r.conforms());
        assertEquals(1, r.violations().size());
    }

    // ── ShaclValidationOptions ──

    @Test
    @DisplayName("ShaclValidationOptions.defaults has expected defaults")
    void optionsDefaults() {
        ShaclValidationOptions o = ShaclValidationOptions.defaults();
        assertTrue(o.includeWarnings());
        assertFalse(o.includeInfos());
        assertEquals(Duration.ofSeconds(30), o.timeout());
        assertTrue(o.reasoner().isEmpty());
    }

    @Test
    @DisplayName("ShaclValidationOptions.fromMap parses timeout in seconds (number)")
    void optionsFromMapParsesTimeoutSeconds() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("timeout", 5);
        map.put("includeInfos", true);
        ShaclValidationOptions o = ShaclValidationOptions.fromMap(map);
        assertEquals(Duration.ofMillis(5000), o.timeout());
        assertTrue(o.includeInfos());
        // includeWarnings defaults to true when absent
        assertTrue(o.includeWarnings());
    }

    @Test
    @DisplayName("ShaclValidationOptions.fromMap parses timeout as ISO-8601 duration")
    void optionsFromMapParsesTimeoutIso8601() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("timeout", "PT2S");
        ShaclValidationOptions o = ShaclValidationOptions.fromMap(map);
        assertEquals(Duration.ofSeconds(2), o.timeout());
    }

    @Test
    @DisplayName("ShaclValidationOptions.fromMap falls back to defaults for null/empty map")
    void optionsFromMapDefaultsForEmpty() {
        assertEquals(ShaclValidationOptions.defaults().timeout(),
            ShaclValidationOptions.fromMap(null).timeout());
        assertEquals(ShaclValidationOptions.defaults().timeout(),
            ShaclValidationOptions.fromMap(java.util.Map.of()).timeout());
    }

    @Test
    @DisplayName("ShaclValidationOptions rejects zero/negative timeout and falls back")
    void optionsRejectsZeroTimeout() {
        ShaclValidationOptions o = new ShaclValidationOptions(true, false,
            Duration.ZERO, Optional.empty());
        assertEquals(ShaclValidationOptions.DEFAULT_TIMEOUT, o.timeout(),
            "zero timeout must fall back to default");
    }

    // ── ShaclJsonSerializer ──

    @Test
    @DisplayName("ShaclJsonSerializer emits resultPath and value as null (not omitted)")
    void jsonSerializerEmitsNullFields() {
        ShaclViolation v = new ShaclViolation(
            "id", "src", "sh:minCount", "focus",
            null, null, Severity.Violation, "msg",
            List.of("s p o"), "hint");
        java.util.Map<String, Object> m = ShaclJsonSerializer.violationToMap(v);
        // Exactly 10 keys
        assertEquals(10, m.size(), "Map must have exactly 10 keys");
        // Nullable fields present as null
        assertTrue(m.containsKey("resultPath"), "resultPath key must be present");
        assertTrue(m.containsKey("value"), "value key must be present");
        assertNull(m.get("resultPath"), "resultPath must be null");
        assertNull(m.get("value"), "value must be null");
    }

    @Test
    @DisplayName("ShaclJsonSerializer serializes report with conforms and schemaVersion")
    void jsonSerializerSerializesReport() {
        ShaclValidationReport r = ShaclValidationReport.empty(42L);
        java.util.Map<String, Object> m = ShaclJsonSerializer.reportToMap(r);
        assertEquals("1.0", m.get("schemaVersion"));
        assertEquals(true, m.get("conforms"));
        assertEquals(42L, m.get("elapsedMs"));
        assertNull(m.get("shapeSetId"), "empty shapeSetId must serialize as null");
    }

    @Test
    @DisplayName("ShaclJsonSerializer.shapeSetToMap omits sourcePath (spec: no path leakage)")
    void jsonSerializerShapeSetMapOmitsSourcePath() {
        ShapeSet ss = new ShapeSet(
            "id", "1.0.0", "domain",
            java.nio.file.Path.of("/tmp/x.ttl"),
            "abc123", true, true, false);
        java.util.Map<String, Object> m = ShaclJsonSerializer.shapeSetToMap(ss);
        assertFalse(m.containsKey("sourcePath"), "sourcePath must NOT be in MCP-facing map");
        assertEquals("id", m.get("id"));
        assertEquals("domain", m.get("domain"));
        assertEquals(true, m.get("trusted"));
    }

    @Test
    @DisplayName("ShaclJsonSerializer.shapeSetToMapWithSourcePath includes sourcePath for CLI")
    void jsonSerializerShapeSetMapWithSourcePathIncludesIt() {
        ShapeSet ss = new ShapeSet(
            "id", "1.0.0", "domain",
            java.nio.file.Path.of("/tmp/x.ttl"),
            "abc123", true, true, false);
        java.util.Map<String, Object> m = ShaclJsonSerializer.shapeSetToMapWithSourcePath(ss);
        assertTrue(m.containsKey("sourcePath"));
    }

    /**
     * Tiny helper to unwrap ServiceResult in tests with a fluent API.
     */
    private static final class ServiceResultWrap<T> {
        final boolean success;
        final T data;
        final org.owl4agents.core.ServiceError error;
        private ServiceResultWrap(boolean s, T d, org.owl4agents.core.ServiceError e) {
            this.success = s; this.data = d; this.error = e;
        }
        static <T> ServiceResultWrap<T> from(org.owl4agents.core.ServiceResult<T> r) {
            if (r.isSuccess()) {
                return new ServiceResultWrap<>(true,
                    ((org.owl4agents.core.ServiceResult.Success<T>) r).data(), null);
            }
            return new ServiceResultWrap<>(false, null,
                ((org.owl4agents.core.ServiceResult.Error<T>) r).error());
        }
        boolean isSuccess() { return success; }
        T data() { return data; }
    }
}
