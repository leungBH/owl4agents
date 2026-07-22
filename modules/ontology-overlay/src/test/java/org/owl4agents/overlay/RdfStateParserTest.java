package org.owl4agents.overlay;

import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-003 unit tests for {@link RdfStateParser} (OVERLAY-003,
 * OVERLAY-004).
 */
@DisplayName("RdfStateParser: Turtle/JSON-LD/N-Triples parsing")
class RdfStateParserTest {

    private static final String TURTLE = """
        @prefix dyn: <https://owl4agents.org/ontology/dynamic#> .
        @prefix smarthome: <https://owl4agents.org/test/smart-home#> .
        smarthome:device-1 a smarthome:SmartPlug ;
            dyn:hasState "on" .
        """;

    private static final String JSON_LD = """
        {
          "@context": {
            "dyn": "https://owl4agents.org/ontology/dynamic#",
            "smarthome": "https://owl4agents.org/test/smart-home#",
            "hasState": { "@id": "dyn:hasState", "@type": "http://www.w3.org/2001/XMLSchema#string" }
          },
          "@id": "smarthome:device-1",
          "@type": "smarthome:SmartPlug",
          "hasState": "on"
        }
        """;

    private static final String N_TRIPLES =
        "<https://owl4agents.org/test/smart-home#device-1> " +
        "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> " +
        "<https://owl4agents.org/test/smart-home#SmartPlug> .\n" +
        "<https://owl4agents.org/test/smart-home#device-1> " +
        "<https://owl4agents.org/ontology/dynamic#hasState> " +
        "\"on\" .\n";

    @Test
    @DisplayName("OVERLAY-003: Turtle input parsed to axioms")
    void parseTurtle() {
        Collection<OWLAxiom> axioms = RdfStateParser.parse(TURTLE, Lang.TTL);
        assertFalse(axioms.isEmpty(), "Turtle should produce at least one axiom");
        verifyDeviceAxiomsPresent(axioms);
    }

    @Test
    @DisplayName("OVERLAY-003: auto-detect parses Turtle without explicit lang")
    void parseAutoDetectTurtle() {
        Collection<OWLAxiom> axioms = RdfStateParser.parse(TURTLE);
        verifyDeviceAxiomsPresent(axioms);
    }

    @Test
    @DisplayName("OVERLAY-004: JSON-LD input parsed to axioms")
    void parseJsonLd() {
        Collection<OWLAxiom> axioms = RdfStateParser.parse(JSON_LD, Lang.JSONLD);
        verifyDeviceAxiomsPresent(axioms);
    }

    @Test
    @DisplayName("OVERLAY-003: N-Triples input parsed to axioms")
    void parseNTriples() {
        Collection<OWLAxiom> axioms = RdfStateParser.parse(N_TRIPLES, Lang.NT);
        verifyDeviceAxiomsPresent(axioms);
    }

    @Test
    @DisplayName("OVERLAY-005: Turtle and JSON-LD produce equivalent axiom sets")
    void turtleAndJsonLdEquivalent() {
        Collection<OWLAxiom> fromTurtle = RdfStateParser.parse(TURTLE, Lang.TTL);
        Collection<OWLAxiom> fromJsonLd = RdfStateParser.parse(JSON_LD, Lang.JSONLD);
        String turtleChecksum = SnapshotChecksum.compute(fromTurtle);
        String jsonLdChecksum = SnapshotChecksum.compute(fromJsonLd);
        assertEquals(turtleChecksum, jsonLdChecksum,
            "Turtle and JSON-LD representations of the same state must produce equivalent axiom sets");
    }

    @Test
    @DisplayName("OVERLAY-005: Turtle and N-Triples produce equivalent axiom sets")
    void turtleAndNTriplesEquivalent() {
        Collection<OWLAxiom> fromTurtle = RdfStateParser.parse(TURTLE, Lang.TTL);
        Collection<OWLAxiom> fromNTriples = RdfStateParser.parse(N_TRIPLES, Lang.NT);
        String turtleChecksum = SnapshotChecksum.compute(fromTurtle);
        String ntriplesChecksum = SnapshotChecksum.compute(fromNTriples);
        assertEquals(turtleChecksum, ntriplesChecksum,
            "Turtle and N-Triples representations must produce equivalent axiom sets");
    }

    @Test
    @DisplayName("Empty RDF content throws IllegalArgumentException")
    void emptyContentThrows() {
        assertThrows(IllegalArgumentException.class, () -> RdfStateParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> RdfStateParser.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> RdfStateParser.parse(null, Lang.TTL));
    }

    @Test
    @DisplayName("Malformed RDF throws IllegalArgumentException")
    void malformedRdfThrows() {
        String malformed = "this is not valid RDF @@@### !!!";
        // Should throw (auto-detect tries all three formats).
        assertThrows(IllegalArgumentException.class, () -> RdfStateParser.parse(malformed));
    }

    @Test
    @DisplayName("DynamicStateParser facade dispatches by type")
    void dynamicStateParserFacade() {
        Collection<OWLAxiom> fromTurtle = DynamicStateParser.parseTurtle(TURTLE);
        Collection<OWLAxiom> fromJsonLd = DynamicStateParser.parseJsonLd(JSON_LD);
        Collection<OWLAxiom> fromNTriples = DynamicStateParser.parseNTriples(N_TRIPLES);
        Collection<OWLAxiom> fromAuto = DynamicStateParser.parseRdf(TURTLE);

        assertFalse(fromTurtle.isEmpty());
        assertFalse(fromJsonLd.isEmpty());
        assertFalse(fromNTriples.isEmpty());
        assertFalse(fromAuto.isEmpty());

        // All four should produce equivalent axiom sets.
        String c1 = SnapshotChecksum.compute(fromTurtle);
        String c2 = SnapshotChecksum.compute(fromJsonLd);
        String c3 = SnapshotChecksum.compute(fromNTriples);
        String c4 = SnapshotChecksum.compute(fromAuto);
        assertEquals(c1, c2);
        assertEquals(c1, c3);
        assertEquals(c1, c4);
    }

    private void verifyDeviceAxiomsPresent(Collection<OWLAxiom> axioms) {
        // Expect at least one ClassAssertion and one DataPropertyAssertion.
        boolean hasClassAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLClassAssertionAxiom);
        boolean hasDataPropertyAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLDataPropertyAssertionAxiom);
        assertTrue(hasClassAssertion,
            "axioms must include a ClassAssertion for the device");
        assertTrue(hasDataPropertyAssertion,
            "axioms must include a DataPropertyAssertion for hasState");
    }
}
