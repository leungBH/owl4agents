package org.owl4agents.cli;

import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.GraphScope;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;

/**
 * Shared claim parsing utility for v0.3 CLI commands.
 * Gson cannot deserialize Java records with Optional fields in Java 16+,
 * so we parse JSON into a Map and construct Claim records manually.
 *
 * Returns a ParseResult that differentiates between INVALID_CLAIM_SCHEMA
 * (malformed JSON, missing fields) and UNSUPPORTED_CLAIM_TYPE
 * (valid JSON but type is not one of the 14 supported v0.3 claim types).
 */
final class ClaimParser {

    private static final Gson gson = new Gson();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    /**
     * Result of parsing a claim JSON input.
     * Either a successfully parsed Claim, or a specific error with code and message.
     */
    sealed interface ParseResult permits ParseResult.Success, ParseResult.Error {
        boolean isSuccess();
        Claim claim();
        ErrorCode errorCode();
        String errorMessage();

        record Success(Claim claim) implements ParseResult {
            @Override public boolean isSuccess() { return true; }
            @Override public Claim claim() { return claim; }
            @Override public ErrorCode errorCode() { return null; }
            @Override public String errorMessage() { return null; }
        }

        record Error(ErrorCode errorCode, String errorMessage) implements ParseResult {
            @Override public boolean isSuccess() { return false; }
            @Override public Claim claim() { return null; }
            @Override public ErrorCode errorCode() { return errorCode; }
            @Override public String errorMessage() { return errorMessage; }
        }
    }

    static ParseResult parseClaim(String claimInput) {
        return parseClaim(claimInput, null);
    }

    static ParseResult parseClaim(String claimInput, String reasonerOverride) {
        try {
            String json = claimInput;
            // If it looks like a file path, try to read it
            if (!claimInput.trim().startsWith("{")) {
                try {
                    json = java.nio.file.Files.readString(java.nio.file.Path.of(claimInput));
                } catch (Exception e) {
                    return new ParseResult.Error(ErrorCode.INVALID_CLAIM_SCHEMA,
                        "Failed to read claim file: " + claimInput);
                }
            }

            // Parse JSON into a Map to avoid Gson record deserialization issues
            Map<String, Object> map = gson.fromJson(json, MAP_TYPE);
            if (map == null) {
                return new ParseResult.Error(ErrorCode.INVALID_CLAIM_SCHEMA,
                    "Failed to parse claim JSON — input produced null map.");
            }

            // Extract fields
            String claimId = (String) map.get("claimId");
            String typeStr = (String) map.get("type");
            String ontologyId = (String) map.get("ontologyId");
            String predicate = (String) map.get("predicate");

            // Check for unsupported claim type BEFORE constructing Claim
            ClaimType type = parseClaimType(typeStr);
            if (type == null && typeStr != null) {
                // type field was provided but is not a supported v0.3 claim type
                return new ParseResult.Error(ErrorCode.UNSUPPORTED_CLAIM_TYPE,
                    "Claim type '" + typeStr + "' is not supported. Supported types: "
                    + supportedTypeNames());
            }

            // If type field is entirely missing, let ClaimValidator catch it as INVALID_CLAIM_SCHEMA
            ClaimEntity subject = parseEntity(map.get("subject"));
            ClaimEntity object = parseEntity(map.get("object"));

            // Parse optional fields
            Optional<String> reasoner = Optional.empty();
            String reasonerStr = (String) map.get("reasoner");
            if (reasonerOverride != null && !"auto".equals(reasonerOverride)) {
                reasoner = Optional.of(reasonerOverride);
            } else if (reasonerStr != null && !reasonerStr.isBlank()) {
                reasoner = Optional.of(reasonerStr);
            }

            Optional<GraphScope> graphScope = Optional.empty();
            String scopeStr = (String) map.get("graphScope");
            if (scopeStr != null && !scopeStr.isBlank()) {
                try {
                    graphScope = Optional.of(GraphScope.valueOf(scopeStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid scope — validator will catch it
                }
            }

            Optional<Map<String, Object>> options = Optional.empty();
            Object optsObj = map.get("options");
            if (optsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> optsMap = (Map<String, Object>) optsObj;
                options = Optional.of(optsMap);
            }

            Claim claim = new Claim(claimId, type, ontologyId, subject, predicate, object,
                reasoner, graphScope, options);

            return new ParseResult.Success(claim);
        } catch (Exception e) {
            return new ParseResult.Error(ErrorCode.INVALID_CLAIM_SCHEMA,
                "Failed to parse claim JSON: " + e.getMessage());
        }
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

    private static ClaimType parseClaimType(String typeStr) {
        if (typeStr == null) return null;
        for (ClaimType ct : ClaimType.values()) {
            if (ct.jsonName().equals(typeStr)) return ct;
        }
        return null;
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