# v0.4 Acceptance Contract

This contract defines the v0.4 example demo packs and validation acceptance gates.
A v0.4 report claiming PASS MUST include evidence for every required gate listed below.

## Required Report Path

```text
reports/acceptance/YYYY-MM-DD-HHMMSS-v0.4-acceptance-report.md
```

The report MUST follow `test/contracts/acceptance-report/contracts.md` and this contract.

## Required Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V04-EX-001 | manifest | File validation | Every required example directory contains valid `example.yaml` with all required fields (id, title, description, interfaces, fixtures, commands, expectedOutputs, ciRequired, attribution) |
| V04-EX-002 | claim-verification | CLI real process | Supported, contradicted, unknown, and out_of_scope claim examples return expected verdicts; contradicted/unknown preconditions are explicit; all commands exit with expected code |
| V04-EX-003 | pizza-reasoning | CLI real process | Import, summary, classify, and context commands run successfully on pizza.owl; output includes ontology ID, entity counts, reasoner metadata |
| V04-EX-004 | agent-mcp | MCP process | MCP readonly starts and returns initialize + tools/list within 30 seconds; readonly tool names (ontology_verify_claim, ontology_summary, ontology_classify, ontology_get_evidence_path) are present in tool list. Note: ontology_import is a v0.8 write tool (requires --allow-write) and is NOT expected in readonly tools/list. |
| V04-EX-005 | biomedical-grounding | CLI real process | Required small biomedical example runs from project-owned golden fixture; search, context, or verify commands produce structured output |
| V04-EX-006 | doc-drift | File validation | Root README links every required example; "Try in 3 minutes" commands reference existing files |
| V04-EX-007 | expected-output | File validation | Expected output files define schema/field assertions, not byte-for-byte snapshots; no absolute paths, tokens, or private names |
| V04-EX-008 | negative | CLI real process | Contradicted/unknown examples document why claim is not supported; negative paths produce meaningful explanations |
| V04-REL-001 | release | Build/test | `./gradlew clean buildVerification` passes including v0.1 through v0.3.1 regression + v0.4 example validation |
| V04-REL-002 | acceptance | Report | Timestamped acceptance report lists all required example scenarios, commands, fixtures, durations, results, and failure summaries |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| pizza.owl | test/corpus/smoke/pizza.owl | yes | pizza reasoning import, summary, classify |
| v0.3-golden | test/corpus/golden/v0.3-claim-verification.owl | yes | claim verification examples |
| claim-supported | test/fixtures/v0.3/claim-supported.json | yes | supported verdict example |
| claim-contradicted | test/fixtures/v0.3/claim-contradicted.json | yes | contradicted verdict example |
| claim-unknown | test/fixtures/v0.3/claim-unknown.json | yes | unknown verdict example |
| claim-real-out-of-scope | test/fixtures/v0.3/claim-real-out-of-scope.json | yes | out_of_scope verdict example |
| v0.4-biomedical-golden | test/corpus/golden/v0.4-biomedical-grounding.owl | yes | biomedical grounding example |

Missing required fixtures MUST make the gate FAIL, not SKIP.

## Optional Fixtures (Manual Only)

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| bfo.owl | test/corpus/smoke/bfo.owl | no | optional upper ontology import |

Optional fixtures MUST NOT cause CI failure when absent.

## PASS Verdict Invalid Without

A v0.4 report claiming PASS is invalid if it omits evidence for:
- example manifest validation (all 4 required packs)
- claim verification (all 4 verdict paths)
- pizza reasoning (import, summary, classify, context)
- MCP startup and tool list (initialize + tools/list)
- biomedical grounding (project-owned golden fixture)
- documentation drift (README links, "Try in 3 minutes" commands)
- expected output format (schema assertions, not exact snapshots)
- release gate (buildVerification passes with v0.4 validation)
- regression (v0.1 through v0.3.1 gates still pass)

## Skipped Required Scenarios

Any required example validation scenario that is skipped, missing, unreadable, or only manually inspected MUST be marked BLOCKED or FAIL. The report MUST NOT claim final PASS when any required gate is skipped.