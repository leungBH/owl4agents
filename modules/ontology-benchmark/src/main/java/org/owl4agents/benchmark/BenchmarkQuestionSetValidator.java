package org.owl4agents.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.Verdict;
import org.owl4agents.core.util.GsonFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/**
 * Validates benchmark question set JSONL files.
 * Two-tier validation:
 * - Tier 1 (structural): missing questionId/expectedVerdict/claims or missing
 *   subject.iri/object.iri/type → INVALID_QUESTION_SET/INVALID_CLAIM_SCHEMA,
 *   execution blocked
 * - Tier 2 (semantic): valid structure but unknown IRI → reviewStatus: pending,
 *   execution allowed but excluded from primary metrics
 * - NL-only rejection: empty/null claims → INVALID_QUESTION_SET, execution skipped,
 *   subsequent lines continue
 */
public class BenchmarkQuestionSetValidator {

    private final Gson gson = GsonFactory.createGson();

    /** Validation error for a question line. */
    public record ValidationError(String questionId, String code, String diagnostic) {}

    /** Validation result for a question line. */
    public record LineValidationResult(
        BenchmarkQuestion question,
        List<ValidationError> errors,
        String reviewStatus
    ) {
        public boolean isBlocked() {
            return errors.stream().anyMatch(e ->
                e.code().equals("INVALID_QUESTION_SET") || e.code().equals("INVALID_CLAIM_SCHEMA"));
        }
    }

