package org.owl4agents.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.Arrays;

import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.GraphScope;

/**
 * Validates structured claims before they enter the verification pipeline.
 * Rejects free-text-only claims and malformed structured claims with
 * INVALID_CLAIM_SCHEMA or UNSUPPORTED_CLAIM_TYPE error codes.
 */
public class ClaimValidator {

    /** Valid entity kinds within a claim. */
    private static final List<String> VALID_KINDS = List.of(
        "class", "object_property", "data_property", "annotation_property",
        "individual", "datatype"
    );

    /** Entity-kind requirements per claim type. */
    private static final Map<ClaimType, EntityKindRequirement> KIND_REQUIREMENTS = buildKindRequirements();

    private record EntityKindRequirement(String subjectKind, String objectKind, boolean subjectRequired, boolean objectRequired) {}

    private static Map<ClaimType, EntityKindRequirement> buildKindRequirements() {
        EnumMap<ClaimType, EntityKindRequirement> map = new EnumMap<>(ClaimType.class);
        // Class-class claims
        map.put(ClaimType.SUBCLASS,              new EntityKindRequirement("class", "class", true, true));
        map.put(ClaimType.EQUIVALENT_CLASSES,    new EntityKindRequirement("class", "class", true, true));
        map.put(ClaimType.DISJOINT_CLASSES,      new EntityKindRequirement("class", "class", true, true));
        map.put(ClaimType.CLASS_COMPATIBILITY,   new EntityKindRequirement("class", "class", true, true));
        // Individual-class claims
        map.put(ClaimType.INDIVIDUAL_MEMBERSHIP, new EntityKindRequirement("individual", "class", true, true));
        // Individual-individual claims
        map.put(ClaimType.OBJECT_PROPERTY_ASSERTION, new EntityKindRequirement("individual", "individual", true, true));
        // Individual-literal claims
        map.put(ClaimType.DATA_PROPERTY_ASSERTION,   new EntityKindRequirement("individual", "literal", true, true));
        // Property-class claims
        map.put(ClaimType.OBJECT_PROPERTY_DOMAIN, new EntityKindRequirement("object_property", "class", true, true));
        map.put(ClaimType.OBJECT_PROPERTY_RANGE,  new EntityKindRequirement("object_property", "class", true, true));
        map.put(ClaimType.DATA_PROPERTY_DOMAIN,   new EntityKindRequirement("data_property", "class", true, true));
        // Property-datatype claims
        map.put(ClaimType.DATA_PROPERTY_RANGE,    new EntityKindRequirement("data_property", "datatype", true, true));
        // Datatype-literal claims
        map.put(ClaimType.LITERAL_VALIDITY,       new EntityKindRequirement("datatype", "literal", true, true));
        // Ontology-level claims — no entity requirements
        map.put(ClaimType.ONTOLOGY_CONSISTENCY,   new EntityKindRequirement(null, null, false, false));
        map.put(ClaimType.ONTOLOGY_SCOPE,         new EntityKindRequirement(null, null, false, false));
        return map;
    }

    /**
     * Validates a claim for required fields, entity kinds, graph scope, and evidence options.
     * Returns the validated claim on success, or a structured error on failure.
     */
    public ServiceResult<Claim> validate(Claim claim) {
        // 1. Null check — entire claim missing
        if (claim == null) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("Claim must not be null."));
        }

        // 2. Required string fields
        if (claim.claimId() == null || claim.claimId().isBlank()) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("claimId is required and must not be blank."));
        }
        if (claim.type() == null) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("type is required."));
        }
        if (claim.ontologyId() == null || claim.ontologyId().isBlank()) {
            return ServiceResult.error(ServiceError.invalidClaimSchema("ontologyId is required and must not be blank."));
        }

        // 3. Validate entity kinds against known OWL kinds (for subject/object that are present)
        //    Check this BEFORE claim-type kind requirements so invalid kinds get the
        //    more fundamental "not a valid OWL entity kind" error rather than a misleading
        //    "must be 'X'" error for a kind that isn't even in the OWL vocabulary.
        if (claim.subject() != null && !VALID_KINDS.contains(claim.subject().kind())) {
            return ServiceResult.error(ServiceError.invalidClaimSchema(
                "subject.kind '" + claim.subject().kind() + "' is not a valid OWL entity kind. "
                + "Valid kinds: " + VALID_KINDS));
        }
        if (claim.object() != null && !VALID_KINDS.contains(claim.object().kind())
            && !"literal".equals(claim.object().kind())) {
            return ServiceResult.error(ServiceError.invalidClaimSchema(
                "object.kind '" + claim.object().kind() + "' is not a valid entity kind. "
                + "Valid kinds: " + VALID_KINDS + " and 'literal'."));
        }

        // 4. Entity kind requirements per claim type
        EntityKindRequirement req = KIND_REQUIREMENTS.get(claim.type());
        if (req == null) {
            return ServiceResult.error(ServiceError.unsupportedClaimType(
                claim.type().jsonName(),
                Arrays.stream(ClaimType.values()).map(ClaimType::jsonName).toList()
            ));
        }

        if (req.subjectRequired()) {
            if (claim.subject() == null) {
                return ServiceResult.error(ServiceError.invalidClaimSchema(
                    "subject is required for claim type '" + claim.type().jsonName() + "'."));
            }
            if (!req.subjectKind().equals(claim.subject().kind())) {
                return ServiceResult.error(ServiceError.invalidClaimSchema(
                    "subject.kind must be '" + req.subjectKind() + "' for claim type '" + claim.type().jsonName()
                    + "', but got '" + claim.subject().kind() + "'."));
            }
        }

        if (req.objectRequired()) {
            if (claim.object() == null) {
                return ServiceResult.error(ServiceError.invalidClaimSchema(
                    "object is required for claim type '" + claim.type().jsonName() + "'."));
            }
            if (!req.objectKind().equals(claim.object().kind())) {
                return ServiceResult.error(ServiceError.invalidClaimSchema(
                    "object.kind must be '" + req.objectKind() + "' for claim type '" + claim.type().jsonName()
                    + "', but got '" + claim.object().kind() + "'."));
            }
        }

        // 5. GraphScope validation — must be a known scope if provided
        if (claim.graphScope().isPresent()) {
            GraphScope scope = claim.graphScope().get();
            // GraphScope is an enum, so only valid values can reach here;
            // no extra validation needed beyond presence check.
        }

        // 6. Evidence options validation
        if (claim.options().isPresent()) {
            Map<String, Object> opts = claim.options().get();
            Object includeEvidence = opts.get(Claim.INCLUDE_EVIDENCE);
            if (includeEvidence != null && !(includeEvidence instanceof Boolean)) {
                return ServiceResult.error(ServiceError.invalidClaimSchema(
                    "options.includeEvidence must be a boolean, got: " + includeEvidence.getClass().getSimpleName()));
            }
            Object maxEvidence = opts.get(Claim.MAX_EVIDENCE);
            if (maxEvidence != null) {
                if (!(maxEvidence instanceof Number)) {
                    return ServiceResult.error(ServiceError.invalidClaimSchema(
                        "options.maxEvidence must be a number, got: " + maxEvidence.getClass().getSimpleName()));
                }
                int maxVal = ((Number) maxEvidence).intValue();
                if (maxVal < 1) {
                    return ServiceResult.error(ServiceError.invalidClaimSchema(
                        "options.maxEvidence must be a positive integer, got: " + maxVal));
                }
            }
        }

        return ServiceResult.success(claim, ResultMetadata.empty());
    }
}