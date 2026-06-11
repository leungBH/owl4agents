package org.owl4agents.benchmark;

import java.util.ArrayList;
import java.util.List;

import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/**
 * Validates NL-to-claim decomposition output for schema conformance
 * WITHOUT executing verification. This is a pre-benchmark submission
 * validation helper for agent-side claim decomposition.
 */
public class NlClaimValidationHelper {

    private final Gson gson = GsonFactory.createGson();

    /** Validation error for a decomposed claim. */
    public record ClaimValidationError(int claimIndex, String code, String diagnostic) {}

    /** Validation result. */
    public record ValidationResult(List<ClaimValidationError> errors, boolean valid) {
        public boolean isValid() { return valid; }
    }

    /**
     * Validate a list of decomposed claims for schema conformance.
     * Does NOT execute verification — only checks structural validity.
     */
    public ValidationResult validateClaims(String claimsJson) {
        if (claimsJson == null || claimsJson.isBlank()) {
            return new ValidationResult(List.of(
                new ClaimValidationError(0, "INVALID_CLAIM_SCHEMA", "Empty claims input")
            ), false);
        }

        JsonArray claimsArr;
        try {
            JsonObject wrapper = gson.fromJson(claimsJson, JsonObject.class);
            if (wrapper != null && wrapper.has("claims")) {
                claimsArr = wrapper.getAsJsonArray("claims");
            } else {
                // Try parsing as a direct array
                claimsArr = gson.fromJson(claimsJson, JsonArray.class);
            }
        } catch (Exception e) {
            // Try as direct array
            try {
                claimsArr = gson.fromJson(claimsJson, JsonArray.class);
            } catch (Exception e2) {
                return new ValidationResult(List.of(
                    new ClaimValidationError(0, "INVALID_CLAIM_SCHEMA", "Invalid JSON: " + e2.getMessage())
                ), false);
            }
        }

        if (claimsArr == null || claimsArr.isEmpty()) {
            return new ValidationResult(List.of(
                new ClaimValidationError(0, "INVALID_CLAIM_SCHEMA", "Empty claims array — NL-only without claims is rejected")
            ), false);
        }

        List<ClaimValidationError> errors = new ArrayList<>();

        for (int i = 0; i < claimsArr.size(); i++) {
            JsonObject claimObj = claimsArr.get(i).getAsJsonObject();

            // Check type
            String typeStr = getAsString(claimObj, "type");
            if (typeStr == null || typeStr.isBlank()) {
                errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                    "Claim missing required field: type"));
            } else {
                try {
                    ClaimType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                        "Invalid claim type: " + typeStr));
                }
            }

            // Check subject
            JsonObject subjectObj = claimObj.getAsJsonObject("subject");
            if (subjectObj == null) {
                errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                    "Claim missing subject"));
            } else {
                String iri = getAsString(subjectObj, "iri");
                if (iri == null || iri.isBlank()) {
                    errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                        "Claim missing subject.iri"));
                }
                String kind = getAsString(subjectObj, "kind");
                if (kind == null || kind.isBlank()) {
                    errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                        "Claim missing subject.kind"));
                }
            }

            // Check object
            JsonObject objectObj = claimObj.getAsJsonObject("object");
            if (objectObj == null) {
                errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                    "Claim missing object"));
            } else {
                String iri = getAsString(objectObj, "iri");
                if (iri == null || iri.isBlank()) {
                    errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                        "Claim missing object.iri"));
                }
                String kind = getAsString(objectObj, "kind");
                if (kind == null || kind.isBlank()) {
                    errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                        "Claim missing object.kind"));
                }
            }

            // Check predicate
            String predicate = getAsString(claimObj, "predicate");
            if (predicate == null || predicate.isBlank()) {
                errors.add(new ClaimValidationError(i, "INVALID_CLAIM_SCHEMA",
                    "Claim missing predicate"));
            }
        }

        return new ValidationResult(errors, errors.isEmpty());
    }

    private String getAsString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        var elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) return null;
        return elem.getAsString();
    }
}