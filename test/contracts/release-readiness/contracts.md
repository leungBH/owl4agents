# Release Readiness Acceptance Contract

## Contract: Build verification gate

The `.\gradlew.bat clean buildVerification` command SHALL exit with code 0 and all required regression tests for the release SHALL pass.

- No test failures, no silently skipped required fixtures
- Gradle deprecation warnings classified as: blocking (must fix), accepted (documented), or fixed
- Blocking warnings must be resolved before release

## Contract: Shadow jar build

The `.\gradlew.bat :modules:ontology-cli:shadowJar` command SHALL produce `modules/ontology-cli/build/libs/owl4agents.jar`.

- The jar file SHALL exist after the command
- The jar SHALL be executable via `java -jar owl4agents.jar --help`

## Contract: Launcher help output

When `node npm/bin/owl4agents.js --help` is executed as a real child process:

- Exit code SHALL be 0
- stdout SHALL include "owl4agents" and at least 8 command names (init, import, list, summary, search, entity, query, mcp)
- stderr SHALL NOT contain crash text, stack traces, or access violations
- stdout SHALL NOT be empty

## Contract: Launcher version output

When `node npm/bin/owl4agents.js --version` is executed as a real child process:

- Exit code SHALL be 0
- stdout SHALL contain the project version string, for example `0.3.0`
- stderr SHALL NOT contain crash text or stack traces

## Contract: Missing runtime diagnostics

When the launcher runs in an isolated environment with no Java runtime and no fat jar:

- Exit code SHALL be a deterministic nonzero value (not arbitrary)
- stderr SHALL contain a clear error category (missing runtime, missing jar, missing Java)
- stderr SHALL include guidance (e.g. which build command to run, which Java version is required)
- stdout SHALL NOT contain misleading success output

## Contract: Command forwarding

When `node npm/bin/owl4agents.js list-reasoners` is executed with a built fat jar:

- Exit code SHALL be 0
- stdout SHALL include "HermiT", "ELK", and "Openllet"
- Exit code semantics SHALL be preserved: nonzero from runtime MUST NOT be converted to success

## Contract: MCP readonly startup

When `node npm/bin/owl4agents.js mcp --readonly` is started as a real child process:

- The process SHALL start without immediate crash
- stderr SHALL NOT contain crash text or stack traces during startup
- A bounded MCP initialize or tools/list interaction SHALL return a valid JSON-RPC response
- The process SHALL shut down cleanly within timeout

## Contract: regression gates

All existing acceptance gates from earlier released versions SHALL pass unchanged. Feature releases SHALL also run their own version-specific fixtures and contracts, such as the v0.3 claim-verification fixtures.

- CLI/MCP parity SHALL remain equivalent
- Placeholder payloads, empty success payloads, and crash output SHALL fail tests
- Missing fixtures SHALL fail loudly (not silently skip)

## Contract: Dependency matrix alignment

README dependency versions SHALL match source-of-truth build files:

- OWL API version matches `modules/ontology-owlapi/build.gradle.kts`
- HermiT, ELK, Openllet versions match `modules/ontology-reasoner/build.gradle.kts`
- Gradle version matches `gradle/wrapper/gradle-wrapper.properties`
- Java version matches Gradle toolchain or documented requirement
- Node version matches `npm/package.json` engines field

## Contract: Acceptance report evidence

A timestamped acceptance report SHALL be written under `reports/acceptance/` with:

- Date/time (ISO-8601)
- OS, Java version, Node version, Gradle version
- Commands executed and their results
- Fixture list and gate status (pass/fail/skip)
- Failures with root cause
- Skipped scenarios with rationale
- Retest notes for any initially-failing gates
- Final verdict: PASS or FAIL

The report SHALL follow `test/contracts/acceptance-report/contracts.md`.

## Contract: Tester handoff quality

Before a release is accepted, the tester SHALL provide a report that is complete enough for another person or agent to reproduce the decision.

- Every required gate SHALL have a command, exit code, duration, stdout summary, stderr summary, and result
- Every failed or blocked gate SHALL have a defect ledger entry
- Every skipped scenario SHALL identify whether it is optional, deferred, or blocked
- Every retest SHALL include the original failed command and the successful retest command
- Report text SHALL be UTF-8 and readable without mojibake
- Status values SHALL use `PASS`, `FAIL`, `SKIP`, `BLOCKED`, or `DEFERRED`

The release SHALL be blocked if the report is missing command evidence, hides required skips, has unreadable encoding, or claims PASS while required gates were not executed.
