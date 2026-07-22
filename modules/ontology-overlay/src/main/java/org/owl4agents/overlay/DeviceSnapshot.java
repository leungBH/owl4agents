package org.owl4agents.overlay;

import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 OV-003 / D10: Structured representation of a single device's
 * dynamic state at capture time.
 *
 * <p>Each {@code DeviceSnapshot} is converted by {@link StructuredStateConverter}
 * into a set of OWL ABox axioms:</p>
 * <ul>
 *   <li>{@code ClassAssertion(deviceIri, deviceType)}</li>
 *   <li>{@code ObjectPropertyAssertion(hasLocation, deviceIri, locationIri)}
 *       when {@code location} is present.</li>
 *   <li>{@code DataPropertyAssertion(hasState, deviceIri, stateLiteral)}
 *       when {@code state} is present.</li>
 *   <li>One {@code DataPropertyAssertion} per entry in {@code properties}.</li>
 * </ul>
 *
 * <p>The same logical state expressed as RDF (Turtle/JSON-LD/N-Triples)
 * and parsed by {@link RdfStateParser} SHALL produce an equivalent axiom
 * set (set equality ignoring blank node IDs) per OV-003.</p>
 *
 * @param deviceIri  absolute IRI of the device individual (e.g.
 *                   {@code "https://home.example.org/device/lamp-1"})
 * @param deviceType absolute IRI of the device's OWL class (e.g.
 *                   {@code "https://home.example.org/ontology#SmartPlug"})
 * @param location   absolute IRI of the device's location, or empty
 * @param state      short state string (e.g. {@code "on"}, {@code "off"}),
 *                   or empty
 * @param properties additional data properties keyed by property IRI;
 *                   values are strings (the converter wraps them as
 *                   {@code xsd:string} literals)
 */
public record DeviceSnapshot(
    String deviceIri,
    String deviceType,
    Optional<String> location,
    Optional<String> state,
    Map<String, String> properties
) {
    public DeviceSnapshot {
        if (deviceIri == null || deviceIri.isBlank()) {
            throw new IllegalArgumentException("deviceIri must not be blank");
        }
        if (deviceType == null || deviceType.isBlank()) {
            throw new IllegalArgumentException("deviceType must not be blank");
        }
        if (location == null) location = Optional.empty();
        if (state == null) state = Optional.empty();
        if (properties == null) {
            properties = Map.of();
        } else {
            properties = Map.copyOf(properties);
        }
    }

    /**
     * Convenience constructor for the common case of a device with a
     * type and an optional state but no extra properties.
     */
    public DeviceSnapshot(String deviceIri, String deviceType, String location, String state) {
        this(deviceIri, deviceType,
            Optional.ofNullable(location).filter(s -> !s.isBlank()),
            Optional.ofNullable(state).filter(s -> !s.isBlank()),
            Map.of());
    }
}
