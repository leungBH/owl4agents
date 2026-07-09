package org.owl4agents.distribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V0.x Contract Preservation Suite.
 *
 * <p>The V0.1 / V0.2 / V0.3 / ... acceptance suites encode the
 * historical contract of the claim verification pipeline. Any v0.8.x
 * change that breaks a V0.x test is, by definition, a breaking
 * change. This suite is a thin catalog of which V0.x tests exist
 * and which behaviors they lock in. If any are removed or renamed,
 * the catalog flags the regression.</p>
 *
 * <p>Located in the {@code ontology-distribution} module because
 * that module is the end-to-end integration target. It depends on
 * all upstream modules, so it is the natural place to document
 * cross-module contract preservation.</p>
 *
 * <p>Cross-module checks use <b>file-based existence</b> on the
 * test source tree (not {@code Class.forName}), because the
 * distribution module's test classpath does not include the
 * {@code test} sources of other modules. A passing test here means
 * "the source file for this hard gate is in the project tree".</p>
 *
 * <p>Also serves as the cross-module <b>regression catalog</b> for
 * the 6 v0.8.1 defects (DEFECT-1..DEFECT-3, DEFECT-5) — the
 * module-local catalogs are in
 * {@code V081DefectRegressionTest} in the
 * {@code ontology-validation} module.</p>
 *
 * <p>See {@code reports/acceptance/2026-07-09-v0.8.1-acceptance-defects.md}
 * for the original 6-defect analysis that motivated this suite.</p>
 */
@DisplayName("V0.x Contract Preservation Suite")
class V0xContractPreservationTest {

    // ── V0.3 acceptance contract ───────────────────────────────────────

    /**
     * V0.3 Claim verdicts — 4 tests that lock in the v0.3
     * SUPPORTED/OUT_OF_SCOPE/UNKNOWN/CONTRADICTED decision logic.
     * v0.8.x must preserve all 4.
     */
    @Test
    @DisplayName("V0.3 Claim verdicts: 4 tests must exist")
    void v03ClaimVerdictsContract() {
        // In-module: V03AcceptanceSuite is in this same module, so
        // Class.forName() works. The methods are in the @Nested
        // ClaimVerdictTests class, so we look up there.
        int missing = 0;
        StringBuilder sb = new StringBuilder();
        for (String method : new String[]{
            "subclassClaimIsSupported",            // DEFECT-1 gate
            "undeclaredEntityClaimIsOutOfScope",   // DEFECT-2 gate
            "sparseSubclassClaimIsUnknown",        // preserved
            "falseDisjointnessClaimIsContradicted" // preserved
        }) {
            if (!hasMethod("org.owl4agents.distribution.V03AcceptanceSuite$ClaimVerdictTests", method)) {
                missing++;
                sb.append(" V0.3.ClaimVerdictTests.").append(method);
            }
        }
        assertEquals(0, missing,
            "V0.3 claim-verdict contract is missing tests:" + sb
                + " — these tests lock in the v0.3 SUPPORTED/OUT_OF_SCOPE/UNKNOWN/CONTRADICTED decision logic.");
    }

    /**
     * V0.3 Evidence grounding — 4 tests that lock in the v0.3
     * evidence path output (asserted/inferred sources, explanation
     * structures, counterexample emission).
     */
    @Test
    @DisplayName("V0.3 Evidence grounding: 4 tests must exist")
    void v03EvidenceGroundingContract() {
        int missing = 0;
        StringBuilder sb = new StringBuilder();
        for (String method : new String[]{
            "contradictedClaimExposesCounterexamples",
            "supportedClaimHasEvidencePath",       // DEFECT-3 gate
            "unknownClaimHasExplanation",
            "missingEntityDetectionReportsUndeclaredEntities"
        }) {
            if (!hasMethod("org.owl4agents.distribution.V03AcceptanceSuite$EvidenceGroundingTests", method)) {
                missing++;
                sb.append(" V0.3.EvidenceGroundingTests.").append(method);
            }
        }
        assertEquals(0, missing,
            "V0.3 evidence-grounding contract is missing tests:" + sb);
    }

    // ── V0.8.1 acceptance contract ─────────────────────────────────────

    /**
     * V0.8.1 acceptance — the v0.8.1 contract adds 2 new claim
     * types (different_individuals, object_property_subproperty)
     * and complex class expressions. The V081AcceptanceSuite has
     * a TC-14 accuracy gate (now hardened in v0.8.1 per
     * CLAUDE.md Rule 2) plus 5 ISSUE-specific sub-tests.
     *
     * <p>Cross-module: V081AcceptanceSuite is in the
     * {@code ontology-validation} module. We check for the test
     * source file on disk (relative to the project root).</p>
     */
    @Test
    @DisplayName("V0.8.1 acceptance: TC-14 + 5 ISSUE sub-tests must exist (file-based)")
    void v081AcceptanceContract() {
        Path source = resolveRelative(
            "modules/ontology-validation/src/test/java/org/owl4agents/validation/V081AcceptanceSuite.java");
        assertNotNull(source, "project root must be discoverable");
        assertTrue(Files.exists(source),
            "V0.8.1 acceptance test file must exist at: " + source);

        // The actual hard gate is the suite running. Here we just
        // confirm the source file contains the expected method
        // names. The file-based check is module-portable.
        String content;
        try {
            content = Files.readString(source);
        } catch (Exception e) {
            fail("Could not read " + source + ": " + e);
            return;
        }
        for (String method : new String[]{
            "fullCuratedClaimsAccuracyGate",
            "pizza007ComplexExpressionMatches",
            "pizza035DifferentIndividualsAssertedMatches",
            "pizza037SubPropertyAssertedMatches",
            "pizza046InferredDomainMatches",
            "owl2bench027InferredSubclassMatches"
        }) {
            assertTrue(content.contains("void " + method + "("),
                "V0.8.1 acceptance contract is missing test: " + method
                    + " (in " + source.getFileName() + ")");
        }
    }

