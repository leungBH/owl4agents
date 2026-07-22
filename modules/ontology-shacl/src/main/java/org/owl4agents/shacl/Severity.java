package org.owl4agents.shacl;

/**
 * v0.8.7 SHACL violation severity (D8).
 *
 * <p>Maps to SHACL's three severity levels ({@code sh:Violation},
 * {@code sh:Warning}, {@code sh:Info}). The JSON serialization uses the
 * lowercase form ({@code "violation"} / {@code "warning"} / {@code "info"})
 * via {@link #jsonName()} so MCP, CLI, and Java API outputs are
 * byte-for-byte identical.</p>
 */
public enum Severity {
    Violation("violation"),
    Warning("warning"),
    Info("info");

    private final String jsonName;

    Severity(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /**
     * Parse a SHACL severity IRI (e.g.
     * {@code "http://www.w3.org/ns/shacl#Warning"}) into a {@link Severity}.
     * Defaults to {@link #Violation} for the standard sh:Violation IRI or
     * any unrecognized value (defensive: SHACL Core defaults to Violation
     * when no {@code sh:severity} is declared on a shape).
     */
    public static Severity fromIri(String iri) {
        if (iri == null) return Violation;
        if (iri.endsWith("#Warning") || iri.endsWith("/Warning")) return Warning;
        if (iri.endsWith("#Info") || iri.endsWith("/Info")) return Info;
        return Violation;
    }
}
