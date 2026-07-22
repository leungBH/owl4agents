package org.owl4agents.toolcall.decomposition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.toolcall.ToolContract;

/**
 * v0.8.7 CL-001 / CL-002: Decomposes a single {@link ToolCallCandidate}
 * against its {@link ToolContract} into a {@link ToolCallClaimBatch}
 * containing structured {@link DecomposedClaim} objects.
 *
 * <p>The decomposer emits at minimum the following six claim categories
 * when applicable (per the {@code claim-decomposition} spec "Claim
 * Decomposition Rules"):</p>
 * <ol>
 *   <li><b>Target class membership</b> —
 *       {@code ClassAssertion(targetEntity, contract.targetClass)} →
 *       {@link ClaimType#INDIVIDUAL_MEMBERSHIP}.</li>
 *   <li><b>Location</b> —
 *       {@code ObjectPropertyAssertion(targetEntity, hasLocation, ?location)}
 *       plus {@code ClassAssertion(?location, Room)} →
 *       {@link ClaimType#OBJECT_PROPERTY_ASSERTION}. The claim uses the
 *       standard {@code hasLocation} predicate; the optional Room
 *       class-membership sub-claim is emitted as a second LOCATION claim
 *       when the contract's {@code requiredStates} contains a location
 *       hint.</li>
 *   <li><b>Capability possession</b> — the target entity belongs to a
 *       class that entails {@code hasCapability some Z} for each
 *       {@code Z} in {@code contract.requiredCapabilities} →
 *       {@link ClaimType#OBJECT_PROPERTY_ASSERTION} with
 *       {@code predicate="hasCapability"}. This claim is verifiable by the
 *       reasoner via subclass-based entailment (e.g.
 *       {@code CoolingOnlyDevice subclassOf HVACDevice},
 *       {@code HVACDevice subclassOf (hasCapability some TemperatureControl)}).</li>
 *   <li><b>Operation requires capability</b> —
 *       {@code SubClassOf(contract.toolOperation, OperationRequiringCapability(...))}
 *       → {@link ClaimType#SUBCLASS}. Emitted once per required capability;
 *       the subject is the tool's operation class (derived from
 *       {@code contract.toolName}) and the object is the
 *       {@code OperationRequiringCapability} class for that capability.</li>
 *   <li><b>User permission</b> —
 *       {@code ClassAssertion(requestedBy, contract.requiredPermission)} →
 *       {@link ClaimType#INDIVIDUAL_MEMBERSHIP}.</li>
 *   <li><b>Datatype satisfaction</b> — for each numeric/string argument,
 *       a {@link ClaimType#DATA_PROPERTY_RANGE} claim asserting the literal
 *       satisfies the contract's datatype constraints. The decomposer
 *       extracts the datatype range from the contract's
 *       {@code inputSchema} when present.</li>
 * </ol>
 *
 * <p>Per CL-002, the resulting {@link ToolCallClaimBatch} is passed to
 * {@code ClaimWorkflowService.verifyBatch} via
 * {@link ToolCallClaimBatchAdapter}; the pipeline MUST NOT invoke
 * {@code ClaimVerificationService.verify} once per claim.</p>
 *
 * <p>The "Empty batch for query-only tool" scenario is honored: when
 * {@code contract.targetClass} is empty, {@code requiredCapabilities} is
 * empty, {@code requiredPermission} is empty, and no datatype constraints
 * apply, the returned batch has an empty {@code claims} list.</p>
 */
public final class ClaimDecomposer {

    /** Default predicate IRIs used by the decomposer. */
    public static final String HAS_LOCATION_PREDICATE = "http://www.w3.org/ns/sosa/hasLocation";
    public static final String HAS_CAPABILITY_PREDICATE = "https://w3id.org/saref#hasCapability";
    public static final String OPERATION_REQUIRES_CAPABILITY_CLASS =
        "https://w3id.org/saref#OperationRequiringCapability";
    /** Fallback predicate IRIs (smart-home-study convention). */
    public static final String HAS_LOCATION_FALLBACK = "http://example.org/smarthome#hasLocation";
    public static final String HAS_CAPABILITY_FALLBACK = "http://example.org/smarthome#hasCapability";

