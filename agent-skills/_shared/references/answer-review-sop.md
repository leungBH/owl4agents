# Answer Review SOP Reference

Standard operating procedure reference for answer review using v0.5 workflow.

This document is referenced by all agent skills that need to review answers against ontology evidence.

## Overview

The answer review workflow combines claim verification, evidence context generation, and policy-dependent handling guidance in a single call. It is the recommended workflow for LLM agents that need to verify an answer and produce a grounded response.

## Workflow Steps

### 1. Prepare Claims

Decompose the answer into structured claims following the [claim-batch-schema.md](claim-batch-schema.md). Each claim must have:
- `id`: unique identifier
- `type`: one of the supported claim types
- `required`: boolean (defaults to `true`)
- `subject`, `predicate`, `object`: structured entity references

### 2. Choose Review Policy

- **strict** (default): Recommended for critical answers where accuracy is paramount. Contradicted answers are rejected.
- **conservative**: Recommended for informational answers where partial verification is acceptable. Only cite verified claims.
- **report-only**: Recommended for research or exploratory contexts where the user wants raw data.

### 3. Execute Review

Call `ontology_review_answer_claims` with:
- `ontology_id`: the ontology to verify against
- `claims`: the structured claim batch
- `max_context_tokens`: budget for evidence context (0 = no truncation)
- `policy`: review policy (`strict`, `conservative`, or `report-only`)

### 4. Interpret Results

The response contains:
- **report**: Full verification report with aggregate status and per-claim results
- **evidenceContext**: Budget-aware evidence context for agent prompts
- **policy**: The review policy used
- **handlingGuidance**: Policy-dependent instructions for how to present results

Follow the [verdict-policy.md](verdict-policy.md) to interpret verdicts and aggregate status.

### 5. Present Results

Follow the handling guidance returned in the response:
- Under **strict** policy: Do not present any claim as fact unless it is supported by ontology evidence.
- Under **conservative** policy: Only cite explicitly verified claims. State limitations for unknown/out-of-scope claims.
- Under **report-only** policy: Present the factual findings without judgment.

Always follow the [evidence-citation-policy.md](evidence-citation-policy.md) — cite only returned evidence, never fabricate.

## CLI Commands

The same workflow is available via CLI:

```bash
# Verify an answer
owl4agents verify-answer pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --json

# Build evidence context
owl4agents evidence-context pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --max-context-tokens 500 --json

# Review an answer (recommended single-call workflow)
owl4agents review-answer pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --policy strict --json
```

## MCP Tools

The same workflow is available via MCP tools:

- `ontology_verify_claims_batch`: Verify a batch of claims
- `ontology_build_evidence_context`: Build compact evidence context
- `ontology_review_answer_claims`: Review with policy guidance (recommended)