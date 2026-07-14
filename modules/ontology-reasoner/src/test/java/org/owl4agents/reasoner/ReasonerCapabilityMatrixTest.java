package org.owl4agents.reasoner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ReasonerCapability;
import org.owl4agents.core.model.ReasonerSelectionResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.8.5 Reasoner capability matrix tests (task 7.5)")
class ReasonerCapabilityMatrixTest {

    @Test
    @DisplayName("HermiT declares OWL 2 DL support and no explanation")
    void hermitCapabilities() {
        HermiTAdapter adapter = new HermiTAdapter();
        assertEquals("HermiT", adapter.getName());
        assertTrue(adapter.supportsConsistency(), "HermiT must support consistency");
        assertTrue(adapter.supportsTemporaryOntology(), "HermiT must support temporary ontology");
        assertFalse(adapter.supportsExplanation(), "HermiT does not support explanation");
        List<String> profiles = adapter.getSupportedProfiles();
        assertNotNull(profiles);
        assertTrue(profiles.contains("OWL 2 DL"), "HermiT must declare OWL 2 DL support");
    }

    @Test
    @DisplayName("ELK declares OWL 2 EL support and no explanation")
    void elkCapabilities() {
        ELKAdapter adapter = new ELKAdapter();
        assertEquals("ELK", adapter.getName());
        assertTrue(adapter.supportsConsistency(), "ELK must support consistency");
        assertTrue(adapter.supportsTemporaryOntology(), "ELK must support temporary ontology");
        assertFalse(adapter.supportsExplanation(), "ELK does not support explanation");
        List<String> profiles = adapter.getSupportedProfiles();
        assertNotNull(profiles);
        assertTrue(profiles.contains("OWL 2 EL"), "ELK must declare OWL 2 EL support");
    }

    @Test
    @DisplayName("Openllet declares explanation support")
    void openlletCapabilities() {
        OpenlletAdapter adapter = new OpenlletAdapter();
        assertEquals("Openllet", adapter.getName());
        assertTrue(adapter.supportsConsistency(), "Openllet must support consistency");
        assertTrue(adapter.supportsTemporaryOntology(), "Openllet must support temporary ontology");
        assertTrue(adapter.supportsExplanation(), "Openllet must support explanation");
    }

    @Test
    @DisplayName("Auto-selection: OWL 2 DL selects HermiT")
    void autoSelectionOwl2Dl() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", false);
        assertEquals("HermiT", result.reasonerName(),
            "OWL 2 DL must auto-select HermiT");
    }

    @Test
    @DisplayName("Auto-selection: explanation requested selects Openllet")
    void autoSelectionExplanation() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 DL", true);
        assertEquals("Openllet", result.reasonerName(),
            "Explanation request must select Openllet regardless of profile");
    }

    @Test
    @DisplayName("Auto-selection: OWL 2 EL selects ELK")
    void autoSelectionOwl2El() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 2 EL", false);
        assertEquals("ELK", result.reasonerName(),
            "OWL 2 EL must auto-select ELK");
    }

    @Test
    @DisplayName("Auto-selection: unknown profile returns null reasoner (PROFILE_NOT_SUPPORTED)")
    void autoSelectionUnknownProfile() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        ReasonerSelectionResult result = selector.select("OWL 99 QX", false);
        assertNull(result.reasonerName(),
            "Unknown profile must return null reasoner (caller maps to PROFILE_NOT_SUPPORTED)");
        assertTrue(result.selectionRationale().contains("PROFILE_NOT_SUPPORTED"),
            "Rationale must mention PROFILE_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("ReasonerLifecycleManager.listReasoners includes capability fields")
    void listReasonersIncludesCapabilities() {
        ReasonerLifecycleManager mgr = new ReasonerLifecycleManager();
        var listResult = mgr.listReasoners();
        assertNotNull(listResult);
        assertNotNull(listResult.reasoners());
        assertFalse(listResult.reasoners().isEmpty(),
            "At least one reasoner must be registered");

        for (ReasonerCapability cap : listResult.reasoners()) {
            assertNotNull(cap.name(), "Reasoner name must not be null");
            assertNotNull(cap.supportedProfiles(), "Profiles list must not be null");
            // v0.8.5: all current reasoners support consistency and temporary ontology
            assertTrue(cap.supportsConsistency(),
                cap.name() + " must declare supportsConsistency=true");
            assertTrue(cap.supportsTemporaryOntology(),
                cap.name() + " must declare supportsTemporaryOntology=true");
        }
    }

    @Test
    @DisplayName("isProfileSupported returns true for known profiles")
    void isProfileSupportedKnown() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        assertTrue(selector.isProfileSupported("OWL 2 DL"));
        assertTrue(selector.isProfileSupported("OWL 2 EL"));
        assertTrue(selector.isProfileSupported("OWL 2 Full"));
        assertTrue(selector.isProfileSupported("unknown")); // falls back to HermiT
    }

    @Test
    @DisplayName("isProfileSupported returns false for truly unknown profiles")
    void isProfileSupportedUnknown() {
        AutoReasonerSelector selector = new AutoReasonerSelector();
        assertFalse(selector.isProfileSupported("OWL 99 QX"),
            "Truly unknown profile should not be supported");
    }
}
