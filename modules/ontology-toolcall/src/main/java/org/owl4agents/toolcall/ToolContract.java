package org.owl4agents.toolcall;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v0.8.7 TC-002: Tool contract data model.
 *
 * <p>A {@code ToolContract} is more than a JSON Schema: it captures both
 * structural obligations (the {@code inputSchema}) and semantic
 * obligations (target class, required capabilities, required states,
 * effects, risk level, required permission, SHACL ShapeSets). The
 * pipeline uses a contract to drive stage 3 (JSON Schema), stage 5
 * (claim decomposition), stage 7 (SHACL), and stage 8 (risk
 * evaluation).</p>
 *
 * <p>Loaded by {@link ToolContractRegistry} from
 * {@code ~/.owl4agents/contracts/<toolName>.json}. Nullable / list
 * fields use defensive defaults in the canonical constructor so a
 * minimal contract ({@code toolName} + {@code inputSchema}) is
 * valid.</p>
 *
 * <p>JSON serialization (MCP, CLI, Java API) is byte-for-byte
 * identical via {@link ToolCallJsonSerializer#contractToMap(ToolContract)}.</p>
 *
 * @param toolName           unique tool name (e.g. {@code "set_temperature"})
 * @param inputSchema        JSON Schema object describing the tool's
 *                           arguments; serialized as a JSON-compatible
 *                           {@code Map<String, Object>}
 * @param targetClass       OWL class IRI the target entity must belong
 *                           to, or empty for tools that do not target an
 *                           entity (e.g. {@code list_devices})
 * @param requiredCapabilities list of capability IRIs the target entity
 *                           must have (inferred or asserted)
 * @param requiredStates    list of state predicates that must hold on
 *                           the target entity
 * @param effects           list of effect descriptions (e.g.
 *                           {@code "changesState(thermostat, targetTemperature)"})
 * @param riskLevel         risk classification (low/medium/high/critical)
 * @param requiredPermission permission class IRI the requesting user must
 *                           belong to, or empty
 * @param shapeSetIds       list of registered SHACL ShapeSet IDs to apply
 *                           at stage 7
 */
public record ToolContract(
    String toolName,
    Map<String, Object> inputSchema,
    Optional<String> targetClass,
    List<String> requiredCapabilities,
    List<String> requiredStates,
    List<String> effects,
    RiskLevel riskLevel,
    Optional<String> requiredPermission,
    List<String> shapeSetIds
) {
    public ToolContract {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("ToolContract.toolName must not be blank");
        }
        if (inputSchema == null) {
            inputSchema = Map.of();
        } else {
            // Defensive copy so external mutation does not leak in.
            inputSchema = Map.copyOf(inputSchema);
        }
        if (targetClass == null) targetClass = Optional.empty();
        if (requiredCapabilities == null) requiredCapabilities = List.of();
        else requiredCapabilities = List.copyOf(requiredCapabilities);
        if (requiredStates == null) requiredStates = List.of();
        else requiredStates = List.copyOf(requiredStates);
        if (effects == null) effects = List.of();
        else effects = List.copyOf(effects);
        if (riskLevel == null) riskLevel = RiskLevel.LOW;
        if (requiredPermission == null) requiredPermission = Optional.empty();
        if (shapeSetIds == null) shapeSetIds = List.of();
        else shapeSetIds = List.copyOf(shapeSetIds);
    }

    /**
     * Convenience constructor for a minimal contract (toolName + inputSchema)
     * with low risk and no semantic obligations.
     */
    public ToolContract(String toolName, Map<String, Object> inputSchema) {
        this(toolName, inputSchema, Optional.empty(),
            List.of(), List.of(), List.of(),
            RiskLevel.LOW, Optional.empty(), List.of());
    }

    /**
     * Whether this contract requires SHACL validation (non-empty
     * {@code shapeSetIds}). The pipeline uses this to short-circuit
     * stage 7 when no shapes apply.
     */
    public boolean requiresShacl() {
        return !shapeSetIds.isEmpty();
    }

    /**
     * Whether this contract targets a specific entity. When false,
     * the pipeline skips target-class claim decomposition (stage 5)
     * per the "Contract with no target class" scenario.
     */
    public boolean targetsEntity() {
        return targetClass.isPresent()
            || !requiredCapabilities.isEmpty()
            || !requiredStates.isEmpty();
    }
}