    /**
     * Decompose a {@link ToolCallCandidate} against its {@link ToolContract}
     * into a {@link ToolCallClaimBatch}.
     *
     * @param candidate the tool call to decompose; must not be {@code null}
     * @param contract  the tool contract driving decomposition; must not be {@code null}
     * @return a {@link ToolCallClaimBatch}; never {@code null}
     */
    public ToolCallClaimBatch decompose(ToolCallCandidate candidate, ToolContract contract) {
        if (candidate == null) {
            throw new IllegalArgumentException("ClaimDecomposer.decompose: candidate must not be null");
        }
        if (contract == null) {
            throw new IllegalArgumentException("ClaimDecomposer.decompose: contract must not be null");
        }
        if (!candidate.toolName().equals(contract.toolName())) {
            // Defensive: the pipeline should always pair a candidate with
            // its own contract, but the decomposer does not enforce this
            // (a mismatch is a pipeline bug, not a user error).
            // Continue decomposition using the contract as authoritative.
        }

        List<DecomposedClaim> claims = new ArrayList<>();
        String callId = candidate.callId();

        // (1) Target class membership
        emitClassMembershipClaim(claims, callId, candidate, contract);

        // (2) Location
        emitLocationClaims(claims, callId, candidate, contract);

        // (3) Capability possession
        emitCapabilityClaims(claims, callId, candidate, contract);

        // (4) Operation requires capability
        emitOperationCapabilityClaims(claims, callId, candidate, contract);

        // (5) User permission
        emitPermissionClaim(claims, callId, candidate, contract);

        // (6) Datatype satisfaction
        emitDatatypeClaims(claims, callId, candidate, contract);

        return new ToolCallClaimBatch(
            callId,
            Instant.now(),
            candidate.sourceModel(),
            claims,
            claims.size(),
            buildClaimRoleMap(claims)
        );
    }

    // ────────────────────────────────────────────────────────────────────
    // (1) Target class membership
    // ────────────────────────────────────────────────────────────────────

    private void emitClassMembershipClaim(List<DecomposedClaim> claims,
                                          String callId,
                                          ToolCallCandidate candidate,
                                          ToolContract contract) {
        if (contract.targetClass().isEmpty()) {
            return;
        }
        if (candidate.targetEntity().isEmpty()) {
            return;
        }
        String targetEntity = candidate.targetEntity().get();
        String targetClass = contract.targetClass().get();
        DecomposedClaim claim = new DecomposedClaim(
            newClaimId(callId, "class-membership"),
            callId,
            ClaimCategory.CLASS_MEMBERSHIP,
            ClaimType.INDIVIDUAL_MEMBERSHIP,
            individual(targetEntity),
            "classAssertion",
            klass(targetClass),
            true,
            Optional.empty(),
            Optional.empty(),
            Optional.of("Target entity " + targetEntity + " is a member of " + targetClass
                + " (asserted or inferred via subclass hierarchy)")
        );
        claims.add(claim);
    }

    // ────────────────────────────────────────────────────────────────────
    // (2) Location
    // ────────────────────────────────────────────────────────────────────

