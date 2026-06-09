package org.owl4agents.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.owl4agents.core.GraphScope;
import org.owl4agents.core.model.*;

/**
 * Validates v0.5 claim batch inputs with deterministic field-level diagnostics.
 * Rejects empty claims arrays, missing id/type fields, and free-text-only submissions.
 * Defaults omitted required fields to true.
 *
 * This validator operates on pre-parsed Map objects. JSON string parsing is
 * handled by the CLI and MCP layers (which have Gson available).
 */
public class ClaimBatchValidator {

    /**
     * Result of validating a claim batch input.
     * Either a successfully validated ClaimBatchInput, or a structured error with
     * field-level diagnostics identifying every invalid field.
     */
    public sealed interface BatchValidationResult
        permits BatchValidationResult.Success, BatchValidationResult.Error {

        boolean isSuccess();
        ClaimBatchInput batch();
        AggregateAnswerStatus aggregateStatus();
        List<FieldDiagnostic> diagnostics();

        record Success(ClaimBatchInput batch) implements BatchValidationResult {
            @Override public boolean isSuccess() { return true; }
            @Override public ClaimBatchInput batch() { return batch; }
            @Override public AggregateAnswerStatus aggregateStatus() { return null; }
            @Override public List<FieldDiagnostic> diagnostics() { return List.of(); }
        }

        record Error(AggregateAnswerStatus aggregateStatus, List<FieldDiagnostic> diagnostics)
            implements BatchValidationResult {
            @Override public boolean isSuccess() { return false; }
            @Override public ClaimBatchInput batch() { return null; }
            @Override public AggregateAnswerStatus aggregateStatus() { return aggregateStatus; }
            @Override public List<FieldDiagnostic> diagnostics() { return diagnostics; }
        }
    }

    /**
     * A single field-level diagnostic identifying an invalid field and its reason.
     */
    public record FieldDiagnostic(String field, String reason) {}

    /**
     * Validates a pre-parsed Map as a claim batch input.
     * Used by both CLI (after Gson parsing) and MCP (which receives arguments as Map objects).
     * Returns deterministic field-level diagnostics for every invalid field.
     */
    public BatchValidationResult validateMap(Map<String, Object> map) {
        List<FieldDiagnostic> diagnostics = new ArrayList<>();

        // 1. answerId is required
        String answerId = (String) map.get("answerId");
        if (answerId == null || answerId.isBlank()) {
            diagnostics.add(new FieldDiagnostic("answerId", "answerId is required and must not be blank."));
        }

        // 2. claims must be present and non-empty
        Object claimsObj = map.get("claims");
        if (claimsObj == null) {
            diagnostics.add(new FieldDiagnostic("claims", "claims is required."));
            // Free-text-only check: if answerText exists without claims, that's invalid_input
            if (map.containsKey("answerText")) {
                diagnostics.add(new FieldDiagnostic("claims",
                    "Free-text-only submissions are not supported. Structured claims are required."));
            }
        } else if (claimsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> claimsList = (List<Object>) claimsObj;
            if (claimsList.isEmpty()) {
                diagnostics.add(new FieldDiagnostic("claims", "claims must be a non-empty array."));
            } else {
                // Validate each claim
                for (int i = 0; i < claimsList.size(); i++) {
                    Object claimObj = claimsList.get(i);
                    if (claimObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> claimMap = (Map<String, Object>) claimObj;
                        validateClaimFields(claimMap, i, diagnostics);
                    } else {
                        diagnostics.add(new FieldDiagnostic("claims[" + i + "]",
                            "Each claim must be a JSON object, got: "
                            + (claimObj == null ? "null" : claimObj.getClass().getSimpleName())));
                    }
                }
            }
        } else {
            diagnostics.add(new FieldDiagnostic("claims",
                "claims must be an array, got: " + (claimsObj == null ? "null" : claimsObj.getClass().getSimpleName())));
        }

        // 3. If diagnostics found, return invalid_input
        if (!diagnostics.isEmpty()) {
            return new BatchValidationResult.Error(AggregateAnswerStatus.INVALID_INPUT, diagnostics);
        }

        // 4. Build the ClaimBatchInput from validated fields
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claimsList = (List<Map<String, Object>>) claimsObj;

        List<ClaimBatchInput.BatchClaim> batchClaims = new ArrayList<>();
        for (Map<String, Object> claimMap : claimsList) {
            batchClaims.add(buildBatchClaim(claimMap));
        }

        Optional<WorkflowOptions> workflowOpts = Optional.empty();
        Object optsObj = map.get("options");
        if (optsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> optsMap = (Map<String, Object>) optsObj;
            workflowOpts = Optional.of(buildWorkflowOptions(optsMap));
        }

        ClaimBatchInput batch = new ClaimBatchInput(
            answerId,
            Optional.ofNullable((String) map.get("question")),
            Optional.ofNullable((String) map.get("answerText")),
            batchClaims,
            workflowOpts
        );

        return new BatchValidationResult.Success(batch);
    }

