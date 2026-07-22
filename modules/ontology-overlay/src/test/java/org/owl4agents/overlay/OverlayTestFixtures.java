package org.owl4agents.overlay;

import org.owl4agents.core.OntologyId;
import org.owl4agents.owlapi.OntologyCache;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixtures for the overlay module test suite.
 */
final class OverlayTestFixtures {

    static final String SMART_HOME_NS = "https://owl4agents.org/test/smart-home#";
    static final String DEVICE_CLASS_IRI = SMART_HOME_NS + "Device";
    static final String SMART_PLUG_CLASS_IRI = SMART_HOME_NS + "SmartPlug";
    static final String HVAC_CLASS_IRI = SMART_HOME_NS + "HVACDevice";
    static final String ROOM_CLASS_IRI = SMART_HOME_NS + "Room";
    static final String USER_PERMISSION_IRI = SMART_HOME_NS + "CanControlDevices";

    private OverlayTestFixtures() {
    }

    /**
     * Build a small in-memory smart-home TBox with a class hierarchy:
     * Device ⊔ SmartPlug ⊔ HVACDevice ⊔ Room, plus SmartPlug ⊑ Device
     * and HVACDevice ⊑ Device.
     */
    static OWLOntology buildSmartHomeTBox() {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        try {
            OWLOntology ont = mgr.createOntology(IRI.create(SMART_HOME_NS));
            OWLClass device = df.getOWLClass(IRI.create(DEVICE_CLASS_IRI));
            OWLClass smartPlug = df.getOWLClass(IRI.create(SMART_PLUG_CLASS_IRI));
            OWLClass hvac = df.getOWLClass(IRI.create(HVAC_CLASS_IRI));
            OWLClass room = df.getOWLClass(IRI.create(ROOM_CLASS_IRI));
            ont.addAxiom(df.getOWLDeclarationAxiom(device));
            ont.addAxiom(df.getOWLDeclarationAxiom(smartPlug));
            ont.addAxiom(df.getOWLDeclarationAxiom(hvac));
            ont.addAxiom(df.getOWLDeclarationAxiom(room));
            ont.addAxiom(df.getOWLSubClassOfAxiom(smartPlug, device));
            ont.addAxiom(df.getOWLSubClassOfAxiom(hvac, device));
            return ont;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Materialize a smart-home ontology file on disk under the
     * {@code <tempDir>/<workspaceName>/ontologies/<id>/canonical/ontology.owl}
     * path that {@link OntologyCache} resolves.
     */
    static OntologyCache materializeWorkspace(Path tempDir, String workspaceName,
                                              String ontologyId, OWLOntology ontology)
            throws Exception {
        Path ontologyPath = tempDir.resolve(workspaceName)
            .resolve("ontologies").resolve(ontologyId)
            .resolve("canonical").resolve("ontology.owl");
        Files.createDirectories(ontologyPath.getParent());
        OWLOntologyManager mgr = ontology.getOWLOntologyManager();
        try (var out = Files.newOutputStream(ontologyPath)) {
            mgr.saveOntology(ontology, out);
        }
        OntologyCache cache = new OntologyCache(tempDir.toString(), workspaceName);
        return cache;
    }

    /**
     * Convenience: materialize the smart-home TBox under
     * {@code smart-home} and return a cache that resolves it.
     */
    static OntologyCache materializeSmartHome(Path tempDir, String workspaceName)
            throws Exception {
        OWLOntology tbox = buildSmartHomeTBox();
        return materializeWorkspace(tempDir, workspaceName, "smart-home", tbox);
    }

    /**
     * Build N device dynamic axioms: one ClassAssertion(device-i, SmartPlug)
     * plus one DataPropertyAssertion(hasState, device-i, "on"|"off") per device.
     */
    static List<org.semanticweb.owlapi.model.OWLAxiom> buildDeviceAxioms(int count) {
        OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = mgr.getOWLDataFactory();
        OWLClass smartPlug = df.getOWLClass(IRI.create(SMART_PLUG_CLASS_IRI));
        org.semanticweb.owlapi.model.OWLDataProperty hasState =
            df.getOWLDataProperty(StructuredStateConverter.HAS_STATE_IRI);
        List<org.semanticweb.owlapi.model.OWLAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            IRI deviceIri = IRI.create(SMART_HOME_NS + "device-" + i);
            OWLNamedIndividual device = df.getOWLNamedIndividual(deviceIri);
            axioms.add(df.getOWLClassAssertionAxiom(smartPlug, device));
            axioms.add(df.getOWLDataPropertyAssertionAxiom(
                hasState, device, df.getOWLLiteral((i % 2 == 0) ? "on" : "off")));
        }
        return axioms;
    }

    /**
     * Build a Turtle string representing N devices with state assertions,
     * matching {@link #buildDeviceAxioms(int)}.
     */
    static String buildDeviceTurtle(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("@prefix dyn: <").append(StructuredStateConverter.DYNAMIC_NS).append("> .\n");
        sb.append("@prefix smarthome: <").append(SMART_HOME_NS).append("> .\n");
        for (int i = 0; i < count; i++) {
            String state = (i % 2 == 0) ? "on" : "off";
            sb.append("smarthome:device-").append(i)
                .append(" a smarthome:SmartPlug ;\n")
                .append("    dyn:hasState \"").append(state).append("\" .\n");
        }
        return sb.toString();
    }

    /**
     * Build an {@link EnvironmentSnapshot} with N devices, matching
     * {@link #buildDeviceAxioms(int)}.
     */
    static EnvironmentSnapshot buildDeviceSnapshot(int count, String snapshotId) {
        List<DeviceSnapshot> devices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String state = (i % 2 == 0) ? "on" : "off";
            devices.add(new DeviceSnapshot(
                SMART_HOME_NS + "device-" + i,
                SMART_PLUG_CLASS_IRI,
                null,
                state
            ));
        }
        return new EnvironmentSnapshot(
            snapshotId,
            java.time.Instant.now(),
            "test",
            1L,
            "",
            devices,
            List.of(),
            List.of()
        );
    }

    /**
     * Helper: unwrap a successful ServiceResult or fail the test.
     */
    static <T> T unwrap(org.owl4agents.core.ServiceResult<T> result) {
        if (!result.isSuccess()) {
            org.owl4agents.core.ServiceError err =
                ((org.owl4agents.core.ServiceResult.Error<T>) result).error();
            throw new AssertionError("ServiceResult error: " + err.code() + " — " + err.message());
        }
        return ((org.owl4agents.core.ServiceResult.Success<T>) result).data();
    }
}
