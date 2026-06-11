# v0.5 Fixture-to-Gate Mapping

This document maps each acceptance gate to its required input fixture.

| Gate ID | Fixture | Ontology | Interface |
| --- | --- | --- | --- |
| V05-WF-001 | test/fixtures/v0.5/answer-claims-supported.json | v0.3-claim-verification | CLI |
| V05-WF-002 | test/fixtures/v0.5/answer-claims-contradicted.json | v0.3-claim-verification | CLI |
| V05-WF-003 | test/fixtures/v0.5/answer-claims-unknown.json | v0.3-claim-verification | CLI |
| V05-WF-004 | test/fixtures/v0.5/answer-claims-out-of-scope.json | v0.3-claim-verification | CLI |
| V05-WF-005 | test/fixtures/v0.5/answer-claims-partially-verified.json | v0.3-claim-verification | CLI |
| V05-WF-006 | test/fixtures/v0.5/answer-claims-malformed.json | (none — malformed) | CLI |
| V05-WF-007 | test/fixtures/v0.5/answer-claims-v03-wrapped.json | v0.3-claim-verification | CLI |
| V05-CTX-001 | test/fixtures/v0.5/answer-claims-mixed.json | v0.3-claim-verification | CLI |
| V05-MCP-001 | test/fixtures/v0.5/answer-claims-supported.json | v0.3-claim-verification | MCP |
| V05-MCP-002 | test/fixtures/v0.5/answer-claims-mixed.json | v0.3-claim-verification | MCP |
| V05-SKILL-001 | skills/**/SKILL.md | (file validation) | File |
| V05-SKILL-002 | skills/*/examples/ | (file validation) | File |
| V05-PROMPT-001 | prompts/*.md | (file/MCP validation) | File/MCP |
| V05-REL-001 | (full build) | (build/test) | Build |

All workflow fixtures reuse existing v0.3/v0.4 golden ontologies:
- `test/corpus/golden/v0.3-claim-verification.owl` — for claim-verification and mixed workflows
- `test/corpus/golden/v0.4-biomedical-grounding.owl` — optional biomedical workflow fixture
- `test/corpus/smoke/pizza.owl` — optional pizza reasoning fixture

No new golden ontology is needed for v0.5 workflow fixtures.