# Example Validation Contracts (v0.4)

## Principle

v0.4 example validation is an automated release gate. It executes required example packs through real child processes, captures structured output, and asserts that every required example passes before v0.4 can be tagged as released.

## Child-Process Execution

- Required CLI examples MUST be validated through real child-process execution.
- The entry point MUST be `node npm/bin/owl4agents.js` — direct `java -jar` invocation is not allowed in example validation scripts.
- Each command MUST be executed from the repository root directory.
- stdout and stderr MUST be captured for each command execution.
- Exit codes MUST be checked exactly — exit code `0` for success paths, deterministic nonzero codes for negative examples.

## Exit Code Assertions

| Scenario | Expected exit code | Notes |
| --- | --- | --- |
| CLI claim verification (supported, contradicted, unknown, out_of_scope) | 0 | All four verdicts return exit 0 with structured JSON |
| CLI pizza reasoning (import, summary, classify, context) | 0 | All steps return exit 0 |
| CLI biomedical grounding (search, context, verify) | 0 | Small fixture path returns exit 0 |
| Negative CLI example (malformed claim, unsupported type) | 1 | Error paths return nonzero exit code |

## Stdout/Stderr Capture

- stdout MUST be captured and parsed as JSON when the command produces structured output.
- stderr MUST be captured. Validation MUST check stderr for crash text, stack traces, or error messages when exit code is nonzero.
- Capture MUST be UTF-8 readable.
- Captured output MUST NOT be discarded silently when a command fails.

## JSON Field Assertions

Validation MUST NOT require byte-for-byte full-output snapshots. Instead, it MUST use schema/field assertions:

| Example | Required JSON fields | Assertion type |
| --- | --- | --- |
| claim-verification (supported) | `verdict`, `claimId`, `ontologyId`, `evidence` (any non-empty) | Field present + verdict exact value `supported` + evidence non-empty |
| claim-verification (contradicted) | `verdict`, `claimId`, `evidence` (any counter evidence) | Field present + verdict exact value `contradicted` |
| claim-verification (unknown) | `verdict`, `unknownReason`, `unknownExplanation` | Field present + verdict exact value `unknown` + reason non-empty |
| claim-verification (out_of_scope) | `verdict`, `evidence` (scope_statement or empty) | Field present + verdict exact value `out_of_scope` |
| pizza reasoning (summary) | `ontologyId`, `classCount` or equivalent entity count | Field present + ontologyId non-empty |
| pizza reasoning (classify) | `reasonerName`, `consistent` | Field present + reasonerName non-empty |
| biomedical grounding (search) | `results` (any non-empty) or `matched` | Field present + non-empty result set |
| biomedical grounding (verify) | `verdict`, `ontologyId` | Field present + verdict exact value |
| MCP initialize | `capabilities`, `serverInfo.name`, `serverInfo.version` | Field present + name matches owl4agents |

Assertions MUST allow for ordering differences, optional metadata fields, and version-dependent values where the contract does not specify exact values.

## Crash and Placeholder Rejection

Validation MUST fail the example when:

- stdout or stderr contains `ACCESS_VIOLATION`, unhandled exception stack traces, or launcher startup errors
- stdout is empty on a command that should produce structured JSON output
- stdout contains placeholder text: `TODO`, `stub`, `not implemented`, fake evidence, or a success payload missing required fields
- The command exits with a nonzero code but stderr contains no diagnostic text

## MCP Timeout

- MCP child process startup MUST complete within 30 seconds by default.
- If the manifest specifies a stricter timeout, that timeout MUST be used.
- Timeout MUST apply to: MCP process start, `initialize` response, and `tools/list` response.
- MCP process MUST be terminated cleanly (SIGTERM on Unix, process kill on Windows) after validation completes or times out.

## Required MCP Validation

CI-required MCP validation MUST:

1. Start owl4agents MCP readonly as a child process
2. Send JSON-RPC `initialize` request and receive a valid response
3. Send JSON-RPC `tools/list` request and receive a non-empty tool list
4. Assert that documented tool names appear in the tool list
5. Terminate the MCP process cleanly

## Optional Live MCP Tool-Call Validation

- Live MCP tool-call validation (sending a real tool request and checking the response) is optional in v0.4.
- If omitted, documentation MUST state that CI-required MCP coverage is initialize + `tools/list` plus sanitized transcript validation.
- If implemented, live tool-call validation MUST assert at least one readonly MCP tool returns a non-empty structured response.
- CLI/MCP verdict parity for the same claim fixture is NOT required in v0.4 unless explicitly tested.

## Sanitized Transcript Validation

- Committed MCP transcripts under `examples/agent-mcp/transcripts/` MUST be validated against current documented tool names.
- Validation MUST fail if a required documented tool name is missing from the transcript.
- Validation MUST reject transcripts containing: absolute local paths, local usernames, bearer tokens, passwords, secret-like fields, or private ontology names.
- Detection patterns MUST include: Windows paths (`C:\`, `D:\`), Unix home paths (`/home/`, `/Users/`), token-like strings (`Bearer `, `token`, `secret`, `password`), and private ontology filenames not in the public fixture set.

## Documentation Drift Validation

- Root `README.md` MUST link every required example pack, and links MUST resolve to checked-in files.
- "Try in 3 minutes" command sequences MUST reference existing files and supported CLI commands.
- Expected output files MUST define required fields, allowed values, or sample snippets — NOT exact byte-for-byte matching.
- Stale tool names, outdated command syntax, or broken relative links MUST fail validation.

## Acceptance Evidence

- v0.4 acceptance report MUST list every required example scenario, command, exit code, fixture, duration, result, and failure summary.
- PASS MUST NOT be claimed when any required example validation scenario is skipped, missing, unreadable, or only manually inspected.
- Skipped required scenarios MUST be marked BLOCKED or FAIL with a defect ledger entry.