    // ── v0.8.1 Defect Regression Catalog (cross-module) ──────────────

    /**
     * DEFECT-1: transitive SubClassOf entailment.
     * Hard gate: V03.ClaimVerdictTests.subclassClaimIsSupported (in-module).
     */
    @Test
    @DisplayName("DEFECT-1: V03.ClaimVerdictTests.subclassClaimIsSupported must exist (transitive SubClassOf)")
    void defect1Catalog() {
        assertTrue(hasMethod("org.owl4agents.distribution.V03AcceptanceSuite$ClaimVerdictTests",
                "subclassClaimIsSupported"),
            "DEFECT-1: V03.ClaimVerdictTests.subclassClaimIsSupported must exist — "
                + "it is the hard gate for the getSuperClasses(direct=true)→false fix");
    }

    /**
     * DEFECT-2: verifyOntologyScope reports OUT_OF_SCOPE for undeclared entities.
     * Hard gate: V03.ClaimVerdictTests.undeclaredEntityClaimIsOutOfScope (in-module).
     */
    @Test
    @DisplayName("DEFECT-2: V03.ClaimVerdictTests.undeclaredEntityClaimIsOutOfScope must exist")
    void defect2Catalog() {
        assertTrue(hasMethod("org.owl4agents.distribution.V03AcceptanceSuite$ClaimVerdictTests",
                "undeclaredEntityClaimIsOutOfScope"),
            "DEFECT-2: V03.ClaimVerdictTests.undeclaredEntityClaimIsOutOfScope must exist — "
                + "it is the hard gate for the verifyOntologyScope re-adding entity-declaration check");
    }

    /**
     * DEFECT-3: supported claim has evidence path.
     * Hard gate: V03.EvidenceGroundingTests.supportedClaimHasEvidencePath (in-module).
     */
    @Test
    @DisplayName("DEFECT-3: V03.EvidenceGroundingTests.supportedClaimHasEvidencePath must exist")
    void defect3Catalog() {
        assertTrue(hasMethod("org.owl4agents.distribution.V03AcceptanceSuite$EvidenceGroundingTests",
                "supportedClaimHasEvidencePath"),
            "DEFECT-3: V03.EvidenceGroundingTests.supportedClaimHasEvidencePath must exist — "
                + "it is the hard gate for the evidence-emit-after-transitive-entailment chain");
    }

    /**
     * DEFECT-5: reasoner test compiles (had missing @DisplayName import).
     * Hard gate: AdapterGetUnderlyingReasonerTest.java exists and
     * contains @DisplayName-annotated methods. Cross-module, so
     * file-based check.
     */
    @Test
    @DisplayName("DEFECT-5: AdapterGetUnderlyingReasonerTest.java contains @DisplayName usage")
    void defect5Catalog() {
        Path source = resolveRelative(
            "modules/ontology-reasoner/src/test/java/org/owl4agents/reasoner/AdapterGetUnderlyingReasonerTest.java");
        assertNotNull(source, "project root must be discoverable");
        assertTrue(Files.exists(source),
            "DEFECT-5: AdapterGetUnderlyingReasonerTest.java must exist at: " + source);
        String content;
        try {
            content = Files.readString(source);
        } catch (Exception e) {
            fail("Could not read " + source + ": " + e);
            return;
        }
        assertTrue(content.contains("@DisplayName"),
            "DEFECT-5: AdapterGetUnderlyingReasonerTest.java must use @DisplayName "
                + "(the original bug was a missing import)");
    }

    // ── Aggregated gate: all V0.x contracts are intact ────────────────

    @Test
    @DisplayName("ALL: all V0.x acceptance test files are present")
    void allV0xAcceptanceFilesExist() {
        String[] required = {
            "modules/ontology-distribution/src/test/java/org/owl4agents/distribution/V03AcceptanceSuite.java",
            "modules/ontology-validation/src/test/java/org/owl4agents/validation/V081AcceptanceSuite.java"
            // V01 and V02 suites are tracked separately when present.
            // Add their relative paths here as new versions are added.
        };
        int missing = 0;
        StringBuilder sb = new StringBuilder();
        for (String relativePath : required) {
            Path p = resolveRelative(relativePath);
            if (p == null || !Files.exists(p)) {
                missing++;
                sb.append(" ").append(relativePath);
            }
        }
        assertEquals(0, missing,
            "V0.x acceptance test file(s) missing:" + sb
                + " — these files are the upstream contract. Per CLAUDE.md Rule 3, "
                + "no v0.8.x change can remove or rename them.");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean hasMethod(String className, String methodName) {
        try {
            Class<?> klass = Class.forName(className);
            klass.getDeclaredMethod(methodName);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Resolve a path relative to the project root. The current
     * working directory may be the project root (when running
     * {@code gradlew test} from the root) or a subdirectory (when
     * running {@code gradlew :modules:foo:test}). We search up to
     * 3 parent directories looking for a directory that contains
     * {@code modules/}.
     */
    private Path resolveRelative(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return null;
    }
}
