# 388-Claim Benchmark Fixture List

> Schema: exact-consistency-verification
> Date: 2026-07-13
> Purpose: Map each benchmark claim to its expected verdict and ontology

## Summary

| Ontology | Total Claims | SUPPORTED | CONTRADICTED | UNKNOWN | OUT_OF_SCOPE |
|----------|-------------|-----------|-------------|---------|-------------|
| Pizza | 120 | 45 | 25 | 30 | 20 |
| HPO | 100 | 35 | 15 | 30 | 20 |
| Mondo | 100 | 30 | 20 | 30 | 20 |
| SOSA | 68 | 25 | 10 | 18 | 15 |
| **Total** | **388** | **135** | **70** | **108** | **75** |

## Sources

1. **v0.8.1 Acceptance Suite** (80 claims): `test/fixtures/v0.6/question-sets/pizza-50.jsonl` and `test/fixtures/v0.6/question-sets/pizza-80.jsonl` — Pizza ontology
2. **v0.6 Error Claims** (9 claims): `test/fixtures/v0.6/error-claims/` — Pizza, HPO, Mondo
3. **Exact Consistency Fixtures** (15 claims): `test/corpus/exact-consistency/` — synthetic ontologies
4. **HPO Benchmark Claims** (100 claims): generated from HPO ontology structure
5. **Mondo Benchmark Claims** (100 claims): generated from Mondo ontology structure
6. **SOSA Benchmark Claims** (68 claims): generated from SOSA ontology structure
7. **Cross-ontology OOS Claims** (15 claims): entities from one ontology tested against another

## Exact Consistency Fixture Claims (15)

| # | Fixture | Claim Type | Subject | Predicate | Object | Expected Verdict |
|---|---------|-----------|---------|-----------|--------|-----------------|
| 1 | disjoint-no-witness.owl | SUBCLASS | C | subClassOf | D | UNKNOWN |
| 2 | disjoint-with-witness.owl | SUBCLASS | C | subClassOf | D | CONTRADICTED |
| 3 | empty-class.owl | SUBCLASS | C | subClassOf | owl:Nothing | SUPPORTED |
| 4 | existential-no-witness.owl | SUBCLASS | C | subClassOf | D | UNKNOWN |
| 5 | existential-with-witness.owl | SUBCLASS | C | subClassOf | D | CONTRADICTED |
| 6 | negative-membership.owl | INDIVIDUAL_MEMBERSHIP | a | type | C | CONTRADICTED |
| 7 | negative-prop-assertion.owl | OBJECT_PROPERTY_ASSERTION | a | r | b | CONTRADICTED |
| 8 | equiv-disjoint-no-witness.owl | EQUIVALENT_CLASSES | C | equivalentTo | D | UNKNOWN |
| 9 | equiv-disjoint-with-witness.owl | EQUIVALENT_CLASSES | C | equivalentTo | D | CONTRADICTED |
| 10 | data-property-facet-conflict.owl | DATA_PROPERTY_ASSERTION | a | r | "5"^^xsd:integer | CONTRADICTED |
| 11 | different-individuals-witness.owl | DIFFERENT_INDIVIDUALS | a | differentFrom | b | CONTRADICTED |
| 12 | subproperty-hierarchy-conflict.owl | OBJECT_PROPERTY_ASSERTION | x | r | y | CONTRADICTED |
| 13 | source-inconsistent.owl | SUBCLASS | C | subClassOf | D | SOURCE_ONTOLOGY_INCONSISTENT |
| 14 | imports-closure-main.owl | SUBCLASS | E | subClassOf | C | CONTRADICTED |
| 15 | timeout-large.owl | SUBCLASS | Conflict | subClassOf | Root | REASONER_TIMEOUT (with 1ms timeout) |

## Note

The full 388-claim fixture list is generated at benchmark runtime from the ontology structure.
This document serves as the contract for expected verdicts.
