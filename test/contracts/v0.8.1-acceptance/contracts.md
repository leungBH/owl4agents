# v0.8.1 Acceptance Contracts

## Principle

v0.8.1 is a patch release that fixes 5 claim-verification root-cause defects
(see `doc/retrospectives/2026-07-08-system-issues-v0.8.0.md` for the 5 ISSUE
descriptions) and improves the 80-curated-claim accuracy gate from 75/80 to
80/80. This contract defines the 29 acceptance gates (TC-1 through TC-29) that
v0.8.1 MUST pass before being tagged as released.

v0.8.1 is **backward-compatible** with v0.8.0:
- The MCP protocol contract is unchanged.
- The readonly tool count remains 56 (no new MCP tool).
- Existing v0.8.0 client claims parse unchanged (the `object.expression`
  field is optional; new `ClaimType` values are additive).

## Cross-Reference Matrix

| ISSUE | Root cause | Fix in v0.8.1 | TC scenarios |
| --- | --- | --- | --- |
| ISSUE-01 | `ClaimVerificationService.verify` lacks a global scope pre-check; external IRIs reach type-specific verifiers and return UNKNOWN instead of OUT_OF_SCOPE. | Section 4: scope pre-check in `verify()` before the switch, with `ONTOLOGY_SCOPE` / `ONTOLOGY_CONSISTENCY` / `LITERAL_VALIDITY` exemption and a built-in namespace whitelist (`xsd:`, `rdf:`, `rdfs:`, `owl:`). | TC-1, TC-2, TC-3, TC-22, TC-23, TC-24 |
| ISSUE-02 | `ReasonerServiceImpl.checkAxiomEntailment` has no `ObjectPropertyDomain` / `ObjectPropertyRange` / `DataPropertyDomain` / `DataPropertyRange` cases; the broken `SubClassOf` case calls `getOWLReasonerFromAdapter(adapter)` which returns `null`. | Section 5: add `getUnderlyingReasoner()` to the adapter interface; create the 4 domain/range cases using asserted-first-then-isEntailed-fallback; fix the `SubClassOf` case to use `adapter.getUnderlyingReasoner()`. | TC-4, TC-5, TC-15 (v0.8.0 SSE regression), TC-26, TC-27 |
| ISSUE-03 | `EquivalentClasses` has no case; `ClaimEntity` has no `expression` field; `buildEntailmentParams` cannot express complex class expressions. | Section 3 + Section 6: add sealed `ClassExpression` interface with 6 record implementations; add `ClassExpressionBuilder`; create the `EquivalentClasses` case in `checkEntailment`; extend `ClaimEntity` with optional `expression`. | TC-6, TC-7, TC-8, TC-25 |
| ISSUE-04 | `ClaimType` lacks `DIFFERENT_INDIVIDUALS`; individual-vs-individual "different" claims are routed through `verifyDisjointClasses` (class-level only). | Section 3 + Section 7: add `DIFFERENT_INDIVIDUALS` to `ClaimType`; add `verifyDifferentIndividuals` and the `DifferentIndividuals` case in `checkEntailment` (asserted-first then `isEntailed` fallback). | TC-9, TC-10, TC-11 |
| ISSUE-05 | `ClaimType` lacks `OBJECT_PROPERTY_SUBPROPERTY`; `SubObjectPropertyOf` not in `supportedTypes`; property-hierarchy claims are routed through `verifyObjectPropertyAssertion` (individual-level). | Section 3 + Section 8: add `OBJECT_PROPERTY_SUBPROPERTY` to `ClaimType`; add `verifySubPropertyOf` and the `SubObjectPropertyOf` case in `checkEntailment`. | TC-12, TC-13 |

## Test Matrix (TC-1 through TC-29)

The full 29-scenario matrix is defined in `design.md` §"Test Matrix". Each
scenario below references the TC ID, the contract, the JUnit class + method
that covers it, and the required PASS criteria.

### ISSUE-01 scope pre-check (TC-1, TC-2, TC-3, TC-22, TC-23, TC-24)

