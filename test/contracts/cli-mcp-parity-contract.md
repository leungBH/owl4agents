# CLI/MCP Parity Test Contract

> Schema: exact-consistency-verification
> Date: 2026-07-13

## Parity Rule

For each semantic fixture, the CLI `verify-claim` command and MCP `verify` tool MUST produce identical:
1. `semanticVerdict` (supported/contradicted/unknown/out_of_scope/null)
2. `errorCode` (when executionStatus != completed)
3. Evidence structure (kind + description)
4. `metadata.reasonerName`

## Test Cases

| Fixture | CLI Command | MCP Tool Call | Expected Verdict |
|---|---|---|---|
| disjoint-no-witness.owl | `verify-claim --ontology test --type subclass --subject C --predicate subClassOf --object D` | `ontology_verify_claim` with same params | UNKNOWN |
| disjoint-with-witness.owl | `verify-claim --ontology test --type subclass --subject C --predicate subClassOf --object D` | `ontology_verify_claim` with same params | CONTRADICTED |
| empty-class.owl | `verify-claim --ontology test --type subclass --subject C --predicate subClassOf --object owl:Nothing` | `ontology_verify_claim` with same params | SUPPORTED |
| source-inconsistent.owl | `verify-claim --ontology test --type subclass --subject C --predicate subClassOf --object D` | `ontology_verify_claim` with same params | ERROR (SOURCE_ONTOLOGY_INCONSISTENT) |
| timeout-large.owl | `verify-claim --ontology test --timeout 1ms --type subclass --subject Conflict --predicate subClassOf --object Root` | `ontology_verify_claim` with timeout=1ms | TIMEOUT (REASONER_TIMEOUT) |

## Batch Parity

- Same claim in batch and individually MUST produce identical result
- 10 mixed claims in 2 different orders MUST produce identical results
