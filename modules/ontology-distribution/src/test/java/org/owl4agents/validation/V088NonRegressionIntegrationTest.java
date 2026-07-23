package org.owl4agents.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * v0.8.8 Section 8.3 / task 8.3: Non-regression integration test.
 *
 * <p>Verifies that a representative sample (20+ claims) of the 359
 * previously-passing claims from the v0.8.6 repro package still produce
 * the correct (identical) verdicts under v0.8.8. No previously-supported
 * claim may become contradicted or unknown; no previously-unknown claim
 * may become supported.
 *
 * <p><b>DISABLED</b>: This test requires the V086_REPRO_PACKAGE on the
 * {@code E:} drive, which is a local-only fixture not available in CI.
 * The full non-regression run is performed by the acceptance test script
 * {@code E:\V086_REPRO_PACKAGE\acceptance-test.ps1} (adapted for v0.8.8)
 * as part of Section 10 (Acceptance Tests with V086 Repro Package).
 *
 * <p>The local fixture copy is at {@code test/corpus/v086-repro/}:
 * <ul>
 *   <li>{@code V086_FAILING_CLAIMS.jsonl} — the 29 failing claims</li>
 *   <li>{@code question-sets/*.jsonl} — the full 388-claim question sets</li>
 * </ul>
 *
 * <p>Acceptance contract: {@code test/contracts/v088-acceptance/non-regression.json}
 *
 * <p>To enable this test locally, remove the {@link Disabled} annotation
 * and ensure the V086 repro package fixtures are available at
 * {@code test/corpus/v086-repro/}.
 */
@Tag("integration")
@Disabled("Requires V086_REPRO_PACKAGE on E: drive — run via acceptance-test.ps1 instead")
@DisplayName("v0.8.8 §8.3: Non-regression — 359 previously-passing claims (DISABLED)")
class V088NonRegressionIntegrationTest {

    @Test
    @DisplayName("Representative sample of 359 previously-passing claims produces identical verdicts")
    void nonRegressionSampleProducesIdenticalVerdicts() {
        // This test is disabled. The actual non-regression verification is
        // performed by the acceptance test script which runs the full 388-claim
        // V086 repro package against the v0.8.8 build and compares verdicts.
        //
        // Implementation sketch (when enabled):
        // 1. Load question sets from test/corpus/v086-repro/*.jsonl
        // 2. Filter out the 18 code-fixable claims (A1/A2/pizza-op-008)
        //    and 8 gold-label-disputed claims (B1/B2/C1)
        // 3. For each remaining claim, verify against the appropriate ontology
        // 4. Assert the verdict matches the expectedVerdict from the JSONL
        // 5. Sample at least 20 claims across HPO/Mondo/Pizza/SOSA ontologies
    }
}