| TC | Scenario | Test class | Method | Required result |
| --- | --- | --- | --- | --- |
| TC-1 | subclass claim with external subject IRI | ClaimVerificationScopePrecheckTest | `externalSubjectIriReturnsOutOfScope` | verdict=`out_of_scope`, unknownReason=`missing_entity`, `verifyEntailmentClaim` not invoked |
| TC-2 | subclass claim with external object IRI | ClaimVerificationScopePrecheckTest | `externalObjectIriReturnsOutOfScope` | verdict=`out_of_scope`, unknownReason=`missing_entity` |
| TC-3 | ontology_scope claim with external subject IRI (exempt type) | ClaimVerificationScopePrecheckTest | `ontologyScopeSkipsPrecheck` | behavior identical to v0.8.0; `verifyOntologyScope` invoked, pre-check does not run |
| TC-22 | exemption list + namespace whitelist | ScopePrecheckExemptionListTest | (3 cases) | `ontology_scope`, `ontology_consistency`, `literal_validity` skip pre-check; `xsd:` / `rdf:` / `rdfs:` / `owl:` return true via whitelist |
| TC-23 | expression-only entity bypasses top-level scope check | ExpressionOnlyEntityScopeBypassTest | (2 cases) | `iri==null && expression!=null` skips subject/object scope check; nested IRIs validated by `ClassExpressionBuilder` (`ENTITY_NOT_FOUND`) |
| TC-24 | built-in namespace whitelist | BuiltinNamespaceWhitelistTest | (4 cases) | `isEntityInOntology` returns true for `xsd:string`, `rdfs:label`, `owl:Thing`, etc. |

### ISSUE-02 reasoner-driven entailment (TC-4, TC-5, TC-15, TC-26, TC-27)

| TC | Scenario | Test class | Method | Required result |
| --- | --- | --- | --- | --- |
| TC-4 | inverse-property domain inferred via `isEntailed` | ReasonerEntailmentInferenceTest | `inversePropertyDomainIsDetectedViaIsEntailedFallback` | `pizza-046` (isBaseOf domain PizzaBase) returns `supported`, evidence.source=`inferred` |
| TC-5 | inverse-property range inferred via `isEntailed` | ReasonerEntailmentInferenceTest | `inversePropertyRangeIsDetected` | isBaseOf range Pizza returns `supported` |
| TC-15 | v0.8.0 SSE regression suite zero failures | HttpMcpServerSseTest | (18 cases) | all 18 SSE tests PASS (no regression) |
| TC-26 | `isEntailed` classification precondition | IsEntailedClassificationPreconditionTest | (2 cases) | before `isEntailed`, reasoner is classified; otherwise `UNKNOWN` + `missing_reasoning` |
| TC-27 | `adapter.getUnderlyingReasoner()` method exists | AdapterGetUnderlyingReasonerTest | (3 cases, HermiT/ELK/Openllet) | method returns the raw `OWLReasoner` instance |

### ISSUE-03 complex class expressions (TC-6, TC-7, TC-8, TC-25)

| TC | Scenario | Test class | Method | Required result |
| --- | --- | --- | --- | --- |
| TC-6 | equivalent_classes with complex object expression | ComplexClassExpressionTest | `intersectionWithExistentialIsSupported` | `pizza-007` (CheeseyPizza ≡ Pizza ∩ ∃hasTopping.CheeseTopping) returns `supported`, evidence.source=`inferred` |
| TC-7 | subject is a complex expression | ComplexClassExpressionTest | `subjectExpressionIsSupported` | verdict=`supported` (bidirectional equivalence) |
| TC-8 | malformed expression structure | ComplexClassExpressionTest | `malformedExpressionReturnsInvalidSchema` | error=`invalid_claim_schema` |
| TC-25 | unsupported expression type (e.g. `cardinality_restriction`) | UnsupportedExpressionTypeRejectionTest | (3 cases) | `INVALID_CLAIM_SCHEMA`, error message lists the 6 supported types |

### ISSUE-04 DifferentIndividuals (TC-9, TC-10, TC-11)

| TC | Scenario | Test class | Method | Required result |
| --- | --- | --- | --- | --- |
| TC-9 | asserted different individuals | DifferentIndividualsVerificationTest | `assertedDifferentIndividualsIsSupported` | `pizza-035` (France / Germany) returns `supported`, evidence.source=`asserted` |
| TC-10 | inferred different individuals | DifferentIndividualsVerificationTest | `inferredDifferentFromIsSupported` | synthetic mini-ontology, verdict=`supported`, evidence.source=`inferred` |
| TC-11 | same individual contradicts different from | DifferentIndividualsVerificationTest | `sameIndividualContradictsDifferentFrom` | verdict=`contradicted`, counterEvidence.source=`inferred_same_individual` |

### ISSUE-05 SubObjectPropertyOf (TC-12, TC-13)

| TC | Scenario | Test class | Method | Required result |
| --- | --- | --- | --- | --- |
| TC-12 | asserted sub-property | SubObjectPropertyVerificationTest | `assertedSubPropertyIsSupported` | `pizza-037` (hasBase ⊑ hasIngredient) returns `supported`, evidence.source=`asserted` |
| TC-13 | reversed sub-property (contradicted or unknown) | SubObjectPropertyVerificationTest | `reversedSubPropertyIsContradictedOrUnknown` | verdict=`contradicted` if reverse entailed, else `unknown` |

