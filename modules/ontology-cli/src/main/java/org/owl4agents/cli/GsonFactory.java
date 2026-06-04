package org.owl4agents.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Factory for creating Gson instances properly configured for v0.3 CLI JSON output.
 * <ul>
 *   <li>Registers an Optional TypeAdapter to avoid InaccessibleObjectException on
 *       Optional.value under JDK 17+ module system (DEFECT-006).</li>
 *   <li>Registers a jsonName enum TypeAdapter so that Verdict, ClaimType, EvidenceKind,
 *       UnknownReason, and GraphScope serialize using their contract jsonName format
 *       (e.g. "supported" not "SUPPORTED").</li>
 * </ul>
 */
public final class GsonFactory {

    private static final Gson INSTANCE = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
        .registerTypeAdapterFactory(new JsonNameEnumTypeAdapterFactory())
        .create();

    private GsonFactory() {}

    /**
     * Returns a shared Gson instance configured for CLI --json output.
     * Handles Optional fields and jsonName enums correctly.
     */
    public static Gson createGson() {
        return INSTANCE;
    }
}