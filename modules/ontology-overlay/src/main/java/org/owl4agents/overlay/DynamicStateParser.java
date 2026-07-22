package org.owl4agents.overlay;

import org.apache.jena.riot.Lang;
import org.semanticweb.owlapi.model.OWLAxiom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * v0.8.7 OV-003 / D10: Unified entry point for converting any supported
 * dynamic state input form into a {@link Collection} of
 * {@link OWLAxiom}s.
 *
 * <p>Per the transient-overlay spec "RDF Dynamic State Input" requirement,
 * the overlay subsystem accepts four input forms:</p>
 * <ol>
 *   <li>Turtle string — delegated to {@link RdfStateParser#parse(String, Lang)}
 *       with {@link Lang#TTL}.</li>
 *   <li>JSON-LD string — delegated to {@link RdfStateParser#parse(String, Lang)}
 *       with {@link Lang#JSONLD}.</li>
 *   <li>N-Triples string — delegated to {@link RdfStateParser#parse(String, Lang)}
 *       with {@link Lang#NT}.</li>
 *   <li>Java structured objects ({@link EnvironmentSnapshot},
 *       {@link DeviceSnapshot}, {@link UserContext},
 *       {@link ToolCallCandidate}) — delegated to
 *       {@link StructuredStateConverter#convert(EnvironmentSnapshot)}.</li>
 * </ol>
 *
 * <p>The same logical state expressed as a Java object and as an RDF
 * serialization SHALL produce equivalent axiom sets (set equality up to
 * blank node identity) per OV-003 "Java object and RDF equivalence".</p>
 *
 * <p>The parser is a stateless facade; both underlying converters are
 * safe to call concurrently.</p>
 */
public final class DynamicStateParser {

    private DynamicStateParser() {
        // utility class
    }

    /**
     * Parse a Turtle string into axioms.
     */
    public static Collection<OWLAxiom> parseTurtle(String turtle) {
        return RdfStateParser.parse(turtle, Lang.TTL);
    }

    /**
     * Parse a JSON-LD string into axioms.
     */
    public static Collection<OWLAxiom> parseJsonLd(String jsonLd) {
        return RdfStateParser.parse(jsonLd, Lang.JSONLD);
    }

    /**
     * Parse an N-Triples string into axioms.
     */
    public static Collection<OWLAxiom> parseNTriples(String nTriples) {
        return RdfStateParser.parse(nTriples, Lang.NT);
    }

    /**
     * Parse an RDF string with auto-detected format (Turtle, JSON-LD,
     * or N-Triples).
     */
    public static Collection<OWLAxiom> parseRdf(String rdfContent) {
        return RdfStateParser.parse(rdfContent);
    }

    /**
     * Convert a structured {@link EnvironmentSnapshot} into axioms.
     */
    public static Collection<OWLAxiom> parse(EnvironmentSnapshot snapshot) {
        return StructuredStateConverter.convert(snapshot);
    }

    /**
     * Convert a single {@link DeviceSnapshot} into axioms.
     */
    public static Collection<OWLAxiom> parse(DeviceSnapshot device) {
        return StructuredStateConverter.convertDevice(device);
    }

    /**
     * Convert a single {@link UserContext} into axioms.
     */
    public static Collection<OWLAxiom> parse(UserContext user) {
        return StructuredStateConverter.convertUser(user);
    }

    /**
     * Convert a single {@link ToolCallCandidate} into axioms.
     */
    public static Collection<OWLAxiom> parse(ToolCallCandidate call) {
        return StructuredStateConverter.convertToolCall(call);
    }

    /**
     * Parse a mixed list of inputs. Each element may be an RDF string
     * (Turtle/JSON-LD/N-Triples) or a structured Java object
     * ({@link EnvironmentSnapshot}, {@link DeviceSnapshot},
     * {@link UserContext}, {@link ToolCallCandidate}). All resulting
     * axiom sets are merged into a single collection.
     */
    public static Collection<OWLAxiom> parseAll(List<?> inputs) {
        if (inputs == null) {
            return List.of();
        }
        List<OWLAxiom> merged = new ArrayList<>();
        for (Object input : inputs) {
            if (input == null) continue;
            Collection<OWLAxiom> batch = parseSingle(input);
            merged.addAll(batch);
        }
        return merged;
    }

    /**
     * Parse a single input of unknown type. Used internally by
     * {@link #parseAll(List)} and exposed for callers that want to
     * dispatch on a single value.
     */
    public static Collection<OWLAxiom> parseSingle(Object input) {
        if (input == null) {
            return List.of();
        }
        if (input instanceof EnvironmentSnapshot snap) {
            return StructuredStateConverter.convert(snap);
        }
        if (input instanceof DeviceSnapshot device) {
            return StructuredStateConverter.convertDevice(device);
        }
        if (input instanceof UserContext user) {
            return StructuredStateConverter.convertUser(user);
        }
        if (input instanceof ToolCallCandidate call) {
            return StructuredStateConverter.convertToolCall(call);
        }
        if (input instanceof String rdf) {
            return RdfStateParser.parse(rdf);
        }
        throw new IllegalArgumentException(
            "Unsupported dynamic state input type: " + input.getClass().getName()
                + ". Expected EnvironmentSnapshot, DeviceSnapshot, UserContext, "
                + "ToolCallCandidate, or an RDF string.");
    }
}