### Acceptance gates (TC-14, TC-16, TC-17, TC-18, TC-19, TC-20)

| TC | Scenario | Test class / method | Required result |
| --- | --- | --- | --- |
| TC-14 | 80 curated claims accuracy gate (after) | V081AcceptanceSuite.`fullCuratedClaimsAccuracyGate` | accuracy = 80/80; pizza-007 / pizza-035 / pizza-037 / pizza-046 / owl2bench-027 verdictMatch = true; `pizza-046` source = `inferred`; `pizza-035` source = `asserted`; `pizza-037` source = `asserted` |
| TC-14 sub | v0.8.0-on-revised-fixtures regression | V081AcceptanceSuite.`v080RegressionOnRevisedFixtures` (may be manual) | v0.8.0 jar against revised fixtures: accuracy < 80/80 |
| TC-16 | v0.8.0 stdio / plain-HTTP regression | HttpMcpServerTest v0.7.1 regression set | all cases PASS |
| TC-17 | readonly tool count = 56 | McpToolRegistryTest.`toolCountRemains56` | `new McpToolRegistry().listToolSchemas().size() == 56` |
| TC-18 | CLI / MCP parity on 5 fix scenarios | V081AcceptanceSuite or manual acceptance step | verdict / unknownReason / evidence.source field-level equivalence |
| TC-19 | version-alignment 8-point job | VersionAlignmentTest or CI job | all 8 version-string locations report `0.8.1` |
| TC-20 | SYSTEM_ISSUES.md archived | file system check | `doc/retrospectives/2026-07-08-system-issues-v0.8.0.md` exists with full content |

### Implementation pre-conditions (TC-21, TC-28, TC-29)

| TC | Scenario | Test class | Required result |
| --- | --- | --- | --- |
| TC-21 | pizza.owl axiom presence | PizzaFixtureAxiomVerificationTest | all 6 required axioms declared (see Task 0.2) |
| TC-28 | OntologyIriResolver exists | OntologyIriResolverTest | final class, private constructor, `public static IRI resolveOntologyIRI(OWLOntology, String, String)`; ReasonerServiceImpl and ClassExpressionBuilder delegate to it |
| TC-29 | example.yaml version references | ExampleClaimFileVersionCheckTest | references v0.8.1 claim types and at least one `object.expression` example |

## PASS/FAIL/SKIP/BLOCKED/DEFERRED Rules

See `test/contracts/acceptance-report/contracts.md` for the universal rules.
v0.8.1-specific additions:

- **SKIP** is allowed only for TC-14 sub-step (v0.8.0-on-revised-fixtures),
  which may be executed manually with results recorded in the acceptance
  report. The automated TC-14 (80/80 on v0.8.1 code) is REQUIRED.
- All other TC scenarios are REQUIRED and cannot be SKIPped.
- A regression in TC-15 (v0.8.0 SSE) or TC-16 (v0.8.0 stdio / HTTP) is
  a release blocker (S0) — v0.8.1 must be backward-compatible with v0.8.0
  transport behavior.
- A regression in TC-17 (readonly tool count != 56) is a release blocker.

## Fixture List

- `test/corpus/smoke/pizza.owl` (160KB, OWL 2 DL)
- `test/corpus/benchmarks/owl2bench/` (OWL2Bench corpus, OWL 2 DL)
- `test/fixtures/v0.6/question-sets/pizza-50.jsonl` (50 questions, 3 revised
  in v0.8.1: pizza-007, pizza-035, pizza-037)
- `test/fixtures/v0.6/question-sets/owl2bench-30.jsonl` (30 questions,
  no fixture changes in v0.8.1; implementation fix suffices for owl2bench-027)
- `examples/agent-mcp/example.yaml` (version reference updated in v0.8.1)

## Related OpenSpec Change

- `openspec/changes/patch-v0-8-1-claim-verification-accuracy/` (this change)
- `openspec/changes/archive/v0.8.0/` (predecessor; v0.8.0 SSE regression in
  TC-15 comes from the v0.8.0 release-readiness contract)

## README / FEATURES Claims to Verify

- README.md / README.zh-CN.md claim type list includes `different_individuals`
  and `object_property_subproperty`.
- FEATURES.md / FEATURES.zh-CN.md claim accuracy updated from "93.75% accuracy"
  to "100% accuracy on the 80 curated claims (v0.8.1)".
- CHANGELOG.md has a `## 0.8.1` entry.
