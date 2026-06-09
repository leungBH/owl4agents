# v0.5 Acceptance Contract

This contract defines the v0.5 acceptance gates covering workflow, skill packs, MCP prompts, and regressions.
A v0.5 report claiming PASS MUST include evidence for every required gate listed below.

## Required Report Path

```text
reports/acceptance/YYYY-MM-DD-HHMMSS-v0.5-acceptance-report.md
```

The filename MUST match the pattern `^\d{4}-\d{2}-\d{2}-\d{6}-v0\.5-acceptance-report\.md$`.
The report MUST follow `test/contracts/acceptance-report/contracts.md` and this contract.

## Required Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V05-WF-001 | workflow-supported | CLI process | `verify-answer` with supported claim batch returns `verified` aggregate status, all required claims supported, per-claim verdicts present |
| V05-WF-002 | workflow-contradicted | CLI process | `verify-answer` with contradicted claim batch returns `contradicted` aggregate status, contradiction evidence or counterexample present |
| V05-WF-003 | workflow-unknown | CLI process | `verify-answer` with unknown claim batch returns `insufficient_evidence` aggregate status, unknown reason and suggested action present |
| V05-WF-004 | workflow-out-of-scope | CLI process | `verify-answer` with out-of-scope claim batch returns `out_of_scope` aggregate status, missing/out-of-scope entities listed |
| V05-WF-005 | workflow-partially-verified | CLI process | `verify-answer` with supported+out_of_scope batch returns `partially_verified` aggregate status, scope warning present |
| V05-WF-006 | workflow-malformed | CLI process | `verify-answer` with malformed batch returns deterministic nonzero exit or `invalid_input`, field diagnostics present, no success report |
| V05-WF-007 | workflow-v03-parity | CLI process | v0.3 single-claim fixture wrapped into v0.5 batch preserves the same single-claim verdict |
| V05-CTX-001 | evidence-context | CLI process | `evidence-context` with mixed verdict batch returns compact context with verdicts, evidence summaries, omitted counts; no fabricated evidence |
| V05-MCP-001 | MCP-workflow-parity | MCP process | `ontology_verify_claims_batch` returns the same required report fields as CLI `verify-answer` |
| V05-MCP-002 | MCP-context-parity | MCP process | `ontology_build_evidence_context` returns the same required context fields as CLI `evidence-context` |
| V05-SKILL-001 | skill-validation | File validation | Required skill packs exist with complete SKILL.md metadata, SOP, references, no placeholder text |
| V05-SKILL-002 | skill-examples | File validation | Skill examples use workflow commands and verdict policy correctly; no unsafe instructions |
| V05-PROMPT-001 | prompt-validation | File/MCP validation | Prompt templates exist, reference workflow tools, guide structured claim input; stale tool names fail |
| V05-REL-001 | release | Build/test | `./gradlew clean buildVerification` passes including v0.1–v0.4 regression + v0.5 workflow gates |
| V05-REL-002 | acceptance | Report | Timestamped acceptance report lists all required scenarios, commands, durations, results, and failures |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| answer-claims-supported | test/fixtures/v0.5/answer-claims-supported.json | yes | supported verdict workflow |
| answer-claims-contradicted | test/fixtures/v0.5/answer-claims-contradicted.json | yes | contradicted verdict workflow |
| answer-claims-unknown | test/fixtures/v0.5/answer-claims-unknown.json | yes | unknown verdict workflow |
| answer-claims-out-of-scope | test/fixtures/v0.5/answer-claims-out-of-scope.json | yes | out_of_scope verdict workflow |
| answer-claims-partially-verified | test/fixtures/v0.5/answer-claims-partially-verified.json | yes | partially_verified workflow |
| answer-claims-mixed | test/fixtures/v0.5/answer-claims-mixed.json | yes | mixed verdict + optional claims |
| answer-claims-malformed | test/fixtures/v0.5/answer-claims-malformed.json | yes | malformed input diagnostics |
| answer-claims-v03-wrapped | test/fixtures/v0.5/answer-claims-v03-wrapped.json | yes | v0.3 parity verification |

Missing required fixtures MUST make the gate FAIL, not SKIP.

## PASS Verdict Invalid Without

A v0.5 report claiming PASS is invalid if it omits evidence for:

- Workflow verification for all 7 verdict paths (supported, contradicted, unknown, out_of_scope, partially_verified, malformed, v0.3 parity)
- Evidence context with truncation and no fabricated evidence
- MCP workflow parity with CLI
- Skill pack validation and safety
- Prompt template validation
- Release gate (buildVerification passes with v0.5 gates)
- Regression (v0.1 through v0.4 gates still pass)
- Baseline verification (v0.5 starts from latest v0.4.x commit; v0.4.0 tag exists)