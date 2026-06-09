# MCP Workflow Prompts Contract

This contract defines v0.5 MCP workflow prompt templates, including mandatory file-level templates, optional protocol-level prompt listing, tool-name freshness, structured-claim guidance, and prompt safety policy.

## Required Prompt Templates

v0.5 MUST provide the following file-level prompt templates:

| Template ID | File | Description |
| --- | --- | --- |
| verify-answer-with-ontology | `prompts/verify-answer-with-ontology.md` | Guide agents to verify an answer using ontology workflow tools |
| ground-answer-with-evidence | `prompts/ground-answer-with-evidence.md` | Guide agents to write evidence-grounded answers from workflow reports |
| explain-unknown-ontology-claim | `prompts/explain-unknown-ontology-claim.md` | Guide agents to explain why a claim cannot be verified |

Each template MUST:

1. Reference current v0.5 workflow tools and structured claim requirements.
2. Be committed as a public file regardless of whether protocol-level MCP prompt support is implemented.

## Protocol-Level Prompt Listing (Optional)

1. If the project-local MCP adapter supports prompt listing cleanly, MCP clients MAY discover v0.5 workflow prompts through `prompts/list`.
2. Prompt listing MUST NOT break existing readonly tool listing.
3. Protocol-level validation MUST call MCP prompt listing and verify template names only when the adapter exposes that capability.
4. If protocol-level support is not available in v0.5, it MUST be explicitly deferred in README and CHANGELOG.
5. Workflow MCP tools MUST remain available regardless of prompt support status.

## Prompt Safety Requirements

### Structured Claim Handoff

1. Prompt templates MUST instruct agents to prepare structured claims before calling workflow tools.
2. Prompts MUST NOT imply that owl4agents extracts claims from prose.
3. Free-text-only submissions MUST be clearly described as rejected by owl4agents.

### Verdict Semantic Preservation

1. Prompt templates MUST preserve `supported`, `contradicted`, `unknown`, and `out_of_scope` verdicts without conversion.
2. Prompts MUST NOT instruct agents to convert `unknown` or `out_of_scope` claims into support.
3. The prompt policy MUST match the shared skill verdict policy at `agent-skills/_shared/references/verdict-policy.md`.

### Valid Tool References

1. Every referenced MCP tool name MUST exist in the v0.5 MCP registry.
2. Stale tool names MUST fail validation.
3. The required v0.5 workflow tool names are:
   - `ontology_verify_claims_batch`
   - `ontology_build_evidence_context`
   - `ontology_review_answer_claims`
4. References to low-level v0.1–v0.4 tools MAY be included for debugging or advanced use, but MUST use current registry names.

## Prompt Validation

### Template Lint

Validation MUST fail on:

| Check | Failure Condition |
| --- | --- |
| Placeholder text | Contains `TODO`, `FIXME`, `stub`, `not implemented` |
| Missing tool references | Does not reference any workflow tool |
| Missing input schema guidance | Does not explain structured claim format |
| Local-only paths | Contains `.codex/`, `.claude/`, `.trae/`, `~/.codex/` |
| Stale tool names | References tools not in current READONLY_TOOLS |

### Prompt and Skill Consistency

1. When prompt templates and skill packs both define verdict handling policy, their policy MUST use the same verdict terms: `supported`, `contradicted`, `unknown`, `out_of_scope`.
2. Prompts MUST NOT contradict the shared skill verdict policy.
3. Contradictions between prompts and skills MUST block release.

## PASS Verdict Invalid Without

A v0.5 release claiming valid prompt templates is invalid if:

- Templates instruct agents to treat unknown as support
- Templates reference stale MCP tool names not in the current registry
- Templates imply free-text claim extraction
- Protocol-level prompt listing blocks workflow tools