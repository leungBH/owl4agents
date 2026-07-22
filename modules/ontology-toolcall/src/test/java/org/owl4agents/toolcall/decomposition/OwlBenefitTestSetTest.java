package org.owl4agents.toolcall.decomposition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 CL-004 / CL-007: Tests for the OWL Independent Benefit Test Suite
 * loader ({@link OwlBenefitTestSet}).
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>The test set loads successfully from the canonical classpath resource</li>
 *   <li>The suite contains at least 100 cases (spec mandate)</li>
 *   <li>Each case demonstrates a problem that can only be resolved by OWL
 *       reasoning (lookupOnlyFails=true)</li>
 *   <li>The suite covers multiple categories (capability entailment,
 *       disjointness, transitivity, permission inheritance, etc.)</li>
 *   <li>The {@link OwlBenefitTestSet#casesByCategory(String)} filter works</li>
 * </ul>
 */
@DisplayName("CL-004 OWL Independent Benefit Test Suite")
class OwlBenefitTestSetTest {

    @Test
    @DisplayName("Test set loads from classpath resource")
    void loadsFromClasspath() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        assertNotNull(set);
        assertEquals("owl-benefit-test-set.json", set.sourcePath());
        assertFalse(set.cases().isEmpty(), "Test set should not be empty");
    }

    @Test
    @DisplayName("Test set contains at least 100 cases (spec mandate)")
    void containsAtLeast100Cases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        assertTrue(set.meetsMinimumSize(),
            "Test set must contain at least " + OwlBenefitTestSet.MIN_REQUIRED_CASES
                + " cases; got " + set.size());
        assertTrue(set.size() >= 100,
            "Spec mandates >= 100 cases; got " + set.size());
    }

    @Test
    @DisplayName("Every case has lookupOnlyFails=true (reasoning is necessary)")
    void everyCaseRequiresReasoning() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        for (OwlBenefitTestSet.OwlBenefitTestCase c : set.cases()) {
            assertTrue(c.lookupOnlyFails(),
                "Case " + c.id() + " must have lookupOnlyFails=true "
                    + "(spec: each case demonstrates OWL reasoning is necessary)");
        }
    }

    @Test
    @DisplayName("Every case has non-empty assertedFact and inferredFact")
    void everyCaseHasAssertedAndInferredFacts() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        for (OwlBenefitTestSet.OwlBenefitTestCase c : set.cases()) {
            assertFalse(c.assertedFact().isBlank(),
                "Case " + c.id() + " must have a non-empty assertedFact");
            assertFalse(c.inferredFact().isBlank(),
                "Case " + c.id() + " must have a non-empty inferredFact");
            assertFalse(c.ontologyAxiom().isBlank(),
                "Case " + c.id() + " must have a non-empty ontologyAxiom");
        }
    }

    @Test
    @DisplayName("Test set covers multiple reasoning categories")
    void coversMultipleCategories() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        // The spec mentions: capability entailment, disjointness detection,
        // transitivity, permission inheritance, equivalent classes, etc.
        // Verify at least 4 distinct categories are represented.
        long distinctCategories = set.cases().stream()
            .map(OwlBenefitTestSet.OwlBenefitTestCase::category)
            .distinct()
            .count();
        assertTrue(distinctCategories >= 4,
            "Test set should cover at least 4 distinct categories; got " + distinctCategories);
    }

    @Test
    @DisplayName("Test set includes capability entailment cases (CoolingOnlyDevice → HVACDevice → TemperatureControl)")
    void includesCapabilityEntailmentCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> capabilityCases =
            set.casesByCategory("capability_entailment");
        assertFalse(capabilityCases.isEmpty(),
            "Test set must include capability_entailment cases");
        assertTrue(capabilityCases.size() >= 20,
            "Test set should include at least 20 capability_entailment cases; got "
                + capabilityCases.size());

        // Verify the canonical example: CoolingOnlyDevice subclassOf HVACDevice
        boolean hasCoolingOnlyCase = capabilityCases.stream()
            .anyMatch(c -> c.description().contains("CoolingOnlyDevice")
                || c.assertedFact().contains("CoolingOnlyDevice"));
        assertTrue(hasCoolingOnlyCase,
            "Test set must include the canonical CoolingOnlyDevice → HVACDevice case");
    }

    @Test
    @DisplayName("Test set includes disjointness cases")
    void includesDisjointnessCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> disjointnessCases =
            set.casesByCategory("disjointness");
        assertFalse(disjointnessCases.isEmpty(),
            "Test set must include disjointness cases");
        assertTrue(disjointnessCases.size() >= 10,
            "Test set should include at least 10 disjointness cases; got "
                + disjointnessCases.size());
    }

    @Test
    @DisplayName("Test set includes relation transitivity cases")
    void includesTransitivityCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> transitivityCases =
            set.casesByCategory("relation_transitivity");
        assertFalse(transitivityCases.isEmpty(),
            "Test set must include relation_transitivity cases");
    }

    @Test
    @DisplayName("Test set includes permission inheritance cases")
    void includesPermissionInheritanceCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> permissionCases =
            set.casesByCategory("permission_inheritance");
        assertFalse(permissionCases.isEmpty(),
            "Test set must include permission_inheritance cases");
    }

    @Test
    @DisplayName("Test set includes equivalent_classes cases")
    void includesEquivalentClassesCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> equivCases =
            set.casesByCategory("equivalent_classes");
        assertFalse(equivCases.isEmpty(),
            "Test set must include equivalent_classes cases");
    }

    @Test
    @DisplayName("Test set includes property_subproperty cases")
    void includesPropertySubpropertyCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> subpropCases =
            set.casesByCategory("property_subproperty");
        assertFalse(subpropCases.isEmpty(),
            "Test set must include property_subproperty cases");
    }

    @Test
    @DisplayName("Test set includes union_intersection cases")
    void includesUnionIntersectionCases() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> unionCases =
            set.casesByCategory("union_intersection");
        assertFalse(unionCases.isEmpty(),
            "Test set must include union_intersection cases");
    }

    @Test
    @DisplayName("casesByCategory filter is case-insensitive")
    void casesByCategoryCaseInsensitive() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        List<OwlBenefitTestSet.OwlBenefitTestCase> upper = set.casesByCategory("CAPABILITY_ENTAILMENT");
        List<OwlBenefitTestSet.OwlBenefitTestCase> lower = set.casesByCategory("capability_entailment");
        assertEquals(upper.size(), lower.size(),
            "Filter should be case-insensitive");
    }

    @Test
    @DisplayName("Test set cases include both supported and contradicted expected verdicts")
    void includesSupportedAndContradictedVerdicts() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        boolean hasSupported = set.cases().stream()
            .anyMatch(c -> "supported".equalsIgnoreCase(c.expectedVerdict()));
        boolean hasContradicted = set.cases().stream()
            .anyMatch(c -> "contradicted".equalsIgnoreCase(c.expectedVerdict()));
        assertTrue(hasSupported, "Test set must include 'supported' verdict cases");
        assertTrue(hasContradicted, "Test set must include 'contradicted' verdict cases");
    }

    @Test
    @DisplayName("loadFromClasspath throws if resource not found")
    void loadFromClasspathThrowsIfMissing() {
        assertThrows(IOException.class,
            () -> OwlBenefitTestSet.loadFromClasspath("nonexistent-resource.json"));
    }

    @Test
    @DisplayName("Test set is immutable (cases list cannot be modified)")
    void testSetIsImmutable() throws IOException {
        OwlBenefitTestSet set = OwlBenefitTestSet.loadFromClasspath();
        assertThrows(UnsupportedOperationException.class,
            () -> set.cases().add(new OwlBenefitTestSet.OwlBenefitTestCase(
                "x", "y", "z", "a", "b", "c", "supported", "", "", true)));
    }

    @Test
    @DisplayName("OwlBenefitTestCase canonical constructor validates inputs")
    void testCaseValidatesInputs() {
        assertThrows(IllegalArgumentException.class,
            () -> new OwlBenefitTestSet.OwlBenefitTestCase(
                "", "y", "z", "a", "b", "c", "supported", "", "", true));
        // Null id throws
        assertThrows(IllegalArgumentException.class,
            () -> new OwlBenefitTestSet.OwlBenefitTestCase(
                null, "y", "z", "a", "b", "c", "supported", "", "", true));
    }
}
