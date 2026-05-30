package org.owl4agents.core.model;

/**
 * Base interface for all entity context results.
 */
public interface EntityContext {
    String iri();
    String prefixedName();
    String label();
    String comment();
}