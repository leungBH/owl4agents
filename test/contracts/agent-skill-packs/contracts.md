# Agent Skill Packs Contract

This contract defines the v0.5 public agent skill packs, including directory layout, shared policy references, portable paths, SOP content, lint patterns, and forbidden unsafe instructions.

## Required Skill Packs

v0.5 MUST provide the following skill packs under `agent-skills/`:

| Skill Pack ID | Directory | Required |
| --- | --- | --- |
| owl4agents-claim-verification | `agent-skills/owl4agents-claim-verification/` | yes |
| owl4agents-evidence-grounded-answer | `agent-skills/owl4agents-evidence-grounded-answer/` | yes |
| owl4agents-ontology-scope-check | `agent-skills/owl4agents-ontology-scope-check/` | yes |

Each skill pack MUST contain `SKILL.md` as the entry point.

Skill packs MUST NOT live under ignored local agent configuration directories (`.codex/`, `.claude/`, `.trae/`, or any local-only config).

## Skill Metadata Requirements

Each `SKILL.md` MUST include:

| Section | Required | Description |
| --- | --- | --- |
| name | yes | Skill name |
| description | yes | One-line description |
| trigger guidance | yes | When an agent should use this skill |
| SOP steps | yes | Step-by-step procedure for the agent |
| references | yes | Links to local files within the skill pack |

Placeholder text (`TODO`, `FIXME`, `stub`, `not implemented`) MUST fail validation.

## Shared Verdict Policy Reference

1. All skill packs MUST link to the shared verdict policy at `agent-skills/_shared/references/verdict-policy.md`.
2. Individual skill packs MUST NOT duplicate divergent verdict policy text.
3. Verdict terms MUST be: `supported`, `contradicted`, `unknown`, and `out_of_scope`.
4. The shared policy MUST state that `unknown` and `out_of_scope` do not count as support.

## Shared Reference Files

The `agent-skills/_shared/references/` directory MUST contain:

| File | Required | Description |
| --- | --- | --- |
| `verdict-policy.md` | yes | Shared verdict handling policy |
| `claim-batch-schema.md` | yes | Claim batch input format reference |
| `evidence-citation-policy.md` | yes | How to cite evidence from workflow reports |
| `refusal-and-scope-policy.md` | yes | How to handle contradicted/unknown/out-of-scope claims |
| `answer-review-sop.md` | yes | SOP for review-answer workflow |

All reference links MUST resolve relative to the skill directory using forward slashes.

## Skill Path Portability

1. Links and example commands MUST use cross-platform relative paths or repository-root-relative paths with forward slashes.
2. Skill files MUST NOT contain placeholder paths: `/path/to/...`, `<your-...>`, `your-ontology-id`, or local-only paths such as `.codex/`, `.claude/`, `.trae/`, or `~/.codex/`.
3. Ontology IDs in examples MUST use real documented fixture IDs: `v0.3-claim-verification`, `pizza`, `v0.4-biomedical-grounding`.

## Skill SOP Content Requirements

### Claim Verification SOP

1. MUST instruct agents to provide structured claim JSON to owl4agents.
2. MUST state that owl4agents v0.5 does NOT extract claims from prose.
3. MUST prefer `verify-answer` or `ontology_verify_claims_batch` over manually composing many low-level calls.
4. MUST state that `unknown` and `out_of_scope` do not count as support.
5. MUST require contradicted claims to be surfaced rather than hidden.

### Evidence Grounded Answer SOP

1. MUST instruct agents to cite only evidence returned by workflow reports or evidence context.
2. MUST forbid invented axioms, triples, labels, and reasoner conclusions.
3. MUST instruct agents to qualify answers when workflow reports contain `unknown` or `insufficient_evidence`.
4. MUST instruct agents to state that the ontology does not cover out-of-scope claims.

### Scope Check SOP

1. MUST instruct agents to check entity scope before verification.
2. MUST use `ontology_detect_missing_entities` or workflow scope diagnostics.
3. MUST NOT present out-of-scope claims as ontology-supported.

## Skill Lint Patterns

Validation MUST reject:

| Pattern | Category | Examples |
| --- | --- | --- |
| Placeholder text | content | `TODO`, `FIXME`, `stub`, `not implemented` |
| Placeholder paths | paths | `/path/to/`, `<your-`, `your-ontology-id` |
| Local-only paths | paths | `.codex/`, `.claude/`, `.trae/`, `~/.codex/` |
| Stale command names | commands | `class-context`, `property-context`, `ontology_import` (in readonly mode) |
| Stale MCP tool names | tools | Any tool name NOT in the current READONLY_TOOLS registry whitelist |
| Unsafe verdict policy | safety | "treat unknown as support", "out_of_scope is fine", "contradicted can be ignored" |

### MCP Tool Name Whitelist Check

When skill validation scans MCP tool names in skill text, references, or examples:
1. Each tool name MUST be checked against the current MCP registry whitelist (READONLY_TOOLS in `McpToolRegistry.java`).
2. Readonly skill examples MUST use only tools present in the current readonly whitelist.
3. Stale or future write-tool names such as `ontology_import` MUST fail unless explicitly marked as future/deferred and NOT shown as callable in v0.5 readonly workflows.

## Skill Examples

1. Skill examples MUST reference public checked-in fixtures or clearly mark them as optional/manual.
2. Required skill examples MUST NOT depend on private corpus directories.
3. Runnable examples MUST live under `agent-skills/<skill-id>/examples/`.
4. Ontology IDs MUST use real documented fixture IDs, not placeholders.

## Safety Policy Enforcement

Skills MUST NOT instruct agents to:

1. Fabricate claims, evidence, IRIs, or citations.
2. Treat `unknown` or `out_of_scope` as support.
3. Ignore contradicted claims.
4. Cite evidence that was NOT returned by owl4agents.

Any skill violating these rules MUST block release.