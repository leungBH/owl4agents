package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-003 unit tests for {@link StructuredStateConverter} and
 * OVERLAY-005 (Java object &harr; RDF equivalence).
 *
 * <p>Verifies that structured Java objects ({@link EnvironmentSnapshot},
 * {@link DeviceSnapshot}, {@link UserContext}, {@link ToolCallCandidate})
 * are converted to the expected set of OWL axioms, and that the same
 * logical state expressed as an RDF Turtle string produces an equivalent
 * axiom set (set equality up to blank node IDs, validated via
 * {@link SnapshotChecksum#compute}).</p>
 */
@DisplayName("StructuredStateConverter: Java object -> OWL axioms")
class StructuredStateConverterTest {

    private static final String DEVICE_IRI =
        "https://owl4agents.org/test/smart-home#device-1";
    private static final String DEVICE_TYPE_IRI =
        "https://owl4agents.org/test/smart-home#SmartPlug";
    private static final String LOCATION_IRI =
        "https://owl4agents.org/test/smart-home#LivingRoom";
    private static final String USER_IRI =
        "https://owl4agents.org/test/smart-home#user-alice";
    private static final String PERMISSION_IRI =
        "https://owl4agents.org/test/smart-home#CanControlDevices";

    @Test
    @DisplayName("OVERLAY-005: DeviceSnapshot with state produces ClassAssertion + DataPropertyAssertion")
    void convertDeviceWithState() {
        DeviceSnapshot device = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "on");
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertDevice(device);

        boolean hasClassAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLClassAssertionAxiom);
        boolean hasStateAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLDataPropertyAssertionAxiom dp
                && dp.getProperty().asOWLDataProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_STATE_IRI));
        assertTrue(hasClassAssertion, "expected ClassAssertion for device");
        assertTrue(hasStateAssertion, "expected hasState DataPropertyAssertion");
    }

    @Test
    @DisplayName("OVERLAY-005: DeviceSnapshot with location adds ObjectPropertyAssertion(hasLocation)")
    void convertDeviceWithLocation() {
        DeviceSnapshot device = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, LOCATION_IRI, "on");
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertDevice(device);

        boolean hasLocationAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLObjectPropertyAssertionAxiom op
                && op.getProperty().asOWLObjectProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_LOCATION_IRI));
        assertTrue(hasLocationAssertion,
            "expected ObjectPropertyAssertion(hasLocation) when location is present");
    }

    @Test
    @DisplayName("OVERLAY-005: DeviceSnapshot properties map -> one DataPropertyAssertion per entry")
    void convertDevicePropertiesMap() {
        DeviceSnapshot device = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI,
            Optional.empty(), Optional.empty(),
            Map.of(
                StructuredStateConverter.DYNAMIC_NS + "brightness", "75",
                StructuredStateConverter.DYNAMIC_NS + "firmwareVersion", "1.2.3"
            ));
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertDevice(device);

        long propertyAssertions = axioms.stream()
            .filter(a -> a instanceof OWLDataPropertyAssertionAxiom)
            .count();
        assertEquals(2, propertyAssertions,
            "expected 2 DataPropertyAssertion axioms for the 2 properties");
    }

    @Test
    @DisplayName("OVERLAY-005: UserContext -> ClassAssertion(User) + hasPreference + permission ClassAssertion")
    void convertUser() {
        UserContext user = new UserContext(
            USER_IRI, Optional.of(LOCATION_IRI),
            List.of("prefers-quiet-mode"),
            List.of(PERMISSION_IRI));
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertUser(user);

        // ClassAssertion(user, User)
        boolean hasUserClassAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLClassAssertionAxiom ca
                && ca.getClassExpression().isOWLClass()
                && ca.getClassExpression().asOWLClass().getIRI()
                    .equals(StructuredStateConverter.USER_CLASS_IRI));
        assertTrue(hasUserClassAssertion, "expected ClassAssertion(user, User)");

        // ClassAssertion(user, PermissionClass)
        boolean hasPermissionClassAssertion = axioms.stream()
            .anyMatch(a -> a instanceof OWLClassAssertionAxiom ca
                && ca.getClassExpression().isOWLClass()
                && ca.getClassExpression().asOWLClass().getIRI().toString()
                    .equals(PERMISSION_IRI));
        assertTrue(hasPermissionClassAssertion,
            "expected ClassAssertion(user, PermissionClass)");

        // DataPropertyAssertion(hasPreference, user, "prefers-quiet-mode")
        boolean hasPreference = axioms.stream()
            .anyMatch(a -> a instanceof OWLDataPropertyAssertionAxiom dp
                && dp.getProperty().asOWLDataProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_PREFERENCE_IRI));
        assertTrue(hasPreference, "expected hasPreference DataPropertyAssertion");

        // ObjectPropertyAssertion(hasLocation, user, location)
        boolean hasLocation = axioms.stream()
            .anyMatch(a -> a instanceof OWLObjectPropertyAssertionAxiom op
                && op.getProperty().asOWLObjectProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_LOCATION_IRI));
        assertTrue(hasLocation, "expected hasLocation ObjectPropertyAssertion");
    }

    @Test
    @DisplayName("OVERLAY-005: ToolCallCandidate -> ToolCall class + hasToolName + hasArgument + requestedBy")
    void convertToolCall() {
        ToolCallCandidate call = new ToolCallCandidate(
            "call-1", "set_temperature", DEVICE_IRI,
            Map.of("target", "22", "mode", "cool"),
            USER_IRI);
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertToolCall(call);

        // ClassAssertion(call, ToolCall)
        boolean hasToolCallClass = axioms.stream()
            .anyMatch(a -> a instanceof OWLClassAssertionAxiom ca
                && ca.getClassExpression().isOWLClass()
                && ca.getClassExpression().asOWLClass().getIRI()
                    .equals(StructuredStateConverter.TOOL_CALL_CLASS_IRI));
        assertTrue(hasToolCallClass, "expected ClassAssertion(call, ToolCall)");

        // DataPropertyAssertion(hasToolName, call, "set_temperature")
        boolean hasToolName = axioms.stream()
            .anyMatch(a -> a instanceof OWLDataPropertyAssertionAxiom dp
                && dp.getProperty().asOWLDataProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_TOOL_NAME_IRI));
        assertTrue(hasToolName, "expected hasToolName DataPropertyAssertion");

        // ObjectPropertyAssertion(requestedBy, call, user)
        boolean hasRequestedBy = axioms.stream()
            .anyMatch(a -> a instanceof OWLObjectPropertyAssertionAxiom op
                && op.getProperty().asOWLObjectProperty().getIRI()
                    .equals(StructuredStateConverter.REQUESTED_BY_IRI));
        assertTrue(hasRequestedBy, "expected requestedBy ObjectPropertyAssertion");

        // Two hasArgument assertions (one per arg)
        long argCount = axioms.stream()
            .filter(a -> a instanceof OWLDataPropertyAssertionAxiom dp
                && dp.getProperty().asOWLDataProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_ARGUMENT_IRI))
            .count();
        assertEquals(2, argCount, "expected 2 hasArgument assertions for 2 arguments");
    }

    @Test
    @DisplayName("OVERLAY-005: ToolCallCandidate with targetEntity adds hasTargetEntity assertion")
    void convertToolCallWithTargetEntity() {
        ToolCallCandidate call = new ToolCallCandidate(
            "call-2", "set_brightness", DEVICE_IRI,
            Map.of("level", "80"),
            USER_IRI);
        Collection<OWLAxiom> axioms = StructuredStateConverter.convertToolCall(call);

        boolean hasTargetEntity = axioms.stream()
            .anyMatch(a -> a instanceof OWLDataPropertyAssertionAxiom dp
                && dp.getProperty().asOWLDataProperty().getIRI()
                    .equals(StructuredStateConverter.HAS_TARGET_ENTITY_IRI));
        assertTrue(hasTargetEntity, "expected hasTargetEntity DataPropertyAssertion");
    }

    @Test
    @DisplayName("OVERLAY-005: convert(EnvironmentSnapshot) merges all devices/users/tool calls")
    void convertFullSnapshot() {
        DeviceSnapshot device = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, LOCATION_IRI, "on");
        UserContext user = new UserContext(
            USER_IRI, Optional.of(LOCATION_IRI),
            List.of("prefers-quiet-mode"),
            List.of(PERMISSION_IRI));
        ToolCallCandidate call = new ToolCallCandidate(
            "call-3", "set_temperature", DEVICE_IRI,
            Map.of("target", "22"), USER_IRI);

        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-1", java.time.Instant.now(), "test", 1L, "",
            List.of(device), List.of(user), List.of(call));

        Collection<OWLAxiom> axioms = StructuredStateConverter.convert(snapshot);
        // We expect axioms from all three sources (device: 3, user: 4, call: 4 = 11).
        assertTrue(axioms.size() >= 10,
            "expected at least 10 axioms from the full snapshot, got " + axioms.size());
    }

    @Test
    @DisplayName("OVERLAY-005: convert(null) throws IllegalArgumentException")
    void convertNullThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> StructuredStateConverter.convert(null));
    }

    @Test
    @DisplayName("OVERLAY-005: Java object and equivalent RDF Turtle produce equivalent axiom sets")
    void javaObjectAndRdfEquivalent() {
        // Build the Java-object path: a single device with type SmartPlug and state "on".
        DeviceSnapshot device = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "on");
        Collection<OWLAxiom> javaAxioms = StructuredStateConverter.convertDevice(device);

        // Build the equivalent Turtle path.
        String turtle = """
            @prefix dyn: <%s> .
            @prefix smarthome: <%s> .
            smarthome:device-1 a smarthome:SmartPlug ;
                dyn:hasState "on" .
            """.formatted(
                StructuredStateConverter.DYNAMIC_NS,
                "https://owl4agents.org/test/smart-home#");
        Collection<OWLAxiom> rdfAxioms = RdfStateParser.parse(turtle,
            org.apache.jena.riot.Lang.TTL);

        String javaChecksum = SnapshotChecksum.compute(javaAxioms);
        String rdfChecksum = SnapshotChecksum.compute(rdfAxioms);
        assertEquals(javaChecksum, rdfChecksum,
            "Java-object and RDF paths must produce equivalent axiom sets for the same logical state");
    }

    @Test
    @DisplayName("OVERLAY-005: Same device state always produces the same checksum (deterministic)")
    void deterministicChecksum() {
        DeviceSnapshot device1 = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "on");
        DeviceSnapshot device2 = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "on");
        String c1 = SnapshotChecksum.compute(StructuredStateConverter.convertDevice(device1));
        String c2 = SnapshotChecksum.compute(StructuredStateConverter.convertDevice(device2));
        assertEquals(c1, c2, "checksums must match for equivalent devices");
    }

    @Test
    @DisplayName("OVERLAY-005: Different device states produce different checksums")
    void differentStatesDifferentChecksums() {
        DeviceSnapshot onDevice = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "on");
        DeviceSnapshot offDevice = new DeviceSnapshot(
            DEVICE_IRI, DEVICE_TYPE_IRI, null, "off");
        String c1 = SnapshotChecksum.compute(StructuredStateConverter.convertDevice(onDevice));
        String c2 = SnapshotChecksum.compute(StructuredStateConverter.convertDevice(offDevice));
        assertNotEquals(c1, c2, "checksums must differ for different states");
    }
}
