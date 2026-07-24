# v0.9.0 Acceptance Contracts (v089-entity-declaration-fix)

Source spec: `openspec/changes/v089-entity-declaration-fix/`
Test fixtures: `test/corpus/v089-full-suite/` (copied from `E:\full_test_suite`)

## Contract A1: P0 — Pizza 100 claims not evicted by cross-ontology cache

- **WHEN** `pizza-112.jsonl` (100 claims, Pizza ontology ~115 classes) is verified after HPO (~32K classes) and Mondo (~30K classes) are loaded simultaneously
- **THEN** every Pizza claim's `isEntityDeclared` SHALL return `true` for Pizza entities
- **AND** Pizza claim verdicts SHALL match `expected_results.json` baseline (supported/contradicted/unknown — NOT `out_of_scope` due to cache eviction)
- **AND** zero Pizza claims SHALL return `out_of_scope` solely because their entity signatures were evicted by HPO/Mondo cache pressure

## Contract A2: Multi-ontology load — pizza entities survive hpo+mondo load

- **WHEN** ontologies `pizza`, `hpo`, and `mondo` are loaded simultaneously via CLI/MCP
- **AND** `isEntityDeclared` queries a Pizza entity IRI (e.g., `http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza`)
- **THEN** the per-ontology `EntitySignatureCache` instance for `pizza` SHALL be used (not the hpo or mondo instance)
- **AND** the Pizza entity SHALL be found in the pizza cache even if the hpo cache is at capacity
- **AND** loading hpo SHALL NOT evict any pizza cache entries

## Contract A3: Predicate skip — reserved keywords not searched as entities

- **WHEN** `ontology_detect_missing_entities` receives a claim with `predicate="subClassOf"` (or any reserved keyword: `equivalentClasses`, `disjointClasses`, `subPropertyOf`, `equivalentProperties`, `propertyDisjointWith`, `type`, `domain`, `range`, `inverseOf`, `hasKey`, `hasValue`, `allValuesFrom`, `someValuesFrom`, `cardinality`, `minCardinality`, `maxCardinality`, `differentFrom`, `sameAs`, `propertyChainAxiom`, `hasSelf`)
- **THEN** the system SHALL NOT search for the predicate string as a property entity in the ontology
- **AND** the `missing` list SHALL NOT contain an entry for the predicate
- **AND** the `matched` list SHALL NOT contain an entry for the predicate
- **AND** when the predicate is an IRI (`http://` or `https://` prefix) and not in the reserved set, the system SHALL search it as a property entity

## Contract A4: Verdict vocabulary — `aggregateStatus: "supported"` (BREAKING)

- **WHEN** `verify_claims_batch` is called with a batch where all required claims verify as `supported`
- **THEN** the response field `aggregateStatus` SHALL be `"supported"` (not `"verified"`)
- **AND** downstream consumers checking `aggregateStatus == "verified"` MUST update to `aggregateStatus == "supported"`
- **AND** the `VERIFIED` enum constant's `jsonName()` SHALL return `"supported"`
