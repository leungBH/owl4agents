# v0.6 Question Sets

This directory contains JSONL-formatted question sets used to benchmark the
owl4agents claim verification pipeline. Each file is one question set; each
line is one question with one or more `claims`.

## Files

| File | Questions | Ontology | Notes |
| --- | --- | --- | --- |
| `minimal-test.jsonl` | 1 | any | Tiny smoke-test fixture. |
| `pizza-50.jsonl` | 50 | `pizza` (test/corpus/smoke/pizza.owl) | Main pizza benchmark. **5 of the 50 questions were revised in v0.8.1** to match the new claim-type schema. See below. |
| `owl2bench-30.jsonl` | 30 | `owl2bench` (test/corpus/benchmarks/owl2bench/) | OWL2Bench subset. No fixture changes in v0.8.1; the 1 originally-failing sample (owl2bench-027) is fixed by the implementation change in ISSUE-02. |
| `out-of-scope-cross.jsonl` | varies | varies | Cross-ontology out-of-scope edge cases. |

## v0.8.1 Fixture Revisions (`pizza-50.jsonl`)

Five of the 50 questions in `pizza-50.jsonl` were updated for v0.8.1. The
revisions are detailed below; each one is a "fixture bug fix" — the original
type was inconsistent with the `predicate` and `entity.kind` of the claim
(reasoning frameworks like v0.8.0's `verifyDisjointClasses` misroute an
individual IRI as a class IRI, and `verifyObjectPropertyAssertion` misroutes
a property IRI as an individual IRI). After v0.8.1 adds the correct claim
types, the revised fixtures are semantically correct.

| Question ID | Line | What changed | ISSUE | Why |
| --- | --- | --- | --- | --- |
| `pizza-007` | 7 | `object` changed from `{ "kind": "class", "iri": "Pizza" }` to `{ "kind": "class", "expression": { "type": "intersection", ... } }` describing `Pizza ∩ ∃hasTopping.CheeseTopping` | ISSUE-03 | The natural-language question is "Is CheeseyPizza equivalent to Pizza with some CheeseTopping?", so the object must be the intersection, not the bare `Pizza` class. The v0.8.0 system had no way to express complex class expressions, so the fixture was simplified to the bare class and would have returned `UNKNOWN` even with the v0.8.1 implementation. |
| `pizza-035` | 35 | `type` changed from `disjoint_classes` to `different_individuals` | ISSUE-04 | Both `subject.kind` and `object.kind` are `individual` and the predicate is `differentFrom`. v0.8.0's `verifyDisjointClasses` only handles class-level disjointness, so the question returned `UNKNOWN`. |
| `pizza-037` | 37 | `type` changed from `object_property_assertion` to `object_property_subproperty` | ISSUE-05 | Both `subject.kind` and `object.kind` are `object_property` and the predicate is `subPropertyOf`. v0.8.0's `verifyObjectPropertyAssertion` only handles individual-level property assertions, so the question returned `UNKNOWN`. |
| `pizza-046` | 46 | no change | ISSUE-02 | Already typed `object_property_domain` with `subject.kind: object_property, object.kind: class`. The expected verdict is `supported` once the reasoner-driven `isEntailed` fallback is added to `checkAxiomEntailment`. |
| `owl2bench-027` | 27 (in `owl2bench-30.jsonl`) | no change | ISSUE-02 | Already typed `subclass`. The expected verdict is `supported` once the `SubClassOf` case in `checkAxiomEntailment` is fixed to use `adapter.getUnderlyingReasoner()`. |

Each revised line carries a trailing `note` field summarizing the change for
future readers. JSONL is line-delimited JSON, and the `note` field is a
per-question metadata field, NOT a comment, so the file remains valid JSONL.

## What stayed the same

- The 75 originally-correct claims remain unchanged in their `type`, `subject`, `predicate`, `object` fields, and `expectedVerdict`. v0.8.1 must not regress any of them; in particular, `evidence.source` classifications must remain stable: previously `asserted` (was `"explicit"`) and `inferred` (was `"inferred"`) labels must not flip.
- The `pizza-046` and `owl2bench-027` lines are unchanged; only the implementation needs to fix the verdict.

## Reproducing the accuracy gate

```bash
# Load pizza and owl2bench ontologies into a workspace, then run:
.\gradlew.bat test --tests "*V081AcceptanceSuite*"
```

Expected: 80/80 `verdictMatch`, with the 5 listed questions now passing.
