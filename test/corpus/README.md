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
.\scripts\download-test-corpus.ps1 -Suite smoke
.\scripts\download-test-corpus.ps1 -Suite realworld-small
.\scripts\download-test-corpus.ps1 -Suite benchmarks
.\scripts\download-test-corpus.ps1 -Suite all -IncludeLarge
```

Large downloads include resources such as ORE, Mondo, Uberon, and full benchmark corpora. These can be hundreds of MB or more.