    /**
     * Validate a single JSONL line from a question set.
     * Returns LineValidationResult with parsed question (if valid),
     * any validation errors, and effective reviewStatus.
     */
    public LineValidationResult validateLine(String jsonlLine) {
        if (jsonlLine == null || jsonlLine.isBlank()) {
            return new LineValidationResult(null, List.of(
                new ValidationError(null, "INVALID_QUESTION_SET", "Empty line")
            ), null);
        }

        // Parse JSON
        JsonObject obj;
        try {
            JsonElement elem = gson.fromJson(jsonlLine, JsonElement.class);
            if (elem == null || !elem.isJsonObject()) {
                return new LineValidationResult(null, List.of(
                    new ValidationError(null, "INVALID_QUESTION_SET", "Line is not a valid JSON object")
                ), null);
            }
            obj = elem.getAsJsonObject();
        } catch (Exception e) {
            return new LineValidationResult(null, List.of(
                new ValidationError(null, "INVALID_QUESTION_SET", "JSON parse error: " + e.getMessage())
            ), null);
        }

        List<ValidationError> errors = new ArrayList<>();
        String questionId = getAsString(obj, "questionId");

        // Tier 1: check required top-level fields
        if (questionId == null || questionId.isBlank()) {
            errors.add(new ValidationError(null, "INVALID_QUESTION_SET",
                "Missing required field: questionId"));
        }

        String expectedVerdictStr = getAsString(obj, "expectedVerdict");
        if (expectedVerdictStr == null || expectedVerdictStr.isBlank()) {
            errors.add(new ValidationError(questionId, "INVALID_QUESTION_SET",
                "Missing required field: expectedVerdict"));
        } else {
            // Validate verdict value
            try {
                Verdict.valueOf(expectedVerdictStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                errors.add(new ValidationError(questionId, "INVALID_QUESTION_SET",
                    "Invalid expectedVerdict: " + expectedVerdictStr));
            }
        }

        // Check claims array
        JsonElement claimsElem = obj.get("claims");
        if (claimsElem == null || !claimsElem.isJsonArray()) {
            errors.add(new ValidationError(questionId, "INVALID_QUESTION_SET",
                "Missing or invalid claims array"));
        } else {
            JsonArray claimsArr = claimsElem.getAsJsonArray();
            if (claimsArr.isEmpty()) {
                // NL-only rejection: empty claims → INVALID_QUESTION_SET
                errors.add(new ValidationError(questionId, "INVALID_QUESTION_SET",
                    "Empty claims array — NL-only questions are not supported for verification"));
            } else {
                // Tier 1: validate each claim's structural schema
                for (int i = 0; i < claimsArr.size(); i++) {
                    JsonObject claimObj = claimsArr.get(i).getAsJsonObject();
                    String claimId = getAsString(claimObj, "id");

                    // Required claim fields: type, subject.iri, object.iri
                    String typeStr = getAsString(claimObj, "type");
                    if (typeStr == null || typeStr.isBlank()) {
                        errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                            "Claim " + i + " missing required field: type"));
                    } else {
                        try {
                            ClaimType.valueOf(typeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                                "Claim " + i + " invalid type: " + typeStr));
                        }
                    }

                    // Validate subject
                    JsonObject subjectObj = claimObj.getAsJsonObject("subject");
                    if (subjectObj == null) {
                        errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                            "Claim " + i + " missing subject"));
                    } else {
                        String subjectIri = getAsString(subjectObj, "iri");
                        if (subjectIri == null || subjectIri.isBlank()) {
                            errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                                "Claim " + i + " missing subject.iri"));
                        }
                        String subjectKind = getAsString(subjectObj, "kind");
                        if (subjectKind == null || subjectKind.isBlank()) {
                            errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                                "Claim " + i + " missing subject.kind"));
                        }
                    }

                    // Validate object
                    JsonObject objectObj = claimObj.getAsJsonObject("object");
                    if (objectObj == null) {
                        errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                            "Claim " + i + " missing object"));
                    } else {
                        String objectIri = getAsString(objectObj, "iri");
                        if (objectIri == null || objectIri.isBlank()) {
                            errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                                "Claim " + i + " missing object.iri"));
                        }
                        String objectKind = getAsString(objectObj, "kind");
                        if (objectKind == null || objectKind.isBlank()) {
                            errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                                "Claim " + i + " missing object.kind"));
                        }
                    }

                    // Validate predicate
                    String predicate = getAsString(claimObj, "predicate");
                    if (predicate == null || predicate.isBlank()) {
                        errors.add(new ValidationError(questionId, "INVALID_CLAIM_SCHEMA",
                            "Claim " + i + " missing predicate"));
                    }
                }
            }
        }

        // Determine effective reviewStatus
        String effectiveReviewStatus = getAsString(obj, "reviewStatus");
        if (effectiveReviewStatus == null) {
            // Tier 2: if structurally valid but has potential semantic issues
            // (handled by the runner at execution time), default to approved
            effectiveReviewStatus = errors.isEmpty() ? "approved" : null;
        }

        // If blocked, don't construct question
        if (errors.stream().anyMatch(e ->
            e.code().equals("INVALID_QUESTION_SET") || e.code().equals("INVALID_CLAIM_SCHEMA"))) {
            return new LineValidationResult(null, errors, effectiveReviewStatus);
        }

        // Construct BenchmarkQuestion from parsed JSON
        BenchmarkQuestion question = parseQuestion(obj, expectedVerdictStr);
        return new LineValidationResult(question, errors, effectiveReviewStatus);
    }

    private BenchmarkQuestion parseQuestion(JsonObject obj, String expectedVerdictStr) {
        String questionId = getAsString(obj, "questionId");
        String source = getAsString(obj, "source");
        String sourceYear = getAsString(obj, "sourceYear");
        String domain = getAsString(obj, "domain");
        List<String> ontologyIds = parseStringArray(obj.getAsJsonArray("ontologyIds"));
        String question = getAsString(obj, "question");
        String answerType = getAsString(obj, "answerType");
        String expectedAnswer = getAsString(obj, "expectedAnswer");
        Verdict expectedVerdict = Verdict.valueOf(expectedVerdictStr.toUpperCase());
        String reviewStatus = getAsString(obj, "reviewStatus");
        boolean edgeCase = obj.has("edgeCase") && obj.get("edgeCase").isJsonPrimitive()
            ? obj.get("edgeCase").getAsBoolean() : false;

        // Parse claims
        JsonArray claimsArr = obj.getAsJsonArray("claims");
        List<BenchmarkQuestion.DecomposedClaim> claims = new ArrayList<>();
        for (JsonElement ce : claimsArr) {
            JsonObject co = ce.getAsJsonObject();
            ClaimType type = ClaimType.valueOf(getAsString(co, "type").toUpperCase());
            boolean required = co.has("required") && co.get("required").isJsonPrimitive()
                ? co.get("required").getAsBoolean() : true;
            JsonObject subObj = co.getAsJsonObject("subject");
            JsonObject objObj = co.getAsJsonObject("object");
            ClaimEntity subject = new ClaimEntity(
                getAsString(subObj, "kind"),
                getAsString(subObj, "iri"));
            ClaimEntity object = new ClaimEntity(
                getAsString(objObj, "kind"),
                getAsString(objObj, "iri"));
            String predicate = getAsString(co, "predicate");
            claims.add(new BenchmarkQuestion.DecomposedClaim(
                getAsString(co, "id"), type, required, subject, predicate, object));
        }

        // Parse options
        Optional<BenchmarkQuestion.QuestionOptions> options = Optional.empty();
        if (obj.has("options") && obj.get("options").isJsonObject()) {
            JsonObject optsObj = obj.getAsJsonObject("options");
            String reasoner = getAsString(optsObj, "reasoner");
            boolean requireReasoning = optsObj.has("requireReasoning")
                && optsObj.get("requireReasoning").isJsonPrimitive()
                ? optsObj.get("requireReasoning").getAsBoolean() : true;
            options = Optional.of(new BenchmarkQuestion.QuestionOptions(reasoner, requireReasoning));
        }

        return new BenchmarkQuestion(
            questionId, source, sourceYear, domain, ontologyIds, question,
            answerType, expectedAnswer, expectedVerdict, claims, reviewStatus,
            edgeCase, options);
    }

    private String getAsString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement elem = obj.get(key);
        if (elem == null || !elem.isJsonPrimitive()) return null;
        return elem.getAsString();
    }

    private List<String> parseStringArray(JsonArray arr) {
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement elem : arr) {
            if (elem.isJsonPrimitive()) result.add(elem.getAsString());
        }
        return result;
    }
}