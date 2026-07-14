# Exact Consistency Acceptance Report Contract

> Schema: exact-consistency-verification
> Version: v2
> Date: 2026-07-13

## Format

Each acceptance report MUST follow this structure:
- UTF-8 encoded markdown
- ASCII status words (PASS/FAIL/SKIP)
- Reproducible command evidence

## Scenario Contract

| Scenario ID | Fixture | Interface | Expected Verdict | Must Fail If |
|---|---|---|---|---|
| EC-DISJOINT-NO-WITNESS | disjoint-no-witness.owl | Unit | UNKNOWN | Returns CONTRADICTED |
| EC-DISJOINT-WITH-WITNESS | disjoint-with-witness.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-EMPTY-CLASS | empty-class.owl | Unit | SUPPORTED | Returns UNKNOWN/CONTRADICTED |
| EC-EXISTENTIAL-NO-WITNESS | existential-no-witness.owl | Unit | UNKNOWN | Returns CONTRADICTED |
| EC-EXISTENTIAL-WITH-WITNESS | existential-with-witness.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-NEGATIVE-MEMBERSHIP | negative-membership.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-NEGATIVE-PROP-ASSERTION | negative-prop-assertion.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-EQUIV-DISJOINT-NO-WITNESS | equiv-disjoint-no-witness.owl | Unit | UNKNOWN | Returns CONTRADICTED |
| EC-EQUIV-DISJOINT-WITH-WITNESS | equiv-disjoint-with-witness.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-DATA-PROPERTY-FACET-CONFLICT | data-property-facet-conflict.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-DIFFERENT-INDIVIDUALS-WITNESS | different-individuals-witness.owl | Unit | CONTRADICTED | Returns UNKNOWN |
| EC-SUBPROPERTY-HIERARCHY-CONFLICT | subproperty-hierarchy-conflict.owl | Unit | CONTRADICTED | Proxy verdict without exact check |
| EC-SOURCE-INCONSISTENT | source-inconsistent.owl | Unit | SOURCE_ONTOLOGY_INCONSISTENT, verdict=null | Returns UNKNOWN/CONTRADICTED |
| EC-IMPORTS-CLOSURE | imports-closure-main.owl | Unit | Exact check detects conflict | Conflict not detected |
| EC-TIMEOUT | timeout-large.owl | Unit | REASONER_TIMEOUT, verdict=null | Returns UNKNOWN |
| EC-AXIOM-BUILD-FAIL | Invalid IRI in claim | Unit | CLAIM_AXIOM_BUILD_FAILED | Returns UNKNOWN |
| EC-SOURCE-IMMUTABILITY | Any ontology | Unit | Source axiom count + hash unchanged | Source modified |
| EC-SESSION-DISPOSE | Any ontology | Unit | No linear memory growth | Memory grows linearly |
| EC-CONCURRENT-ISOLATION | Same ontology | Unit | No interference between threads | Thread A's result affected by thread B |
| EC-CLI-PARITY-1 | Disjoint witness fixture | CLI | Same verdict as MCP tool | Different verdict |
| EC-MCP-PARITY-1 | Disjoint no-witness fixture | MCP | Same verdict as CLI | Different verdict |
| EC-SCHEMA-V2 | Any completed claim | CLI/MCP | schemaVersion="claim-verification-result/2" | Missing schemaVersion |
| EC-PROXY-HINT | DisjointClasses(C,D) | Unit/MCP | UNKNOWN + STRUCTURAL_CONFLICT_HINT evidence | CONTRADICTED without exact check |
