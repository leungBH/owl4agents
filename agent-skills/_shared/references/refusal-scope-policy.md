# Refusal and Scope Limitation Policy

Shared policy for when and how agents should refuse to verify claims or state scope limitations.

## When to Refuse

Agents must refuse to verify claims when:

1. **Invalid input.** The claim batch fails validation (missing fields, malformed structure, empty claims). The aggregate status will be `invalid_input`.
2. **Unsupported claim type.** The claim type is not one of the supported types listed in [claim-batch-schema.md](claim-batch-schema.md).
3. **Unsupported policy value.** The review policy is not `strict`, `conservative`, or `report-only`.

When refusing, agents should:
- State the reason clearly (e.g., "The claim batch is invalid because claims must have an `id` field.")
- Do not attempt to guess or fix the input.
- Suggest the user provide properly structured claims per the [claim-batch-schema.md](claim-batch-schema.md).

## When to State Scope Limitations

Agents must state scope limitations when:

1. **Out-of-scope entities.** A claim references entities that do not exist in the ontology. The aggregate status will be `out_of_scope` or `partially_verified`.
2. **Unknown verdict.** A claim receives `UNKNOWN` because evidence is insufficient. The aggregate status may be `insufficient_evidence` or `insufficient_evidence` with `unknownReason` providing details.
3. **Ambiguous entities.** An entity reference matches multiple ontology entities.

When stating limitations, agents should:
- Name the specific entities that are out of scope or ambiguous.
- State that verification is limited to the ontology's domain.
- Do not claim the ontology "proves" or "disproves" anything about out-of-scope entities.

## Scope Checking Workflow

Before verifying claims, agents should:

1. Use `ontology_search_entities` to confirm that entity IRIs exist in the ontology.
2. Use `ontology_detect_missing_entities` to identify matched, ambiguous, missing, and out-of-scope entities in a claim.
3. Use `ontology_get_scope` to understand the ontology's domain coverage and known gaps.

See the [owl4agents-ontology-scope-check skill](../../owl4agents-ontology-scope-check/SKILL.md) for the full SOP.

## Policy Interaction

The review policy interacts with scope limitations:

- **strict**: Out-of-scope required claims → the answer cannot be fully verified. State limitations explicitly.
- **conservative**: Only cite in-scope, verified claims. Mark out-of-scope claims as "unverified."
- **report-only**: Report the factual findings. The agent or user decides how to handle out-of-scope results.