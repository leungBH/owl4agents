package org.owl4agents.toolcall.decomposition;

import java.util.Map;
import java.util.Set;

/**
 * v0.8.7 CL-003 / CL-005: Documents and enforces the division of labor
 * between OWL claim verification and SHACL validation in the 10-stage
 * pipeline.
 *
 * <p>Per the {@code claim-decomposition} spec "Claim and SHACL Division of
 * Labor" and "No OWL No Contradiction Implies Legal Inference" requirements:</p>
 *
 * <h2>OWL Claim responsibilities</h2>
 * <ul>
 *   <li>Class membership (target entity belongs to {@code contract.targetClass})</li>
 *   <li>Capability inheritance (subclass-based capability entailment, e.g.
 *       {@code CoolingOnlyDevice subclassOf HVACDevice} →
 *       {@code hasCapability some TemperatureControl})</li>
 *   <li>Class disjointness (e.g. HVACDevice and NonHVACDevice asserted disjoint)</li>
 *   <li>Relation entailment (e.g. {@code locatedIn} transitivity)</li>
 *   <li>Permission class membership</li>
 *   <li>Risk type membership</li>
 *   <li>Detection of whether the candidate assertions would cause ontology
 *       inconsistency</li>
 * </ul>
 *
 * <h2>SHACL responsibilities</h2>
 * <ul>
 *   <li>Required fields ({@code sh:minCount})</li>
 *   <li>Cardinality ({@code sh:minCount} / {@code sh:maxCount})</li>
 *   <li>Closed-world completeness ({@code sh:closed})</li>
 *   <li>Cross-field numeric relationships ({@code sh:lessThan},
 *       {@code sh:lessThanOrEquals}, etc.)</li>
 *   <li>Dynamic state assertions (current device state predicates)</li>
 *   <li>Multi-device conflicts (e.g. two devices targeted by mutually
 *       exclusive operations)</li>
 *   <li>Time constraints (operation valid within a time window)</li>
 *   <li>Permission context (request was made within an active session)</li>
 *   <li>Parameter combinations (argument sets that are jointly valid)</li>
 * </ul>
 *
 * <h2>Prohibition (CL-005)</h2>
 * <p>The pipeline SHALL NOT treat "OWL found no contradiction" as sufficient
 * evidence that the call is legal — SHACL MUST still run and pass. Even if
 * all OWL claims verify, the call is legal only if SHACL validation also
 * passes (or only produces {@code Warning}/{@code Info} severities that the
 * policy treats as non-blocking). The pipeline SHALL NOT expose a
 * {@code valid: true} field; the {@link org.owl4agents.toolcall.ValidationDecision}
 * enum is the sole source of truth.</p>
 */
public final class ClaimShaclDivisionOfLabor {

    private ClaimShaclDivisionOfLabor() {}

    /**
     * Claim categories that the OWL reasoner is responsible for verifying.
     * Used by {@link ClaimDecomposer} to decide which categories produce
     * {@link DecomposedClaim} objects for stage 6.
     */
    public static final Set<ClaimCategory> OWL_RESPONSIBLE_CATEGORIES = Set.of(
        ClaimCategory.CLASS_MEMBERSHIP,
        ClaimCategory.CAPABILITY,
        ClaimCategory.OPERATION_REQUIREMENTS,
        ClaimCategory.PERMISSION
        // LOCATION is verified via OWL ObjectPropertyAssertion, but the
        // closed-world "device must have a location" assertion is a SHACL
        // minCount constraint. The decomposer still emits an OWL claim for
        // LOCATION so the reasoner can verify the locatedIn relation when
        // asserted; SHACL separately enforces the minCount=1 requirement.
        // DATATYPE is verified by both: OWL DataPropertyRange for the
        // reasoner-derivable range check, SHACL sh:minInclusive/maxInclusive
        // for the closed-world numeric bounds.
    );

    /**
     * Human-readable labels for the OWL responsibility categories. Used in
     * reports and test fixtures.
     */
    public static final Map<ClaimCategory, String> OWL_RESPONSIBILITY_DESCRIPTIONS = Map.of(
        ClaimCategory.CLASS_MEMBERSHIP,
        "Class membership — verify targetEntity belongs to contract.targetClass "
            + "(inferred or asserted). Disjointness detection catches mutually "
            + "exclusive class assertions.",
        ClaimCategory.LOCATION,
        "LocatedIn relation entailment — verify the asserted location relation "
            + "is consistent with the ontology's transitive locatedIn hierarchy. "
            + "The closed-world minCount=1 requirement is enforced by SHACL.",
        ClaimCategory.CAPABILITY,
        "Capability inheritance — verify targetEntity hasCapability some Z for "
            + "each required capability Z, where the assertion may be entailed "
            + "via subclass axioms (e.g. CoolingOnlyDevice subclassOf HVACDevice "
            + "subclassOf (hasCapability some TemperatureControl)).",
        ClaimCategory.OPERATION_REQUIREMENTS,
        "Operation requires capability — verify the tool's operation class is "
            + "a subclass of OperationRequiringCapability(...) for each declared "
            + "required capability.",
        ClaimCategory.PERMISSION,
        "Permission class membership — verify the requesting user belongs to "
            + "the contract.requiredPermission class (e.g. ControlHVACPermission).",
        ClaimCategory.DATATYPE,
        "Datatype range satisfaction — verify each numeric/string argument "
            + "satisfies the contract's datatype range restriction. SHACL "
            + "additionally enforces closed-world numeric bounds (sh:minInclusive, "
            + "sh:maxInclusive)."
    );

    /**
     * SHACL responsibility areas (mirror of the spec list). Used in reports
     * and test fixtures; not used for routing at decomposition time because
     * SHACL applies to the overlay ontology (stage 7) rather than per-claim.
     */
    public static final Set<String> SHACL_RESPONSIBILITY_AREAS = Set.of(
        "required_fields",
        "cardinality",
        "closed_world_completeness",
        "cross_field_numeric_relationships",
        "dynamic_state_assertions",
        "multi_device_conflicts",
        "time_constraints",
        "permission_context",
        "parameter_combinations"
    );

    /**
     * CL-005: Whether OWL "no contradiction" is sufficient to call the tool
     * legal. Always returns {@code false} — SHACL MUST also pass.
     *
     * <p>This method exists to make the prohibition rule explicit and
     * discoverable in code, rather than implicit in pipeline logic. The
     * pipeline's stage 9 (Decision) consults this when computing the final
     * {@link org.owl4agents.toolcall.ValidationDecision}.</p>
     */
    public static boolean owlNoContradictionImpliesLegal() {
        return false;
    }

    /**
     * Whether a given {@link ClaimCategory} is primarily the OWL reasoner's
     * responsibility (true) or SHACL's responsibility (false). Categories
     * not in {@link #OWL_RESPONSIBLE_CATEGORIES} are SHACL's responsibility,
     * with the caveat noted above that LOCATION and DATATYPE have shared
     * responsibility (OWL verifies the entailed relation/range; SHACL
     * enforces the closed-world constraint).
     */
    public static boolean isOwlResponsible(ClaimCategory category) {
        return OWL_RESPONSIBLE_CATEGORIES.contains(category)
            || category == ClaimCategory.LOCATION
            || category == ClaimCategory.DATATYPE;
    }
}
