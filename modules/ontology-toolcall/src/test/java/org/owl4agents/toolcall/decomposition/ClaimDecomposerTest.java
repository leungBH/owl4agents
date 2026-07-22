package org.owl4agents.toolcall.decomposition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.model.ClaimBatchInput;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.overlay.ToolCallCandidate;
import org.owl4agents.toolcall.RiskLevel;
import org.owl4agents.toolcall.ToolContract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 CL-001 / CL-002 / CL-007: Unit tests for {@link ClaimDecomposer}.
 *
 * <p>Covers the six claim category decomposition correctness, the
 * {@link ToolCallClaimBatch} output format, the {@link ToolCallClaimBatchAdapter}
 * conversion to {@link ClaimBatchInput}, and the prohibition rule
 * (CL-005) enforced by {@link ClaimShaclDivisionOfLabor}.</p>
 */
@DisplayName("CL-007 Claim Decomposition")
class ClaimDecomposerTest {

    private ClaimDecomposer decomposer;

    @BeforeEach
    void setUp() {
        decomposer = new ClaimDecomposer();
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private ToolContract setTemperatureContract() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> targetTemp = new LinkedHashMap<>();
        targetTemp.put("type", "number");
        targetTemp.put("minimum", 16);
        targetTemp.put("maximum", 30);
        properties.put("targetTemperature", targetTemp);
        schema.put("properties", properties);
        schema.put("required", List.of("targetTemperature"));

        return new ToolContract(
            "set_temperature",
            schema,
            Optional.of("http://example.org/smarthome#HVACDevice"),
            List.of("http://example.org/smarthome#TemperatureControl"),
            List.of("deviceOn"),
            List.of("changesState(targetEntity, targetTemperature)"),
            RiskLevel.MEDIUM,
            Optional.of("http://example.org/smarthome#ControlHVACPermission"),
            List.of("smart-home-core")
        );
    }

    private ToolCallCandidate setTemperatureCandidate() {
        return new ToolCallCandidate(
            "call-001",
            Optional.empty(),
            Optional.empty(),
            "set_temperature",
            Optional.of("http://example.org/smarthome#thermostat1"),
            Map.of("targetTemperature", "22.5", "location", "http://example.org/smarthome#livingRoom"),
            Optional.of("http://example.org/smarthome#user1"),
            Optional.empty(),
            Optional.empty(),
            Optional.of("gpt-4o"),
            Optional.empty()
        );
    }

    // ────────────────────────────────────────────────────────────────────
    // 6.1 + 6.2: Decomposition rules + ToolCallClaimBatch output format
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("decompose produces all 6 categories for smart-home set_temperature example")
    void decomposeProducesAllSixCategories() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

        assertEquals("call-001", batch.callId());
        assertNotNull(batch.decompositionTimestamp());
        assertTrue(batch.sourceModel().isPresent());
        assertEquals("gpt-4o", batch.sourceModel().get());
        assertFalse(batch.claims().isEmpty(), "Batch should contain at least one claim");
        assertEquals(batch.claims().size(), batch.claimCount());

