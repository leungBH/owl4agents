# owl4agents Test Corpus

This directory organizes OWL/RDF test corpora for owl4agents acceptance and benchmark work.

## Public Fixtures Only

Do not place private or sensitive ontologies in this corpus. Use public ontologies and hand-made golden fixtures only.

## Corpus Suites

| Suite | Directory | Purpose | CI use |
| --- | --- | --- | --- |
| Smoke | `smoke/` | Small public ontologies for every commit | Always |
| Golden | `golden/` | Hand-made deterministic OWL feature tests | Always |
| Conformance | `conformance/owl2/` | W3C OWL 2 semantics and entailment tests | Selected subset |
| Constructs | `constructs/` | OWL language construct coverage | Selected subset |
| Realworld | `realworld/` | Public scientific ontology import and search | Nightly or release |
| Benchmarks | `benchmarks/` | OWL2Bench, LUBM, ORE-style performance and scale | Manual / benchmark |
| Large | `large/` | GO/HPO/Mondo/Uberon/ChEBI full-size stress tests | Manual |

## Already Downloaded

The following small smoke fixtures have been downloaded locally:

| File | Source | Purpose |
| --- | --- | --- |
| `smoke/pizza.owl` | Protégé Pizza Ontology | Class hierarchy, object properties, restrictions, equivalent/disjoint classes |
| `smoke/bfo.owl` | OBO Foundry BFO PURL | Upper ontology, imports/profile/top-level hierarchy smoke test |
| `benchmarks/lubm/univ-bench.owl` | Lehigh University Benchmark ontology | Classes, object properties, data properties, university-domain benchmark schema |
| `benchmarks/owl2bench/UNIV-BENCH-OWL2DL.owl` | OWL2Bench DL TBox | Construct-rich OWL 2 DL axioms for reasoner/profile tests |
| `benchmarks/owl2bench/UNIV-BENCH-OWL2EL.owl` | OWL2Bench EL TBox | OWL 2 EL profile and EL reasoner tests |
| `benchmarks/owl2bench/UNIV-BENCH-OWL2QL.owl` | OWL2Bench QL TBox | OWL 2 QL profile tests |
| `benchmarks/owl2bench/UNIV-BENCH-OWL2RL.owl` | OWL2Bench RL TBox | OWL 2 RL profile tests |
| `golden/v0.3-claim-verification.owl` | Project golden ontology | Deterministic v0.3 claim verification, evidence, unknown, and out-of-scope acceptance tests |
| `golden/v0.4-biomedical-grounding.owl` | Project golden ontology | Deterministic v0.4 biomedical grounding: disease hierarchy, phenotype, organ system, object/data properties, equivalent class, disjointness, and claim verification examples |

## Fixture Attribution

