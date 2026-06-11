# Evidence Citation and Anti-Fabrication Policy

Shared policy for how agents cite evidence and avoid fabrication.

This policy applies to all owl4agents agent skills. Every skill links here instead of duplicating fabrication rules.

## Anti-Fabrication Rules

1. **Do not fabricate claims.** Only submit claims derived from content that can be traced to an ontology entity.
2. **Do not fabricate evidence.** Only cite evidence items returned by the verification workflow. Never invent evidence entries.
3. **Do not fabricate IRIs.** Only use entity IRIs that exist in the ontology. Use `ontology_search_entities` or `ontology_detect_missing_entities` to confirm entity existence before referencing them in claims.
4. **Do not fabricate citations.** Only cite evidence that appears in the verification report or evidence context. If evidence was truncated or omitted, do not reference it as if it were available.

## Evidence Citation Rules

1. **Cite only returned evidence.** When presenting a verified claim, cite the evidence items returned by `ontology_verify_claim`, `ontology_verify_claims_batch`, or `ontology_build_evidence_context`.
2. **Report omitted evidence.** If evidence was truncated (the `omittedEvidenceCount` is greater than 0), state that additional evidence exists but was omitted due to budget constraints.
3. **Report missing evidence.** If a claim received `UNKNOWN` verdict, state that evidence is insufficient. Do not substitute external knowledge for ontology evidence.
4. **Preserve verdict semantics.** An `ENTAILED` verdict means the claim is fully supported. A `NOT_ENTAILED` verdict means the claim is contradicted. An `UNKNOWN` verdict means evidence is insufficient — not that the claim is "probably true" or "might be true."

## Evidence Entry Schema (v0.5 workflow)

Each workflow evidence entry has:

| Field | Type | Description |
|-------|------|-------------|
| `kind` | string | Evidence kind (e.g., "inferred_subclass", "asserted_axiom") |
| `summary` | string | Human-readable summary of the evidence |
| `source` | string | Source of the evidence (e.g., "hermit_reasoner", "explicit_axiom") |
| `reasoner` | string | Reasoner name, if applicable |
| `provenance` | string | Provenance metadata, if applicable |

This is distinct from the v0.3 single-claim evidence schema which uses `evidenceId`, `role`, `kind`, `value`, `source`, `graphScope`, `entities`, and `confidence`.

## Evidence Context Citation

When using `ontology_build_evidence_context` or `ontology_review_answer_claims`, the evidence context includes mandatory agent instructions:

- "Cite only evidence returned in this context."
- Additional status-dependent instructions based on aggregate status.

Agents must follow these instructions and not supplement the returned evidence with external knowledge or fabricated entries.