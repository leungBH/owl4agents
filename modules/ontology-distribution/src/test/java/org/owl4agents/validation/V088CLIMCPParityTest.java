package org.owl4agents.validation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * v0.8.8 Section 8.5 / task 8.5: CLI/MCP parity integration test.
 *
 * <p>Verifies that the same claim produces identical verdicts through both
 * the CLI {@code verify-claim} command and the MCP {@code verify} tool.
 * Tests 3 A1 claims and 3 A2 claims through both interfaces.
 *
 * <p><b>DISABLED</b>: This is a complex parity test that requires:
 * <ul>
 *   <li>The CLI shadow JAR to be built ({@code ./gradlew :modules:ontology-cli:shadowJar})</li>
 *   <li>The MCP HTTP server to be running</li>
 *   <li>HPO/Mondo/Pizza ontology fixtures to be available</li>
 *   <li>Orchestrating subprocess calls to the CLI and HTTP calls to the MCP server</li>
 * </ul>
 *
 * <p>This test is deferred to the acceptance test phase (Section 10) where
 * the full CLI/MCP parity is verified via the acceptance test script. The
 * stub is provided here to document the requirement and serve as a starting
 * point for a future implementation.
 *
 * <p>Acceptance contracts:
 * <ul>
 *   <li>{@code test/contracts/v088-acceptance/a1-disjoint-classes-elk.json}</li>
 *   <li>{@code test/contracts/v088-acceptance/a2-class-level-contradictions.json}</li>
 *   <li>{@code test/contracts/cli-mcp-parity-contract.md}</li>
 * </ul>
 */
@Tag("integration")
@Disabled("Complex CLI/MCP parity test — requires shadow JAR + MCP server + ontology fixtures. "
    + "Run via acceptance-test.ps1 in Section 10 instead.")
@DisplayName("v0.8.8 §8.5: CLI/MCP parity — same claim produces identical verdict (DISABLED)")
class V088CLIMCPParityTest {

    @Test
    @DisplayName("3 A1 claims produce identical verdicts via CLI and MCP")
    void a1ClaimsProduceIdenticalVerdictsViaCliAndMcp() {
        // Implementation sketch (when enabled):
        // 1. Build the CLI shadow JAR
        // 2. Start the MCP HTTP server
        // 3. For each of 3 A1 claims (hpo-ex-004, hpo-ex-005, mondo-ex-004):
        //    a. Run `verify-claim hpo --claim=<claim>.json --json --reasoner=elk` via CLI
        //    b. Call the MCP `verify` tool with the same claim
        //    c. Assert both return `contradicted` with SATISFIABILITY_CHECK evidence
        //    d. Assert the verdicts are identical
    }

    @Test
    @DisplayName("3 A2 claims produce identical verdicts via CLI and MCP")
    void a2ClaimsProduceIdenticalVerdictsViaCliAndMcp() {
        // Implementation sketch (when enabled):
        // 1. Build the CLI shadow JAR
        // 2. Start the MCP HTTP server
        // 3. For each of 3 A2 claims (pizza-sc-005, pizza-ec-005, pizza-dc-005):
        //    a. Run `verify-claim pizza --claim=<claim>.json --json` via CLI
        //    b. Call the MCP `verify` tool with the same claim
        //    c. Assert both return `contradicted` with SATISFIABILITY_CHECK evidence
        //    d. Assert the verdicts are identical
    }
}
