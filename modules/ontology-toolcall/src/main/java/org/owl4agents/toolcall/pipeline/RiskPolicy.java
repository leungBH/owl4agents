package org.owl4agents.toolcall.pipeline;

import org.owl4agents.toolcall.RiskLevel;
import org.owl4agents.toolcall.ToolContract;
import org.owl4agents.toolcall.ValidationDecision;

import java.util.Set;

/**
 * v0.8.7 PL-003 / D14: Risk policy and high-risk action blacklist.
 *
 * <p>Per design D14, the pipeline enforces two parallel defense-in-depth
 * mechanisms for high-risk actions:</p>
 * <ol>
 *   <li><strong>Blacklist</strong> — a hard-coded set of tool names that
 *       ALWAYS force {@link ValidationDecision#REQUEST_CONFIRMATION}
 *       regardless of contract metadata. The blacklist is non-configurable
 *       (design D14 alternative (c) rejected) so callers (including LLMs)
 *       cannot bypass it.</li>
 *   <li><strong>Contract riskLevel</strong> — when
 *       {@link ToolContract#riskLevel()} is {@link RiskLevel#HIGH} or
 *       {@link RiskLevel#CRITICAL}, stage 8 forces
 *       {@link ValidationDecision#REQUEST_CONFIRMATION} even if all
 *       stages 3-7 passed.</li>
 * </ol>
 *
 * <p>The blacklist contains the 6 physical-safety actions mandated by
 * design D14 / work order PL-003:</p>
 * <ul>
 *   <li>{@code unlock_door}</li>
 *   <li>{@code disable_alarm}</li>
 *   <li>{@code turn_off_smoke_detector}</li>
 *   <li>{@code activate_high_heat_device}</li>
 *   <li>{@code open_garage_door}</li>
 *   <li>{@code modify_security_camera}</li>
 * </ul>
 *
 * <p>The {@link #isHighRisk(String, ToolContract)} method is the single
 * authoritative check used by both the short-circuit override (stage 2
 * post-load) and stage 8 (risk evaluation).</p>
 */
public final class RiskPolicy {

    /**
     * The hard-coded high-risk action blacklist. Per design D14 alternative
     * (a) rejected, this list is NOT configurable by callers — the safety
     * policy must be enforced at runtime and cannot be overridden by LLMs.
     */
    public static final Set<String> HIGH_RISK_ACTIONS = Set.of(
        "unlock_door",
        "disable_alarm",
        "turn_off_smoke_detector",
        "activate_high_heat_device",
        "open_garage_door",
        "modify_security_camera"
    );

    private RiskPolicy() {
        // utility class; no instances
    }

    /**
     * Whether the given tool name is in the high-risk blacklist.
     */
    public static boolean isBlacklisted(String toolName) {
        return toolName != null && HIGH_RISK_ACTIONS.contains(toolName);
    }

    /**
     * Whether the contract's risk level is HIGH or CRITICAL.
     */
    public static boolean isHighRiskLevel(ToolContract contract) {
        return contract != null && contract.riskLevel().isHighRisk();
    }

    /**
     * Whether the candidate action is high-risk per EITHER mechanism
     * (blacklist OR contract riskLevel). This is the single authoritative
     * check used by both the short-circuit override (immediately after
     * stage 2 loads the contract) and stage 8 (risk evaluation).
     *
     * <p>Per spec "Non-blacklisted high-risk via contract", a tool not in
     * the blacklist but with {@code riskLevel=high} still forces
     * {@link ValidationDecision#REQUEST_CONFIRMATION} — the contract's
     * riskLevel is a parallel defense-in-depth mechanism.</p>
     */
    public static boolean isHighRisk(String toolName, ToolContract contract) {
        if (isBlacklisted(toolName)) {
            return true;
        }
        return isHighRiskLevel(contract);
    }

    /**
     * The effective risk level for the candidate. Returns
     * {@link RiskLevel#HIGH} when the tool name is blacklisted (even if
     * the contract's riskLevel is lower — the blacklist is authoritative).
     * Otherwise returns the contract's riskLevel (or {@link RiskLevel#LOW}
     * when no contract is available, e.g. before stage 2 completes).
     */
    public static RiskLevel effectiveRiskLevel(String toolName, ToolContract contract) {
        if (isBlacklisted(toolName)) {
            // If the contract also marks it CRITICAL, honor CRITICAL;
            // otherwise the blacklist forces at least HIGH.
            if (contract != null && contract.riskLevel() == RiskLevel.CRITICAL) {
                return RiskLevel.CRITICAL;
            }
            return RiskLevel.HIGH;
        }
        if (contract != null) {
            return contract.riskLevel();
        }
        return RiskLevel.LOW;
    }

    /**
     * The decision forced by the risk policy. Per design D14, when the
     * candidate is high-risk, the decision is forced to
     * {@link ValidationDecision#REQUEST_CONFIRMATION} regardless of
     * whether stages 3-7 produced a unique repair or all passed.
     *
     * <p>This method does NOT apply the short-circuit override (that is
     * the {@code PipelineContext}'s responsibility via
     * {@link PipelineContext#resolvedShortCircuitDecision()}). It is the
     * stage 8 / stage 9 entry check.</p>
     *
     * @param toolName the candidate's tool name
     * @param contract the loaded tool contract (may be null before stage 2)
     * @return {@link ValidationDecision#REQUEST_CONFIRMATION} when
     *         high-risk; otherwise {@link ValidationDecision#EXECUTE}
     *         (the neutral decision — the caller will combine this with
     *         other stage results to compute the final decision)
     */
    public static ValidationDecision forcedDecision(String toolName, ToolContract contract) {
        return isHighRisk(toolName, contract)
            ? ValidationDecision.REQUEST_CONFIRMATION
            : ValidationDecision.EXECUTE;
    }
}
