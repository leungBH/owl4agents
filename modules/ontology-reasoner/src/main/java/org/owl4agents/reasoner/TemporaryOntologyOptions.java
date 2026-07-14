package org.owl4agents.reasoner;

/**
 * Options for {@link TemporaryOntologyFactory#create}.
 *
 * @param copyImportsClosure whether to copy the source ontology's imports
 *                           closure axioms into the temporary ontology
 * @param iriSuffix          suffix appended to the temporary ontology IRI
 *                           (for debugging; defaults to "-temp")
 */
public record TemporaryOntologyOptions(
    boolean copyImportsClosure,
    String iriSuffix
) {
    public static TemporaryOntologyOptions defaults() {
        return new TemporaryOntologyOptions(true, "-temp");
    }
}
