package org.owl4agents.core.model;

/**
 * A single binding value in a SPARQL result.
 */
public record BindingValue(
    String value,
    String datatype,
    String type
) {
    public static final String TYPE_URI = "uri";
    public static final String TYPE_LITERAL = "literal";
    public static final String TYPE_BLANK = "blank";
}