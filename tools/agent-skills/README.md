# owl4agents Agent Skill Packs

Cross-agent skill assets for LLM agents using owl4agents ontology verification workflows.

These skill packs are committed release assets. They provide standard operating procedures (SOPs) that any LLM agent can follow to use owl4agents claim verification, evidence grounding, and scope checking capabilities.

## Directory Layout

```
tools/agent-skills/
├── README.md                              — This index
├── _shared/
│   └── references/
│       ├── verdict-policy.md              — Shared verdict interpretation policy
│       ├── claim-batch-schema.md          — Claim batch input schema reference
│       ├── evidence-citation-policy.md    — Evidence citation and anti-fabrication policy
│       ├── refusal-scope-policy.md        — Refusal and scope limitation policy
│       └── answer-review-sop.md           — Answer review SOP reference
├── owl4agents-claim-verification/
│   └── SKILL.md                           — Claim preparation and verification SOP
├── owl4agents-evidence-grounded-answer/
│   └── SKILL.md                           — Writing evidence-grounded answers SOP
├── owl4agents-ontology-scope-check/
│   └── SKILL.md                           — Entity/scope checking SOP
```

## Using Skill Packs

Each `SKILL.md` contains:
- A standard operating procedure for the skill
- Links to shared policy references (not duplicated divergent policy text)
- Examples using public fixtures and real workflow commands

All skills link to `_shared/references/verdict-policy.md` for verdict interpretation rather than duplicating policy text. This ensures consistency across skills and avoids divergent policy interpretations.

## Portable Paths and Fixture Examples

Skill examples use portable paths relative to the project root and reference real fixture ontology IDs from `test/fixtures/v0.5/`. No placeholder paths (`/path/to/`, `<your-`, `your-ontology-id`) are used.

## Safety Policy

Skills do not instruct agents to fabricate claims, evidence, IRIs, or citations. See `_shared/references/evidence-citation-policy.md` for the anti-fabrication policy.