| Fixture | Source | License | Attribution |
| --- | --- | --- | --- |
| `smoke/pizza.owl` | [Protégé Pizza Ontology](https://protege.stanford.edu/ontologies/pizza/pizza.owl) | Creative Commons Attribution 4.0 (CC BY 4.0) | Stanford University / Protégé project. Used for class hierarchy, object property, and restriction smoke tests. |
| `smoke/bfo.owl` | [OBO Foundry BFO PURL](http://purl.obolibrary.org/obo/bfo.owl) | CC BY 4.0 | BFO2 Ontology, OBO Foundry. Used for upper ontology and import profile smoke. |
| `golden/v0.3-claim-verification.owl` | Project-created golden ontology | Apache-2.0 (project) | owl4agents project. Hand-made deterministic ontology for claim verification acceptance tests. |
| `golden/v0.4-biomedical-grounding.owl` | Project-created golden ontology | Apache-2.0 (project) | owl4agents project. Hand-made deterministic biomedical ontology for v0.4 grounding examples. Simplified educational model — not a clinical reference. |
| `golden/*-minimal.owl` through `09-malformed-rdf.owl` | Project-created golden fixtures | Apache-2.0 (project) | owl4agents project. Deterministic OWL feature test ontologies. |
| `benchmarks/lubm/univ-bench.owl` | [Lehigh University Benchmark](http://swat.cse.lehigh.edu/projects/lubm/) | Academic use | Lehigh University. Used for university-domain benchmark schema tests. |
| `benchmarks/owl2bench/*` | [OWL2Bench](https://github.com/IIIT-Delhi/OWL2Bench) | MIT License | IIIT-Delhi OWL2Bench. Used for OWL 2 profile and reasoner tests. |

## v0.3 Claim Verification Fixtures

v0.3 uses a small deterministic OWL fixture plus structured claim JSON files.

| File | Purpose |
| --- | --- |
| `golden/v0.3-claim-verification.owl` | Golden ontology for supported, contradicted, unknown, in-scope, and out-of-scope verdict paths |
| `../fixtures/v0.3/claim-supported.json` | Supported subclass claim with inferred evidence |
| `../fixtures/v0.3/claim-contradicted.json` | Disjoint-class / counterevidence path fixture |
| `../fixtures/v0.3/claim-unknown.json` | In-scope claim with insufficient axioms |
| `../fixtures/v0.3/claim-out-of-scope.json` | In-scope ontology-scope control fixture |
| `../fixtures/v0.3/claim-real-out-of-scope.json` | Real out-of-scope claim using an undeclared entity |
| `../fixtures/v0.3/claim-malformed.json` | Negative schema fixture expecting `INVALID_CLAIM_SCHEMA` |
| `../fixtures/v0.3/claim-unsupported-type.json` | Negative type fixture expecting `UNSUPPORTED_CLAIM_TYPE` |
| `../fixtures/v0.3/claim-unknown-ontology.json` | Unknown ontology fixture used with CLI/MCP ontology ID checks |
| `../fixtures/v0.3/claim-smoke-supported.json` | Ontology_scope supported claim for v0.3.1 onboarding smoke (entity in-scope) |

## v0.4 Biomedical Grounding Fixtures

v0.4 uses a small project-owned biomedical golden ontology plus structured claim JSON files.

| File | Purpose |
| --- | --- |
| `golden/v0.4-biomedical-grounding.owl` | Golden ontology for disease hierarchy, phenotype, organ, disjointness, and grounding examples |
| `../fixtures/v0.4/claim-bio-supported.json` | Supported subclass claim — Hypertension subClassOf Disease |
| `../fixtures/v0.4/claim-bio-unknown.json` | Unknown claim — Arthritis subClassOf InfectiousDisease (no direct axiom, contradicting disjointness exists) |
| `../fixtures/v0.4/claim-bio-out-of-scope.json` | Out_of_scope claim — CancerStage entity not in ontology |

## V086 Repro Package Fixtures (v0.8.8 Acceptance)

The V086 repro package contains 388 claims (across Pizza, HPO, Mondo, SOSA ontologies) used for v0.8.6→v0.8.8 acceptance testing. The fixtures are copied from `E:\V086_REPRO_PACKAGE\` and are **local-only** (gitignored).

| File | Purpose |
| --- | --- |
| `v086-repro/V086_FAILING_CLAIMS.jsonl` | 18 code-fixable claims (A1 6 + A2 11 + pizza-op-008 1) + 8 DEFERRED claims |
| `v086-repro/pizza-112.jsonl` | 100 Pizza non-regression claims (v0.8.6 baseline) |
| `v086-repro/hpo-60.jsonl` | 60 HPO non-regression claims |
| `v086-repro/hpo-extra-20.jsonl` | 20 HPO extra claims |
| `v086-repro/mondo-60.jsonl` | 60 Mondo non-regression claims |
| `v086-repro/mondo-extra-20.jsonl` | 20 Mondo extra claims |
| `v086-repro/sosa-84.jsonl` | 84 SOSA non-regression claims |

**Note:** The large ontologies (HPO ~74MB, Mondo ~236MB, SOSA) are NOT included in the repro package. Download them separately per the [Large Ontology Sources](#large-ontology-sources-v082-benchmark) section before running the full 388-claim acceptance suite.

## Large Ontology Sources (v0.8.2 Benchmark)

The following large ontologies are used in the v0.8.2 240-claim benchmark. They are **not committed** to the repository due to their size. Download manually before running benchmarks.

| Ontology | Source URL | Expected Filename | Approx Size | Benchmark Config |
| --- | --- | --- | --- | --- |
| HPO (Human Phenotype Ontology) | http://purl.obolibrary.org/obo/hp.owl | `hp.owl` | ~74 MB | `test/fixtures/v0.6/benchmark-configs/hpo-60.yaml` |
| Mondo Disease Ontology | http://purl.obolibrary.org/obo/mondo.owl | `mondo.owl` | ~236 MB | `test/fixtures/v0.6/benchmark-configs/mondo-60.yaml` |
| Pizza (smoke) | https://protege.stanford.edu/ontologies/pizza/pizza.owl | `pizza.owl` | <1 MB | `test/fixtures/v0.6/benchmark-configs/pizza-80.yaml` |
| SOSA (Sensor/Observation) | http://www.w3.org/ns/sosa/sosa.ttl | `sosa.ttl` | <1 MB | `test/fixtures/v0.6/benchmark-configs/sosa-40.yaml` |

### Download

```powershell
# HPO (~74MB)
Invoke-WebRequest -Uri "http://purl.obolibrary.org/obo/hp.owl" -OutFile "large/hp.owl"

# Mondo (~236MB)
Invoke-WebRequest -Uri "http://purl.obolibrary.org/obo/mondo.owl" -OutFile "large/mondo.owl"
```

### Import for Benchmark

After downloading, import each ontology into the owl4agents workspace:

```powershell
java -jar modules/ontology-cli/build/libs/owl4agents.jar import --ontology-id hpo --source-path test/corpus/large/hp.owl
java -jar modules/ontology-cli/build/libs/owl4agents.jar import --ontology-id mondo --source-path test/corpus/large/mondo.owl
```

### Cache Warm-up

v0.8.2 `OntologyCache` eliminates repeated 36-211s load times for HPO/Mondo. The benchmark runner warms up the cache by calling `getOrCreate()` for each ontology before processing the first claim. See `CliServiceFactory.getSharedOntologyCache()`.

## Recommended Golden Ontologies

Create these small deterministic fixtures by hand. Each file should have a matching `.expected.json`.

```text
01-subclass-transitive.owl
02-equivalent-classes.owl
03-disjoint-classes-inconsistent.owl
04-unsatisfiable-class.owl
05-object-property-domain-range.owl
06-data-property-domain-range.owl
07-inverse-property.owl
08-transitive-property.owl
09-functional-object-property.owl
10-functional-data-property.owl
11-cardinality-restriction.owl
12-some-values-from.owl
13-all-values-from.owl
14-has-value.owl
15-same-individual.owl
16-different-individuals.owl
17-negative-object-property-assertion.owl
18-negative-data-property-assertion.owl
19-datatype-restriction.owl
20-import-closure.owl
```

## Download Policy

Small fixtures may be downloaded into this directory. Large corpora should usually stay out of git and be downloaded manually or by explicit script option.

Use:

```powershell
.\tools\scripts\download-test-corpus.ps1 -Suite smoke
.\tools\scripts\download-test-corpus.ps1 -Suite realworld-small
.\tools\scripts\download-test-corpus.ps1 -Suite benchmarks
.\tools\scripts\download-test-corpus.ps1 -Suite all -IncludeLarge
```

Large downloads include resources such as ORE, Mondo, Uberon, and full benchmark corpora. These can be hundreds of MB or more.
