# Test Corpus Manifest

## Golden Fixtures (test/corpus/golden/)

| Fixture | File | Purpose | v0.1 Gates |
| --- | --- | --- | --- |
| Minimal ontology | `01-minimal.owl` | Basic import and summary: one class, no properties, no individuals | Import, Summary |
| Subclass transitive | `02-subclass-transitive.owl` | Transitive subclass hierarchy (Animal → Mammal → Dog/Cat → GoldenRetriever) | Import, Summary, Class context (super/sub) |
| Equivalent classes | `03-equivalent-classes.owl` | owl:equivalentClass axioms (Person≡Human, Employee≡Worker) | Import, Summary, Class context (equivalent) |
| Disjoint classes | `04-disjoint-classes.owl` | owl:disjointWith and AllDisjointClasses axioms | Import, Summary, Class context (disjoint) |
| Property axioms | `05-property-axioms.owl` | Object property domain/range/inverse/hierarchy + Data property domain/range/datatype/hierarchy | Import, Summary, Object/Data property context |
| Individual assertions | `06-individual-assertions.owl` | Named individuals with type, object property, and data property assertions | Import, Summary, Individual context, Entity search |
| SPARQL safety | `07-sparql-safety.owl` | Small ABox for SELECT/ASK/CONSTRUCT/DESCRIBE + update rejection | Import, Summary, SPARQL (all forms + rejection) |
| Invalid import (text) | `08-invalid-import.txt` | Not OWL/RDF — tests structured import error handling | Import (error) |
| Malformed RDF | `09-malformed-rdf.owl` | Invalid RDF syntax — tests OWL API parser error handling | Import (error) |

## Smoke Fixtures (test/corpus/smoke/)

| Fixture | File | Source | Purpose | v0.1 Gates |
| --- | --- | --- | --- | --- |
| Pizza ontology | `pizza.owl` | https://protege.stanford.edu/ontologies/pizza/pizza.owl | Class hierarchy, object properties, restrictions, equivalent/disjoint classes | Import, Summary, Class context, Entity search |
| BFO | `bfo.owl` | http://purl.obolibrary.org/obo/bfo.owl | Upper ontology, imports/profile/top-level hierarchy | Import, Summary, Profile |

## Benchmark Fixtures (test/corpus/benchmarks/)

| Fixture | File | Source | Purpose | v0.1 Gates |
| --- | --- | --- | --- | --- |
| LUBM schema | `lubm/univ-bench.owl` | http://swat.cse.lehigh.edu/onto/univ-bench.owl | University-domain classes, object/data properties, SPARQL benchmark | Import, Summary, Entity search |
| OWL2Bench DL | `owl2bench/UNIV-BENCH-OWL2DL.owl` | https://github.com/kracr/owl2bench | OWL 2 DL construct-rich TBox | Import, Summary, Profile |
| OWL2Bench EL | `owl2bench/UNIV-BENCH-OWL2EL.owl` | https://github.com/kracr/owl2bench | OWL 2 EL profile TBox | Import, Summary, Profile |
| OWL2Bench QL | `owl2bench/UNIV-BENCH-OWL2QL.owl` | https://github.com/kracr/owl2bench | OWL 2 QL profile TBox | Import, Summary, Profile |
| OWL2Bench RL | `owl2bench/UNIV-BENCH-OWL2RL.owl` | https://github.com/kracr/owl2bench | OWL 2 RL profile TBox | Import, Summary, Profile |

## External Conformance and Future Benchmark Corpus Sources

| Source | Suggested location | Download / source | Use | v0.1 scope |
| --- | --- | --- | --- | --- |
| W3C OWL 2 test cases | `test/corpus/conformance/owl2/` | https://www.w3.org/2009/owl-test-cases | Standard semantics, entailment, consistency | Future (v0.2+) |
| OWL2Bench ABox | `test/corpus/benchmarks/owl2bench/` | https://github.com/kracr/owl2bench and Zenodo | ABox scale, query performance | Future (v0.2+) |
| LUBM generated data | `test/corpus/benchmarks/lubm/generated/` | https://swat.cse.lehigh.edu/projects/lubm/ | ABox, SPARQL, generated benchmark data | Future (v0.2+) |
| GO basic | `test/corpus/realworld/go-basic.owl` | http://purl.obolibrary.org/obo/go/go-basic.owl | Large class hierarchy and labels | Future (v0.2+) |
| HPO | `test/corpus/realworld/hp.owl` | http://purl.obolibrary.org/obo/hp.owl | Phenotype search and biomedical QA context | Future (v0.2+) |
| OBI | `test/corpus/realworld/obi.owl` | http://purl.obolibrary.org/obo/obi.owl | Scientific investigation workflow context | Future (v0.2+) |
| ORE 2015 corpus | `test/corpus/large/ore/` | Zenodo (~725 MB) | Reasoner benchmark | Future (manual) |
| Mondo | `test/corpus/large/mondo.owl` | OBO Foundry | Large biomedical ontology | Future (manual) |
| Uberon | `test/corpus/large/uberon.owl` | OBO Foundry | Large anatomy ontology | Future (manual) |
| ChEBI | `test/corpus/large/chebi.owl` | OBO Foundry | Large chemical ontology | Future (manual) |

## Coverage Matrix

| Feature | Golden | Smoke (Pizza/BFO) | Benchmarks (LUBM/OWL2Bench) | v0.1 required |
| --- | --- | --- | --- | --- |
| Class hierarchy | ✓ (02) | ✓ (Pizza) | ✓ | Required |
| Object property | ✓ (05) | ✓ (Pizza) | ✓ | Required |
| Data property | ✓ (05) | Partial | ✓ (LUBM) | Required |
| Individuals | ✓ (06) | Partial (Pizza) | ✓ | Required |
| Equivalent classes | ✓ (03) | ✓ (Pizza) | ✓ (OWL2Bench DL) | Required |
| Disjoint classes | ✓ (04) | ✓ (Pizza) | ✓ | Required |
| Domain / range | ✓ (05) | ✓ (Pizza) | ✓ | Required |
| Restrictions | Partial | ✓ (Pizza) | ✓ | Required |
| Inverse properties | ✓ (05) | ✓ (Pizza) | Partial | Required |
| Datatype constraints | Partial | Partial | ✓ (LUBM) | Required |
| SPARQL | ✓ (07) | Partial | ✓ (LUBM) | Required |
| Import error handling | ✓ (08, 09) | ✓ | ✓ | Required |
| Profile inspection | Partial | ✓ (BFO) | ✓ (OWL2Bench) | Required |
| Agent QA context | ✓ (06) | ✓ (Pizza) | ✓ (LUBM) | Required |