    private void validateClaimFields(Map<String, Object> claimMap, int index, List<FieldDiagnostic> diagnostics) {
        String fieldPrefix = "claims[" + index + "]";

        // id is required
        String id = (String) claimMap.get("id");
        if (id == null || id.isBlank()) {
            diagnostics.add(new FieldDiagnostic(fieldPrefix + ".id",
                "id is required and must not be blank."));
        }

        // type is required
        String typeStr = (String) claimMap.get("type");
        if (typeStr == null || typeStr.isBlank()) {
            diagnostics.add(new FieldDiagnostic(fieldPrefix + ".type",
                "type is required and must not be blank."));
        } else {
            // Check if type is a supported claim type
            ClaimType type = parseClaimType(typeStr);
            if (type == null) {
                diagnostics.add(new FieldDiagnostic(fieldPrefix + ".type",
                    "Claim type '" + typeStr + "' is not supported. Supported types: " + supportedTypeNames()));
            }
        }

        // subject entity validation (if present)
        Object subjectObj = claimMap.get("subject");
        if (subjectObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> subjectMap = (Map<String, Object>) subjectObj;
            validateEntity(subjectMap, fieldPrefix + ".subject", diagnostics);
        }

        // object entity validation (if present)
        Object objectObj = claimMap.get("object");
        if (objectObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> objectMap = (Map<String, Object>) objectObj;
            validateEntity(objectMap, fieldPrefix + ".object", diagnostics);
        }
    }

    private void validateEntity(Map<String, Object> entityMap, String fieldPrefix, List<FieldDiagnostic> diagnostics) {
        String kind = (String) entityMap.get("kind");
        if (kind == null || kind.isBlank()) {
            diagnostics.add(new FieldDiagnostic(fieldPrefix + ".kind", "kind is required."));
        }

        String iri = (String) entityMap.get("iri");
        if (iri == null || iri.isBlank()) {
            diagnostics.add(new FieldDiagnostic(fieldPrefix + ".iri", "iri is required."));
        }
    }

    private ClaimBatchInput.BatchClaim buildBatchClaim(Map<String, Object> claimMap) {
        String id = (String) claimMap.get("id");
        String typeStr = (String) claimMap.get("type");
        ClaimType type = parseClaimType(typeStr);

        // required defaults to true when omitted
        boolean required = true;
        Object requiredObj = claimMap.get("required");
        if (requiredObj instanceof Boolean) {
            required = (Boolean) requiredObj;
        }

        Optional<ClaimEntity> subject = Optional.ofNullable(parseEntity(claimMap.get("subject")));
        Optional<String> predicate = Optional.ofNullable((String) claimMap.get("predicate"));
        Optional<ClaimEntity> object = Optional.ofNullable(parseEntity(claimMap.get("object")));

        Optional<String> reasoner = Optional.empty();
        String reasonerStr = (String) claimMap.get("reasoner");
        if (reasonerStr != null && !reasonerStr.isBlank()) {
            reasoner = Optional.of(reasonerStr);
        }

        Optional<GraphScope> graphScope = Optional.empty();
        String scopeStr = (String) claimMap.get("graphScope");
        if (scopeStr != null && !scopeStr.isBlank()) {
            try {
                graphScope = Optional.of(GraphScope.valueOf(scopeStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Invalid scope — ignored here, ClaimValidator catches it downstream
            }
        }

        Optional<Map<String, Object>> claimOpts = Optional.empty();
        Object optsObj = claimMap.get("options");
        if (optsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> optsMap = (Map<String, Object>) optsObj;
            claimOpts = Optional.of(optsMap);
        }

        return new ClaimBatchInput.BatchClaim(id, type, required, subject, predicate, object,
            reasoner, graphScope, claimOpts);
    }

    private WorkflowOptions buildWorkflowOptions(Map<String, Object> optsMap) {
        Optional<String> reasoner = Optional.empty();
        String reasonerStr = (String) optsMap.get("reasoner");
        if (reasonerStr != null && !reasonerStr.isBlank()) {
            reasoner = Optional.of(reasonerStr);
        }

        Optional<Boolean> requireReasoning = Optional.empty();
        Object requireObj = optsMap.get("requireReasoning");
        if (requireObj instanceof Boolean) {
            requireReasoning = Optional.of((Boolean) requireObj);
        }

        Optional<Integer> maxEvidencePerClaim = Optional.empty();
        Object maxEvObj = optsMap.get("maxEvidencePerClaim");
        if (maxEvObj instanceof Number) {
            maxEvidencePerClaim = Optional.of(((Number) maxEvObj).intValue());
        }

        Optional<Integer> maxContextTokens = Optional.empty();
        Object maxCtxObj = optsMap.get("maxContextTokens");
        if (maxCtxObj instanceof Number) {
            maxContextTokens = Optional.of(((Number) maxCtxObj).intValue());
        }

        return new WorkflowOptions(reasoner, requireReasoning, maxEvidencePerClaim, maxContextTokens);
    }

    private static ClaimType parseClaimType(String typeStr) {
        if (typeStr == null) return null;
        for (ClaimType ct : ClaimType.values()) {
            if (ct.jsonName().equals(typeStr)) return ct;
        }
        return null;
    }

    private static String supportedTypeNames() {
        StringBuilder sb = new StringBuilder();
        ClaimType[] values = ClaimType.values();
        for (int i = 0; i < values.length; i++) {
            sb.append(values[i].jsonName());
            if (i < values.length - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private static ClaimEntity parseEntity(Object entityObj) {
        if (entityObj == null) return null;
        if (entityObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> entityMap = (Map<String, String>) entityObj;
            String kind = entityMap.get("kind");
            String iri = entityMap.get("iri");
            if (kind != null && iri != null) {
                return new ClaimEntity(kind, iri);
            }
        }
        return null;
    }
}