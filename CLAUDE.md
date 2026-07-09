# CLAUDE.md

Project instructions for Claude Code. Also see `CONVENTIONS.md` for directory structure and naming rules.

---

## Behavioral Guidelines

Tradeoff: These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

These guidelines are working if: fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

## Project Conventions

Read and follow `CONVENTIONS.md` for:
- Directory structure and naming rules
- Where to place new files (modules, tools, tests, examples, docs)
- File naming conventions (English only, no single-file directories)
- Gradle module naming (`ontology-` prefix)
- Test organization (code in modules, data in `test/`)
- Build output centralization (`build/modules/`)

## Validation Discipline

These rules were added after the v0.8.1 acceptance review, where 6 defects
escaped the development loop (DEFECT-1..DEFECT-6 in
`reports/acceptance/2026-07-09-v0.8.1-acceptance-defects.md`).

### Rule 1: Test exists ≠ test passes

A task in `tasks.md` is **not** complete until the corresponding test has
been executed and produces a PASSED result. File creation alone is not
evidence of correctness. The checkbox completion criterion is:

```
[x] = "I have run `gradlew :modules:X:test --tests Y` and seen PASSED"
```

Not:

```
[x] = "I created the file"
```

`--tests` filters in Gradle **do not trigger `compileTestJava` for files
outside the filter**. Always run a separate `compileTestJava` pass:

```bash
gradlew :modules:ontology-reasoner:compileTestJava test
gradlew :modules:ontology-distribution:compileTestJava test
gradlew :modules:ontology-validation:compileTestJava test
```

### Rule 2: Accuracy gates are hard assertions — never informational

Accuracy / correctness gates (e.g. "80/80 claims match") MUST be
`assertEquals(...)` with the target number. "Informational" or
"aspirational" framing is **forbidden** for hard contracts — it
provides a hiding place for regressions.

If the gate cannot be hit, the right response is one of:
- Fix the implementation until the gate passes.
- Set the gate to the current baseline + a non-regression bound
  (e.g. `mismatches <= 28` with explicit tracking of all 28 in
  `reports/acceptance/`).
- Defer the work, document it, and add a regression test so it
  cannot silently worsen.

Never silently downgrade a hard gate to a `System.out.println`.

### Rule 3: V0.x contracts must not regress

The V01/V02/V03/... acceptance suites are **upstream contracts** that
v0.8.x changes must preserve. Before modifying any `verify*` /
`check*` / `is*` / `get*` method in `ClaimVerificationService`,
`ConsistencyAnalysisService`, `ReasonerServiceImpl`, or similar,
run the full V0.x acceptance suite first and confirm PASS:

```bash
gradlew :modules:ontology-distribution:test --tests "*V0[123]*"
```

If a V0.x test fails after your change, your change is wrong, **not
the V0.x test**. See `Rule 4`.

### Rule 4: When test conflicts with implementation, read the spec first

The default response to a failing test is **not** "modify the
implementation to match the test". The order is:

1. **Read the spec** (`openspec/specs/<area>/spec.md`) to see what
   the contract actually says.
2. **Re-read the test** to check whether its assertion is consistent
   with the spec.
3. **Only then** decide: fix the implementation, OR fix the test.

Common symptom of getting this wrong: a V0.8.x change is made to make
a new TC-2x test pass, which silently breaks a V0.3 contract test.
The product manager only catches this after the fact.

### Rule 5: Every mismatch must be classified

When an acceptance suite reports N mismatches, each mismatch must
appear in a tracking table with one of:

| Classification | Meaning |
|----------------|---------|
| `FIX-IN-CODE` | v0.8.x implementation is wrong; fix and re-run |
| `FIX-IN-FIXTURE` | Fixture's expected verdict is wrong; update the JSONL |
| `DEFERRABLE` | Documented as accepted baseline in the acceptance report |

A `FIX-IN-CODE` mismatch is a release blocker. A `DEFERRABLE`
mismatch must be listed by ID in the acceptance report. Unclassified
mismatches are release blockers.

## Self-Validation Gate (REQUIRED before archive)

Before marking any OpenSpec change as ready for `openspec archive`,
all of the following must produce a passing result. Paste the output
into the PR description or acceptance report.

```bash
# 1. All touched modules compile their tests
gradlew :modules:<each touched module>:compileTestJava

# 2. All touched modules pass their tests
gradlew :modules:<each touched module>:test

# 3. V0.x upstream contracts do not regress
gradlew :modules:ontology-distribution:test --tests "*V0*"

# 4. Full workspace test in --continue mode
gradlew test --no-daemon --continue
```

All four must end with `BUILD SUCCESSFUL` and `0 failed`. If any
fails, the change is not ready for archive — fix the failure first.

The `openspec archive` command should refuse to proceed if any of
these four commands has not been recorded as passed in the last 24
hours. (Manual check today; CLI gate is a future improvement.)

## Key Paths

| What you need              | Where to find it                           |
|----------------------------|--------------------------------------------|
| CLI entry point            | `node tools/npm/bin/owl4agents.js`         |
| Build output               | `build/modules/ontology-{name}/libs/`      |
| Gradle build               | `.\gradlew.bat :modules:ontology-cli:shadowJar` |
| Test fixtures              | `test/fixtures/v{X}/`                      |
| Test corpus                | `test/corpus/`                             |
| Acceptance reports         | `test/reports/acceptance/`                 |
| Example demos              | `examples/`                                |
| Agent skill packs          | `tools/skills/`                            |
| Project conventions        | `CONVENTIONS.md`                           |
