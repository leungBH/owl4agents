package org.owl4agents.shacl;

import java.time.Duration;
import java.util.Optional;

/**
 * v0.8.7 SH-002 / D6: Options bag for {@link ShaclValidationService}.
 *
 * <p>Defaults (per design D6):</p>
 * <ul>
 *   <li>{@code includeWarnings} = {@code true}</li>
 *   <li>{@code includeInfos} = {@code false}</li>
 *   <li>{@code timeout} = 30s (SPARQL constraint timeout)</li>
 *   <li>{@code reasoner} = empty (reserved for future inferred-overlay SHACL)</li>
 * </ul>
 *
 * @param includeWarnings include {@code sh:Warning} results in the report's
 *                        {@code warnings} list (always included in {@code conforms}
 *                        calculation as non-failing)
 * @param includeInfos    include {@code sh:Info} results in the report's
 *                        {@code infos} list
 * @param timeout         SPARQL constraint timeout; exceeded timeout surfaces
 *                        as {@code SHACL_TIMEOUT}
 * @param reasoner        reserved; v0.8.7 does not use this field
 */
public record ShaclValidationOptions(
    boolean includeWarnings,
    boolean includeInfos,
    Duration timeout,
    Optional<String> reasoner
) {
    /** Default 30s timeout per design D6. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public ShaclValidationOptions {
        if (timeout == null) timeout = DEFAULT_TIMEOUT;
        if (timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (reasoner == null) reasoner = Optional.empty();
    }

    /**
     * Default options: warnings on, infos off, 30s timeout, no reasoner.
     */
    public static ShaclValidationOptions defaults() {
        return new ShaclValidationOptions(true, false, DEFAULT_TIMEOUT, Optional.empty());
    }

    /**
     * Build options from a JSON-like map (used by the MCP tool's
     * {@code options} parameter and the CLI's {@code --options} flag).
     * Recognized keys: {@code includeWarnings} (bool), {@code includeInfos}
     * (bool), {@code timeout} (number of seconds, or ISO-8601 duration).
     * Unrecognized keys are ignored. Missing keys fall back to defaults.
     */
    public static ShaclValidationOptions fromMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return defaults();
        boolean includeWarnings = map.containsKey("includeWarnings")
            ? Boolean.TRUE.equals(map.get("includeWarnings"))
            : true;
        boolean includeInfos = Boolean.TRUE.equals(map.get("includeInfos"));
        Duration timeout = DEFAULT_TIMEOUT;
        Object timeoutRaw = map.get("timeout");
        if (timeoutRaw instanceof Number n) {
            // Per spec: timeout number is interpreted as seconds.
            timeout = Duration.ofSeconds(n.longValue());
        } else if (timeoutRaw instanceof String s && !s.isBlank()) {
            try {
                timeout = Duration.parse(s);
            } catch (java.time.format.DateTimeParseException ex) {
                try {
                    timeout = Duration.ofSeconds(Long.parseLong(s.trim()));
                } catch (NumberFormatException nfe) {
                    timeout = DEFAULT_TIMEOUT;
                }
            }
        }
        return new ShaclValidationOptions(includeWarnings, includeInfos, timeout, Optional.empty());
    }
}
