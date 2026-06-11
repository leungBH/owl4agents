# v0.6 Acceptance Contract

This contract defines the v0.6 acceptance gates covering research evaluation benchmarks, QA evaluation, context batch, and CLI commands.
A v0.6 report claiming PASS MUST include evidence for every required gate listed below.

## Required Report Path

```text
test/reports/acceptance/YYYY-MM-DD-HHMMSS-v0.6-acceptance-report.md
```

The filename MUST match the pattern `^\d{4}-\d{2}-\d{2}-\d{6}-v0\.6-acceptance-report\.md$`.
The report MUST follow `test/contracts/acceptance-report/contracts.md` and this contract.

## Required Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V06-BR-001 | benchmark-run-pizza | CLI process | `benchmark-run` with `pizza-small.yaml` produces valid JSONL output with `pizza-50` questions, non-zero accuracy, per-verdict counts, and summary line |
| V06-BR-002 | benchmark-run-owl2bench | CLI process | `benchmark-run` with `owl2bench-medium.yaml` produces valid JSONL output with `owl2bench-30` questions |
| V06-BR-003 | benchmark-NL-rejection | JUnit | NL-only question lines (empty claims) are rejected with `INVALID_QUESTION_SET`; subsequent valid lines continue |
| V06-BR-004 | benchmark-missing-IRI | JUnit | Claims missing `subject.iri` or `object.iri` are blocked with `INVALID_CLAIM_SCHEMA` |
| V06-EV-001 | eval-qa-metrics | CLI process | `eval-qa` with benchmark result JSONL computes accuracy, false-support-rate, unresolved-rate, verification-coverage, and 4×4 confusion matrix |
| V06-EV-002 | eval-qa-edge-case | JUnit | Edge-case questions excluded from primary metrics when policy is `exclude`; included when `include` |
| V06-CTX-001 | context-batch | CLI process | `context-batch` with `pizza-50.jsonl` produces per-question evidence context entries with `budgetCharsUsed`, `omittedEvidenceCount`, source-order retention |
| V06-CTX-002 | context-batch-budget | JUnit | Character budget `4 * maxContextTokens` is respected; entries exceeding budget are truncated |
| V06-CLI-001 | CLI-error-handling | JUnit | All 3 v0.6 CLI commands return exit code 1 on missing/invalid input files |
| V06-CLI-002 | CLI-malformed-config | JUnit | `benchmark-run` with malformed YAML returns exit code 1 with error diagnostic |
| V06-FIX-001 | fixture-pizza-50 | File validation | `pizza-50.jsonl` has 50 entries with multiple claim types, all have `questionId`, `claims`, `expectedVerdict` |
| V06-FIX-002 | fixture-owl2bench-30 | File validation | `owl2bench-30.jsonl` has 30 entries |
| V06-FIX-003 | fixture-out-of-scope | File validation | `out-of-scope-cross.jsonl` contains `out_of_scope` verdicts |
| V06-FIX-004 | config-parsing | JUnit | All v0.6 YAML configs parse without error via `ExperimentConfigParser` |
| V06-REL-001 | release | Build/test | `./gradlew clean buildVerification` passes including v0.1–v0.5 regression + v0.6 gates |
| V06-REL-002 | acceptance | Report | Timestamped acceptance report lists all required scenarios, commands, durations, results, and failures |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| pizza-50 | test/fixtures/v0.6/question-sets/pizza-50.jsonl | yes | Pizza ontology 50-question benchmark set |
| owl2bench-30 | test/fixtures/v0.6/question-sets/owl2bench-30.jsonl | yes | OWL2Bench 30-question benchmark set |
| out-of-scope-cross | test/fixtures/v0.6/question-sets/out-of-scope-cross.jsonl | yes | Cross-ontology out-of-scope verification |
| pizza-small-config | test/fixtures/v0.6/configs/pizza-small.yaml | yes | CI smoke benchmark config |
| owl2bench-medium-config | test/fixtures/v0.6/configs/owl2bench-medium.yaml | yes | Medium benchmark config |
| pizza-hallucination-config | test/fixtures/v0.6/configs/pizza-hallucination.yaml | yes | Hallucination detection config |

Missing required fixtures MUST make the gate FAIL, not SKIP.

## PASS Verdict Invalid Without

A v0.6 report claiming PASS is invalid if it omits evidence for:

- Benchmark run producing valid JSONL for both pizza-50 and owl2bench-30
- QA evaluation computing accuracy and confusion matrix from benchmark results
- Context batch producing per-question evidence context with budget metadata
- CLI error handling for all 3 v0.6 commands
- NL-only and missing-IRI rejection (Tier-1 validation)
- Fixture integrity (file counts, claim type diversity, config parsing)
- Release gate (buildVerification passes with v0.6 gates)
- Regression (v0.1 through v0.5 gates still pass)