    private void emitLocationClaims(List<DecomposedClaim> claims,
                                    String callId,
                                    ToolCallCandidate candidate,
                                    ToolContract contract) {
        if (candidate.targetEntity().isEmpty()) {
            return;
        }
        // Emit a LOCATION claim whenever the contract has any requiredState
        // referencing a location, OR whenever the candidate's arguments
        // contain a "location" / "room" key. The claim verifies the
        // locatedIn relation (OWL responsibility); the closed-world
        // minCount=1 is enforced by SHACL.
        String targetEntity = candidate.targetEntity().get();
        Optional<String> locationIri = extractLocationFromCandidate(candidate, contract);
        if (locationIri.isEmpty()) {
            // No location provided in the candidate; emit a LOCATION claim
            // only when the contract requires a location (so the reasoner
            // can verify the missing-assertion case is caught by SHACL).
            boolean contractRequiresLocation = contract.requiredStates().stream()
                .anyMatch(s -> s != null && (s.toLowerCase().contains("location")
                    || s.toLowerCase().contains("room")));
            if (!contractRequiresLocation) {
                return;
            }
            // Emit a "verify location exists" claim with a placeholder
            // object — the reasoner will return UNKNOWN, and SHACL will
            // catch the missing-assertion violation.
            DecomposedClaim claim = new DecomposedClaim(
                newClaimId(callId, "location-required"),
                callId,
                ClaimCategory.LOCATION,
                ClaimType.OBJECT_PROPERTY_ASSERTION,
                individual(targetEntity),
                HAS_LOCATION_PREDICATE,
                null,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Target entity " + targetEntity
                    + " is required to have a location (hasLocation minCount=1)")
            );
            claims.add(claim);
            return;
        }
        // Emit the locatedIn relation claim
        DecomposedClaim relationClaim = new DecomposedClaim(
            newClaimId(callId, "location-relation"),
            callId,
            ClaimCategory.LOCATION,
            ClaimType.OBJECT_PROPERTY_ASSERTION,
            individual(targetEntity),
            HAS_LOCATION_PREDICATE,
            individual(locationIri.get()),
            true,
            Optional.empty(),
            Optional.empty(),
            Optional.of("Target entity " + targetEntity + " is located in " + locationIri.get())
        );
        claims.add(relationClaim);
        // Emit the Room class-membership sub-claim (verifies the location
        // is a valid Room individual).
        DecomposedClaim roomClaim = new DecomposedClaim(
            newClaimId(callId, "location-room"),
            callId,
            ClaimCategory.LOCATION,
            ClaimType.INDIVIDUAL_MEMBERSHIP,
            individual(locationIri.get()),
            "classAssertion",
            klass("http://www.w3.org/ns/sosa#Room"),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.of("Location " + locationIri.get() + " is a member of Room")
        );
        claims.add(roomClaim);
    }

    // ────────────────────────────────────────────────────────────────────
    // (3) Capability possession
    // ────────────────────────────────────────────────────────────────────

    private void emitCapabilityClaims(List<DecomposedClaim> claims,
                                      String callId,
                                      ToolCallCandidate candidate,
                                      ToolContract contract) {
        if (candidate.targetEntity().isEmpty()) {
            return;
        }
        if (contract.requiredCapabilities().isEmpty()) {
            return;
        }
        String targetEntity = candidate.targetEntity().get();
        for (String capability : contract.requiredCapabilities()) {
            DecomposedClaim claim = new DecomposedClaim(
                newClaimId(callId, "capability-" + sanitizeForId(capability)),
                callId,
                ClaimCategory.CAPABILITY,
                ClaimType.OBJECT_PROPERTY_ASSERTION,
                individual(targetEntity),
                HAS_CAPABILITY_PREDICATE,
                klass(capability),
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Target entity " + targetEntity + " hasCapability some " + capability
                    + " (asserted or inferred via subclass, e.g. CoolingOnlyDevice subclassOf HVACDevice"
                    + " subclassOf (hasCapability some TemperatureControl))")
            );
            claims.add(claim);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // (4) Operation requires capability
    // ────────────────────────────────────────────────────────────────────

    private void emitOperationCapabilityClaims(List<DecomposedClaim> claims,
                                               String callId,
                                               ToolCallCandidate candidate,
                                               ToolContract contract) {
        if (contract.requiredCapabilities().isEmpty()) {
            return;
        }
        // The tool operation class is conventionally derived from the
        // toolName (e.g. "set_temperature" → SetTemperatureOperation).
        String operationClassIri = deriveOperationClassIri(contract.toolName());
        for (String capability : contract.requiredCapabilities()) {
            String operationRequiringCapabilityIri = OPERATION_REQUIRES_CAPABILITY_CLASS
                + "/" + sanitizeForId(capability);
            DecomposedClaim claim = new DecomposedClaim(
                newClaimId(callId, "operation-capability-" + sanitizeForId(capability)),
                callId,
                ClaimCategory.OPERATION_REQUIREMENTS,
                ClaimType.SUBCLASS,
                klass(operationClassIri),
                "subClassOf",
                klass(operationRequiringCapabilityIri),
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Operation " + operationClassIri + " requires capability " + capability)
            );
            claims.add(claim);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // (5) User permission
    // ────────────────────────────────────────────────────────────────────

    private void emitPermissionClaim(List<DecomposedClaim> claims,
                                     String callId,
                                     ToolCallCandidate candidate,
                                     ToolContract contract) {
        if (contract.requiredPermission().isEmpty()) {
            return;
        }
        if (candidate.requestedBy().isEmpty()) {
            // No user provided; emit a PERMISSION claim with a null subject
            // so the reasoner returns UNKNOWN and SHACL catches the missing
            // requestedBy assertion.
            DecomposedClaim claim = new DecomposedClaim(
                newClaimId(callId, "permission-missing-user"),
                callId,
                ClaimCategory.PERMISSION,
                ClaimType.INDIVIDUAL_MEMBERSHIP,
                null,
                "classAssertion",
                klass(contract.requiredPermission().get()),
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Requesting user is required to be a member of "
                    + contract.requiredPermission().get() + " (no user provided)")
            );
            claims.add(claim);
            return;
        }
        String user = candidate.requestedBy().get();
        String permission = contract.requiredPermission().get();
        DecomposedClaim claim = new DecomposedClaim(
            newClaimId(callId, "permission"),
            callId,
            ClaimCategory.PERMISSION,
            ClaimType.INDIVIDUAL_MEMBERSHIP,
            individual(user),
            "classAssertion",
            klass(permission),
            true,
            Optional.empty(),
            Optional.empty(),
            Optional.of("User " + user + " is a member of " + permission)
        );
        claims.add(claim);
    }

    // ────────────────────────────────────────────────────────────────────
    // (6) Datatype satisfaction
    // ────────────────────────────────────────────────────────────────────

    private void emitDatatypeClaims(List<DecomposedClaim> claims,
                                    String callId,
                                    ToolCallCandidate candidate,
                                    ToolContract contract) {
        Map<String, Object> schema = contract.inputSchema();
        if (schema == null || schema.isEmpty()) {
            return;
        }
        Object propertiesObj = schema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<String, String> arg : candidate.arguments().entrySet()) {
            String argName = arg.getKey();
            String argValue = arg.getValue();
            Object propSchema = properties.get(argName);
            if (!(propSchema instanceof Map<?, ?> propMap)) {
                continue;
            }
            String datatypeIri = extractDatatypeIri(propMap);
            if (datatypeIri == null) {
                continue;
            }
            // Emit a DATA_PROPERTY_RANGE claim: subject=datatype, predicate=argName,
            // object=literal value. The reasoner verifies the literal satisfies
            // the datatype range.
            DecomposedClaim claim = new DecomposedClaim(
                newClaimId(callId, "datatype-" + sanitizeForId(argName)),
                callId,
                ClaimCategory.DATATYPE,
                ClaimType.DATA_PROPERTY_RANGE,
                datatype(datatypeIri),
                argName,
                literal(argValue),
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of("Argument " + argName + "=" + argValue + " satisfies datatype " + datatypeIri
                    + extractRangeDescription(propMap))
            );
            claims.add(claim);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private static String newClaimId(String callId, String suffix) {
        return callId + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ClaimEntity individual(String iri) {
        return new ClaimEntity("individual", iri);
    }

    private static ClaimEntity klass(String iri) {
        return new ClaimEntity("class", iri);
    }

    private static ClaimEntity datatype(String iri) {
        return new ClaimEntity("datatype", iri);
    }

    private static ClaimEntity literal(String value) {
        return new ClaimEntity("literal", value);
    }

    private static String sanitizeForId(String s) {
        if (s == null) return "unknown";
        // Strip protocol/host portion and use only the local name
        int lastSlash = s.lastIndexOf('/');
        int lastHash = s.lastIndexOf('#');
        int cut = Math.max(lastSlash, lastHash);
        String local = cut >= 0 && cut < s.length() - 1 ? s.substring(cut + 1) : s;
        return local.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String deriveOperationClassIri(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "http://example.org/smarthome#UnknownOperation";
        }
        // Convert snake_case to PascalCase: "set_temperature" → "SetTemperature"
        StringBuilder sb = new StringBuilder();
        for (String part : toolName.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return "http://example.org/smarthome#" + sb.toString() + "Operation";
    }

    private static Optional<String> extractLocationFromCandidate(ToolCallCandidate candidate,
                                                                 ToolContract contract) {
        // Look for a "location" / "room" key in the arguments
        for (String key : new String[]{"location", "room", "roomIri", "locationIri"}) {
            String v = candidate.arguments().get(key);
            if (v != null && !v.isBlank()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    private static String extractDatatypeIri(Map<?, ?> propSchema) {
        Object type = propSchema.get("type");
        if (type == null) return null;
        String t = type.toString();
        return switch (t) {
            case "integer" -> "http://www.w3.org/2001/XMLSchema#integer";
            case "number" -> "http://www.w3.org/2001/XMLSchema#decimal";
            case "string" -> "http://www.w3.org/2001/XMLSchema#string";
            case "boolean" -> "http://www.w3.org/2001/XMLSchema#boolean";
            default -> null;
        };
    }

    private static String extractRangeDescription(Map<?, ?> propSchema) {
        Object min = propSchema.get("minimum");
        Object max = propSchema.get("maximum");
        StringBuilder sb = new StringBuilder();
        if (min != null) sb.append(" >= ").append(min);
        if (max != null) sb.append(" <= ").append(max);
        return sb.length() > 0 ? " (range:" + sb + ")" : "";
    }

    private static Map<String, String> buildClaimRoleMap(List<DecomposedClaim> claims) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (DecomposedClaim c : claims) {
            m.put(c.claimId(), c.category().roleName());
        }
        return m;
    }
}
