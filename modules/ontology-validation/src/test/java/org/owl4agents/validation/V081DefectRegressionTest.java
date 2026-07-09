package org.owl4agents.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.1 Defect Regression Suite — the 6 defects identified by the
 * product manager during v0.8.1 acceptance review.
 *
 * <p>This suite is the <b>regression catalog</b> for the defects whose
 * hard-gate tests live in the {@code ontology-validation} module
 * (DEFECT-4, DEFECT-6). The cross-module gates (DEFECT-1, DEFECT-2,
 * DEFECT-3, DEFECT-5) are catalogued in
 * {@code V0xContractPreservationTest} in the
 * {@code ontology-distribution} module, which can see classes in all
 * upstream modules.</p>
 *
 * <p>See:</p>
 * <ul>
 *   <li>{@code reports/acceptance/2026-07-09-v0.8.1-acceptance-defects.md}</li>
 *   <li>{@code CLAUDE.md} Validation Discipline section</li>
 * </ul>
 */
@DisplayName("v0.8.1 Defect Regression Suite (DEFECT-4, DEFECT-6 in this module)")
class V081DefectRegressionTest {

    // ── DEFECT-4: agent-mcp README documents v0.8.1 features ───────────

    @Test
    @DisplayName("DEFECT-4: agent-mcp README.md documents v0.8.1 claim types")
    void defect4AgentMcpReadmeDocumentsV081Features() throws Exception {
        // The hard gate. This test catches regressions where the
        // README is updated to mention v0.8.1 by name but loses the
        // new claim type documentation.
        Path readme = resolveAgentMcpReadme();
        assertNotNull(readme, "agent-mcp README.md must exist");
        assertTrue(Files.exists(readme), "README.md must exist at: " + readme);
        String content = Files.readString(readme);

        assertTrue(content.contains("v0.8.1 Claim Types"),
            "DEFECT-4: README.md must contain 'v0.8.1 Claim Types' section header");
        assertTrue(content.contains("different_individuals"),
            "DEFECT-4: README.md must document different_individuals claim type");
        assertTrue(content.contains("object_property_subproperty"),
            "DEFECT-4: README.md must document object_property_subproperty claim type");
        assertTrue(content.contains("complex class expression")
                || content.contains("Complex class expressions"),
            "DEFECT-4: README.md must document complex class expressions support");
    }

    // ── DEFECT-6: Defect024 regression tests pass ─────────────────────

    @Test
    @DisplayName("DEFECT-6: Defect024RegressionTest$MapErrorBlankIriTests has 3+ @Test methods for v0.8.1 degrade path")
    void defect6Defect024RegressionExists() {
        // The real hard gate is that Defect024RegressionTest's 3 nested
        // tests pass. This test verifies the @Nested class still exists
        // with the right shape (at least 3 @Test methods).
        try {
            Class<?> testClass = Class.forName(
                "org.owl4agents.validation.Defect024RegressionTest$MapErrorBlankIriTests");
            int testMethodCount = 0;
            for (var method : testClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                    testMethodCount++;
                }
            }
            assertTrue(testMethodCount >= 3,
                "DEFECT-6: Defect024RegressionTest$MapErrorBlankIriTests must have at "
                    + "least 3 @Test methods (current=" + testMethodCount + "). The 3 "
                    + "expected methods are: classNotFoundBlankEntityIriNoCrash, "
                    + "classNotFoundEmptyStringEntityIriNoCrash, "
                    + "classNotFoundValidEntityIriReturnsProperError.");
        } catch (ClassNotFoundException e) {
            fail("DEFECT-6: Defect024RegressionTest$MapErrorBlankIriTests class not found: " + e);
        }
    }

    private Path resolveAgentMcpReadme() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = cwd.resolve("examples/agent-mcp/README.md");
            if (Files.exists(candidate)) return candidate;
            cwd = cwd.getParent();
            if (cwd == null) break;
        }
        return Path.of("examples/agent-mcp/README.md");
    }
}