        // Verify all 6 categories are present (CL-001 scenarios).
        java.util.Set<ClaimCategory> categories = batch.claims().stream()
            .map(DecomposedClaim::category)
            .collect(Collectors.toSet());
        assertTrue(categories.contains(ClaimCategory.CLASS_MEMBERSHIP),
            "CLASS_MEMBERSHIP claim missing");
        assertTrue(categories.contains(ClaimCategory.LOCATION),
            "LOCATION claim missing");
        assertTrue(categories.contains(ClaimCategory.CAPABILITY),
            "CAPABILITY claim missing");
        assertTrue(categories.contains(ClaimCategory.OPERATION_REQUIREMENTS),
            "OPERATION_REQUIREMENTS claim missing");
        assertTrue(categories.contains(ClaimCategory.PERMISSION),
            "PERMISSION claim missing");
        assertTrue(categories.contains(ClaimCategory.DATATYPE),
            "DATATYPE claim missing");
    }

    @Test
    @DisplayName("decompose produces at least 3 claims with target_class/capability/permission roles")
    void decomposeProducesAtLeastThreeClaimsWithRequiredRoles() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

        // Per spec "Decomposition produces multiple claims" scenario:
        // claims SHALL contain at least 3 claims, and claimRole SHALL include
        // entries with roles target_class, capability, and permission.
        assertTrue(batch.claims().size() >= 3,
            "Batch must contain at least 3 claims; got " + batch.claims().size());

        java.util.Set<String> roles = new java.util.HashSet<>(batch.claimRole().values());
        assertTrue(roles.contains("target_class"),
            "claimRole must contain 'target_class'; got " + roles);
        assertTrue(roles.contains("capability"),
            "claimRole must contain 'capability'; got " + roles);
        assertTrue(roles.contains("permission"),
            "claimRole must contain 'permission'; got " + roles);
    }

    @Test
    @DisplayName("ToolCallClaimBatch claimRole keys match claim IDs")
    void claimRoleKeysMatchClaimIds() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

        java.util.Set<String> claimIds = batch.claims().stream()
            .map(DecomposedClaim::claimId)
            .collect(Collectors.toSet());
        assertEquals(claimIds, batch.claimRole().keySet(),
            "claimRole keys must exactly match claim IDs");
    }

    @Test
    @DisplayName("Empty batch for query-only tool (no target class, no capabilities, no permission)")
    void emptyBatchForQueryOnlyTool() {
        ToolContract queryContract = new ToolContract(
            "list_devices",
            Map.of("type", "object"),
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            RiskLevel.LOW,
            Optional.empty(),
            List.of()
        );
        ToolCallCandidate queryCandidate = new ToolCallCandidate(
            "call-query",
            Optional.empty(),
            Optional.empty(),
            "list_devices",
            Optional.empty(),
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        ToolCallClaimBatch batch = decomposer.decompose(queryCandidate, queryContract);

        assertTrue(batch.claims().isEmpty(), "Query-only tool should produce empty batch");
        assertTrue(batch.claimRole().isEmpty(), "Empty batch should have empty claimRole map");
        assertEquals(0, batch.claimCount());
        assertTrue(batch.isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────
    // 6.1: Category-specific decomposition correctness
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Category-specific decomposition correctness")
    class CategorySpecific {

        @Test
        @DisplayName("(1) CLASS_MEMBERSHIP: ClassAssertion(targetEntity, contract.targetClass)")
        void classMembershipClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim claim = findFirstByCategory(batch, ClaimCategory.CLASS_MEMBERSHIP);
            assertNotNull(claim);
            assertEquals(ClaimType.INDIVIDUAL_MEMBERSHIP, claim.claimType());
            assertEquals("http://example.org/smarthome#thermostat1", claim.subject().iri());
            assertEquals("individual", claim.subject().kind());
            assertEquals("http://example.org/smarthome#HVACDevice", claim.object().iri());
            assertEquals("class", claim.object().kind());
            assertTrue(claim.required());
            assertEquals("target_class", claim.category().roleName());
        }

        @Test
        @DisplayName("(2) LOCATION: ObjectPropertyAssertion(targetEntity, hasLocation, ?location)")
        void locationClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim locationClaim = findFirstByCategory(batch, ClaimCategory.LOCATION);
            assertNotNull(locationClaim);
            // The first LOCATION claim is the locatedIn relation assertion
            if (locationClaim.object() != null) {
                assertEquals(ClaimType.OBJECT_PROPERTY_ASSERTION, locationClaim.claimType());
                assertEquals("http://example.org/smarthome#thermostat1", locationClaim.subject().iri());
                assertEquals(ClaimDecomposer.HAS_LOCATION_PREDICATE, locationClaim.predicate());
                assertEquals("http://example.org/smarthome#livingRoom", locationClaim.object().iri());
            } else {
                // Or it could be the "location-required" claim if no location was provided
                assertEquals(ClaimType.OBJECT_PROPERTY_ASSERTION, locationClaim.claimType());
                assertNotNull(locationClaim.predicate());
            }
            assertEquals("location", locationClaim.category().roleName());
        }

        @Test
        @DisplayName("(3) CAPABILITY: target entity hasCapability some Z (inferred via subclass)")
        void capabilityClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim claim = findFirstByCategory(batch, ClaimCategory.CAPABILITY);
            assertNotNull(claim);
            assertEquals(ClaimType.OBJECT_PROPERTY_ASSERTION, claim.claimType());
            assertEquals("http://example.org/smarthome#thermostat1", claim.subject().iri());
            assertEquals(ClaimDecomposer.HAS_CAPABILITY_PREDICATE, claim.predicate());
            assertEquals("http://example.org/smarthome#TemperatureControl", claim.object().iri());
            assertTrue(claim.required());
            // Per spec "Capability claim relies on inference" scenario: the
            // evidence description should hint at inference via subclass.
            assertTrue(claim.evidence().isPresent(),
                "CAPABILITY claim should carry an evidence hint about subclass-based inference");
            assertTrue(claim.evidence().get().contains("CoolingOnlyDevice")
                || claim.evidence().get().contains("subclass")
                || claim.evidence().get().contains("inferred"),
                "CAPABILITY evidence should hint at subclass-based inference");
        }

        @Test
        @DisplayName("(4) OPERATION_REQUIREMENTS: SubClassOf(operation, OperationRequiringCapability)")
        void operationRequirementsClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim claim = findFirstByCategory(batch, ClaimCategory.OPERATION_REQUIREMENTS);
            assertNotNull(claim);
            assertEquals(ClaimType.SUBCLASS, claim.claimType());
            assertEquals("class", claim.subject().kind());
            assertTrue(claim.subject().iri().contains("SetTemperatureOperation"),
                "Operation class should be derived from toolName; got " + claim.subject().iri());
            assertEquals("subClassOf", claim.predicate());
            assertTrue(claim.object().iri().contains("OperationRequiringCapability"),
                "Object should be OperationRequiringCapability; got " + claim.object().iri());
            assertTrue(claim.required());
            assertEquals("operation_capability", claim.category().roleName());
        }

        @Test
        @DisplayName("(5) PERMISSION: ClassAssertion(requestedBy, contract.requiredPermission)")
        void permissionClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim claim = findFirstByCategory(batch, ClaimCategory.PERMISSION);
            assertNotNull(claim);
            assertEquals(ClaimType.INDIVIDUAL_MEMBERSHIP, claim.claimType());
            assertEquals("http://example.org/smarthome#user1", claim.subject().iri());
            assertEquals("individual", claim.subject().kind());
            assertEquals("http://example.org/smarthome#ControlHVACPermission", claim.object().iri());
            assertEquals("class", claim.object().kind());
            assertTrue(claim.required());
            assertEquals("permission", claim.category().roleName());
        }

        @Test
        @DisplayName("(6) DATATYPE: DataPropertyRangeRestriction for numeric argument")
        void datatypeClaim() {
            ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

            DecomposedClaim claim = findFirstByCategory(batch, ClaimCategory.DATATYPE);
            assertNotNull(claim);
            assertEquals(ClaimType.DATA_PROPERTY_RANGE, claim.claimType());
            // Subject is the datatype (xsd:decimal for "number" type)
            assertEquals("datatype", claim.subject().kind());
            assertTrue(claim.subject().iri().contains("XMLSchema"));
            assertEquals("targetTemperature", claim.predicate());
            assertEquals("literal", claim.object().kind());
            assertEquals("22.5", claim.object().iri());
            assertTrue(claim.required());
            assertEquals("datatype", claim.category().roleName());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 6.3: ToolCallClaimBatchAdapter converts to ClaimBatchInput for sharing
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ToolCallClaimBatchAdapter converts batch to ClaimBatchInput without mutation")
    void adapterConvertsToClaimBatchInput() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

        ToolCallClaimBatchAdapter adapter = new ToolCallClaimBatchAdapter(batch, "smart-home-ontology");
        ClaimBatchInput input = adapter.toClaimBatchInput();

        assertEquals(batch.callId(), input.answerId(),
            "answerId should match callId per adapter spec");
        assertEquals(batch.claims().size(), input.claims().size(),
            "Claim count should match");
        assertTrue(input.question().isEmpty());
        assertTrue(input.answerText().isEmpty());

        // Verify each BatchClaim was projected correctly
        for (int i = 0; i < batch.claims().size(); i++) {
            DecomposedClaim original = batch.claims().get(i);
            ClaimBatchInput.BatchClaim converted = input.claims().get(i);
            assertEquals(original.claimId(), converted.id());
            assertEquals(original.claimType(), converted.type());
            assertEquals(original.required(), converted.required());
            assertEquals(original.subject(), converted.subject().orElse(null));
            assertEquals(original.object(), converted.object().orElse(null));
        }

        // Verify the adapter preserves callId and claimRole out-of-band
        assertEquals(batch.callId(), adapter.callId());
        assertEquals(batch.claimRole(), adapter.claimRole());

        // Verify the adapter did NOT mutate the original batch
        assertEquals(batch.claims().size(), batch.claimCount());
    }

    @Test
    @DisplayName("Adapter preserves claimRole metadata for pipeline re-attachment")
    void adapterPreservesClaimRoleMetadata() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());
        ToolCallClaimBatchAdapter adapter = new ToolCallClaimBatchAdapter(batch, "smart-home-ontology");

        // The adapter's claimRole should match the batch's claimRole exactly
        assertEquals(batch.claimRole(), adapter.claimRole());

        // After verifyBatch returns, the pipeline can re-attach role info
        // to each ClaimResult by looking up claimId in claimRole.
        for (DecomposedClaim claim : batch.claims()) {
            String role = adapter.claimRole().get(claim.claimId());
            assertNotNull(role, "Every claim must have an entry in claimRole");
            assertEquals(claim.category().roleName(), role);
        }
    }

    @Test
    @DisplayName("Adapter toClaim converts DecomposedClaim to Claim with ontologyId")
    void adapterToClaim() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());
        ToolCallClaimBatchAdapter adapter = new ToolCallClaimBatchAdapter(batch, "smart-home-ontology");

        DecomposedClaim firstClaim = batch.claims().get(0);
        org.owl4agents.core.model.Claim claim = adapter.toClaim(firstClaim);

        assertEquals(firstClaim.claimId(), claim.claimId());
        assertEquals(firstClaim.claimType(), claim.type());
        assertEquals("smart-home-ontology", claim.ontologyId());
        assertEquals(firstClaim.subject(), claim.subject());
        assertEquals(firstClaim.object(), claim.object());
    }

    @Test
    @DisplayName("Adapter rejects null batch and blank ontologyId")
    void adapterRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> new ToolCallClaimBatchAdapter(null, "ont"));
        assertThrows(IllegalArgumentException.class,
            () -> new ToolCallClaimBatchAdapter(
                decomposer.decompose(setTemperatureCandidate(), setTemperatureContract()), ""));
        assertThrows(IllegalArgumentException.class,
            () -> new ToolCallClaimBatchAdapter(
                decomposer.decompose(setTemperatureCandidate(), setTemperatureContract()), null));
    }

    // ────────────────────────────────────────────────────────────────────
    // 6.4 + 6.5: Claim and SHACL Division of Labor + Prohibition rule
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClaimShaclDivisionOfLabor documents OWL vs SHACL responsibilities")
    void divisionOfLaborDocumentsResponsibilities() {
        // OWL responsibility set
        assertTrue(ClaimShaclDivisionOfLabor.OWL_RESPONSIBLE_CATEGORIES.contains(ClaimCategory.CLASS_MEMBERSHIP));
        assertTrue(ClaimShaclDivisionOfLabor.OWL_RESPONSIBLE_CATEGORIES.contains(ClaimCategory.CAPABILITY));
        assertTrue(ClaimShaclDivisionOfLabor.OWL_RESPONSIBLE_CATEGORIES.contains(ClaimCategory.OPERATION_REQUIREMENTS));
        assertTrue(ClaimShaclDivisionOfLabor.OWL_RESPONSIBLE_CATEGORIES.contains(ClaimCategory.PERMISSION));

        // SHACL responsibility areas (mirror of spec list)
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("required_fields"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("cardinality"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("closed_world_completeness"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("cross_field_numeric_relationships"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("dynamic_state_assertions"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("multi_device_conflicts"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("time_constraints"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("permission_context"));
        assertTrue(ClaimShaclDivisionOfLabor.SHACL_RESPONSIBILITY_AREAS.contains("parameter_combinations"));

        // Per-category responsibility descriptions
        for (ClaimCategory cat : ClaimCategory.values()) {
            assertNotNull(ClaimShaclDivisionOfLabor.OWL_RESPONSIBILITY_DESCRIPTIONS.get(cat),
                "OWL responsibility description missing for " + cat);
        }
    }

    @Test
    @DisplayName("CL-005 prohibition: 'OWL no contradiction' does NOT imply legal")
    void prohibitionOwlNoContradictionDoesNotImplyLegal() {
        // The prohibition rule MUST return false — SHACL MUST also pass.
        assertFalse(ClaimShaclDivisionOfLabor.owlNoContradictionImpliesLegal(),
            "OWL 'no contradiction' MUST NOT imply the call is legal");
    }

    @Test
    @DisplayName("isOwlResponsible correctly routes categories")
    void isOwlResponsibleRoutesCategories() {
        // OWL responsibility
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.CLASS_MEMBERSHIP));
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.CAPABILITY));
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.OPERATION_REQUIREMENTS));
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.PERMISSION));
        // Shared responsibility (OWL verifies the entailed relation/range;
        // SHACL enforces the closed-world constraint)
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.LOCATION));
        assertTrue(ClaimShaclDivisionOfLabor.isOwlResponsible(ClaimCategory.DATATYPE));
    }

    // ────────────────────────────────────────────────────────────────────
    // 6.7: Additional batch format tests
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ToolCallClaimBatch is immutable (defensive copies)")
    void batchIsImmutable() {
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());

        // claims list is unmodifiable
        assertThrows(UnsupportedOperationException.class,
            () -> batch.claims().add(new DecomposedClaim(
                "x", batch.callId(), ClaimCategory.PERMISSION,
                ClaimType.INDIVIDUAL_MEMBERSHIP, null, null, null)));

        // claimRole map is unmodifiable
        assertThrows(UnsupportedOperationException.class,
            () -> batch.claimRole().put("new-id", "target_class"));
    }

    @Test
    @DisplayName("DecomposedClaim canonical constructor validates inputs")
    void decomposedClaimValidatesInputs() {
        assertThrows(IllegalArgumentException.class,
            () -> new DecomposedClaim("", "call-1",
                ClaimCategory.PERMISSION, ClaimType.INDIVIDUAL_MEMBERSHIP,
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DecomposedClaim("id", "",
                ClaimCategory.PERMISSION, ClaimType.INDIVIDUAL_MEMBERSHIP,
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DecomposedClaim("id", "call-1",
                null, ClaimType.INDIVIDUAL_MEMBERSHIP,
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DecomposedClaim("id", "call-1",
                ClaimCategory.PERMISSION, null,
                null, null, null));
    }

    @Test
    @DisplayName("Decomposer rejects null candidate and null contract")
    void decomposerRejectsNulls() {
        assertThrows(IllegalArgumentException.class,
            () -> decomposer.decompose(null, setTemperatureContract()));
        assertThrows(IllegalArgumentException.class,
            () -> decomposer.decompose(setTemperatureCandidate(), null));
    }

    @Test
    @DisplayName("Decomposer handles missing requestedBy user (emits PERMISSION claim with null subject)")
    void decomposerHandlesMissingUser() {
        ToolCallCandidate candidateWithoutUser = new ToolCallCandidate(
            "call-no-user",
            Optional.empty(),
            Optional.empty(),
            "set_temperature",
            Optional.of("http://example.org/smarthome#thermostat1"),
            Map.of("targetTemperature", "22.5"),
            Optional.empty(),  // no requestedBy
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        ToolCallClaimBatch batch = decomposer.decompose(candidateWithoutUser, setTemperatureContract());

        DecomposedClaim permissionClaim = findFirstByCategory(batch, ClaimCategory.PERMISSION);
        assertNotNull(permissionClaim);
        assertNull(permissionClaim.subject(),
            "PERMISSION claim should have null subject when no user provided");
        assertEquals("http://example.org/smarthome#ControlHVACPermission",
            permissionClaim.object().iri());
    }

    @Test
    @DisplayName("Decomposer handles missing targetEntity (skips CLASS_MEMBERSHIP, LOCATION, CAPABILITY)")
    void decomposerHandlesMissingTargetEntity() {
        ToolCallCandidate candidateWithoutTarget = new ToolCallCandidate(
            "call-no-target",
            Optional.empty(),
            Optional.empty(),
            "set_temperature",
            Optional.empty(),  // no targetEntity
            Map.of("targetTemperature", "22.5"),
            Optional.of("http://example.org/smarthome#user1"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        ToolCallClaimBatch batch = decomposer.decompose(candidateWithoutTarget, setTemperatureContract());

        // CLASS_MEMBERSHIP, LOCATION, CAPABILITY should be skipped
        assertNull(findFirstByCategory(batch, ClaimCategory.CLASS_MEMBERSHIP));
        assertNull(findFirstByCategory(batch, ClaimCategory.LOCATION));
        assertNull(findFirstByCategory(batch, ClaimCategory.CAPABILITY));
        // OPERATION_REQUIREMENTS, PERMISSION, DATATYPE should still be emitted
        assertNotNull(findFirstByCategory(batch, ClaimCategory.OPERATION_REQUIREMENTS));
        assertNotNull(findFirstByCategory(batch, ClaimCategory.PERMISSION));
        assertNotNull(findFirstByCategory(batch, ClaimCategory.DATATYPE));
    }

    @Test
    @DisplayName("Decomposer emits multiple CAPABILITY claims for multi-capability contract")
    void decomposerEmitsMultipleCapabilityClaims() {
        Map<String, Object> schema = Map.of("type", "object");
        ToolContract multiCapContract = new ToolContract(
            "set_temperature_and_humidity",
            schema,
            Optional.of("http://example.org/smarthome#HVACDevice"),
            List.of(
                "http://example.org/smarthome#TemperatureControl",
                "http://example.org/smarthome#HumidityControl"
            ),
            List.of(),
            List.of(),
            RiskLevel.MEDIUM,
            Optional.empty(),
            List.of()
        );
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-multi-cap",
            Optional.empty(),
            Optional.empty(),
            "set_temperature_and_humidity",
            Optional.of("http://example.org/smarthome#device1"),
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        ToolCallClaimBatch batch = decomposer.decompose(candidate, multiCapContract);

        long capabilityCount = batch.claims().stream()
            .filter(c -> c.category() == ClaimCategory.CAPABILITY)
            .count();
        assertEquals(2, capabilityCount,
            "Should emit 2 CAPABILITY claims for 2 required capabilities");

        long operationCount = batch.claims().stream()
            .filter(c -> c.category() == ClaimCategory.OPERATION_REQUIREMENTS)
            .count();
        assertEquals(2, operationCount,
            "Should emit 2 OPERATION_REQUIREMENTS claims for 2 required capabilities");
    }

    @Test
    @DisplayName("Decomposer handles string and integer datatypes")
    void decomposerHandlesStringAndIntegerDatatypes() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        properties.put("deviceName", nameProp);
        Map<String, Object> brightnessProp = new LinkedHashMap<>();
        brightnessProp.put("type", "integer");
        brightnessProp.put("minimum", 0);
        brightnessProp.put("maximum", 100);
        properties.put("brightness", brightnessProp);
        schema.put("properties", properties);

        ToolContract contract = new ToolContract(
            "set_lighting",
            schema,
            Optional.of("http://example.org/smarthome#LightingDevice"),
            List.of(),
            List.of(),
            List.of(),
            RiskLevel.LOW,
            Optional.empty(),
            List.of()
        );
        ToolCallCandidate candidate = new ToolCallCandidate(
            "call-mixed-datatype",
            Optional.empty(),
            Optional.empty(),
            "set_lighting",
            Optional.of("http://example.org/smarthome#light1"),
            Map.of("deviceName", "Kitchen Light", "brightness", "75"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        ToolCallClaimBatch batch = decomposer.decompose(candidate, contract);

        long datatypeCount = batch.claims().stream()
            .filter(c -> c.category() == ClaimCategory.DATATYPE)
            .count();
        assertEquals(2, datatypeCount, "Should emit 2 DATATYPE claims for 2 arguments");

        // Verify the string datatype
        DecomposedClaim stringClaim = batch.claims().stream()
            .filter(c -> c.category() == ClaimCategory.DATATYPE && "deviceName".equals(c.predicate()))
            .findFirst().orElse(null);
        assertNotNull(stringClaim);
        assertEquals("http://www.w3.org/2001/XMLSchema#string", stringClaim.subject().iri());

        // Verify the integer datatype
        DecomposedClaim intClaim = batch.claims().stream()
            .filter(c -> c.category() == ClaimCategory.DATATYPE && "brightness".equals(c.predicate()))
            .findFirst().orElse(null);
        assertNotNull(intClaim);
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", intClaim.subject().iri());
    }

    @Test
    @DisplayName("Batch verification sharing: single ToolCallClaimBatch → single ClaimBatchInput")
    void batchVerificationSharing() {
        // Per CL-002: the pipeline SHALL NOT invoke ClaimVerificationService.verify
        // once per claim. All claims share the same ontology load, reasoner, and
        // entity cache. The adapter produces a single ClaimBatchInput that
        // ClaimWorkflowService.verifyBatch consumes once.
        ToolCallClaimBatch batch = decomposer.decompose(setTemperatureCandidate(), setTemperatureContract());
        ToolCallClaimBatchAdapter adapter = new ToolCallClaimBatchAdapter(batch, "smart-home-ontology");

        ClaimBatchInput input = adapter.toClaimBatchInput();

        // The batch input is a single object with all claims — verifyBatch
        // is called ONCE on this object, not once per claim.
        assertNotNull(input);
        assertEquals(batch.claims().size(), input.claims().size());
        // All claims share the same ontologyId (set by the adapter)
        for (ClaimBatchInput.BatchClaim bc : input.claims()) {
            // ontologyId is not on BatchClaim itself but on the Claim
            // built from it via batchClaimToV03Claim in ClaimWorkflowService.
            // The batch input is the single shared input to verifyBatch.
            assertNotNull(bc.id());
            assertNotNull(bc.type());
        }
    }

    @Test
    @DisplayName("ClaimCategory roleName values match spec role set")
    void claimCategoryRoleNamesMatchSpec() {
        assertEquals("target_class", ClaimCategory.CLASS_MEMBERSHIP.roleName());
        assertEquals("location", ClaimCategory.LOCATION.roleName());
        assertEquals("capability", ClaimCategory.CAPABILITY.roleName());
        assertEquals("operation_capability", ClaimCategory.OPERATION_REQUIREMENTS.roleName());
        assertEquals("permission", ClaimCategory.PERMISSION.roleName());
        assertEquals("datatype", ClaimCategory.DATATYPE.roleName());
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private DecomposedClaim findFirstByCategory(ToolCallClaimBatch batch, ClaimCategory category) {
        return batch.claims().stream()
            .filter(c -> c.category() == category)
            .findFirst()
            .orElse(null);
    }
}
