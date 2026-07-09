package org.owl4agents.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.owl4agents.core.model.ClassExpression;

/**
 * Shared Gson factory that registers all required TypeAdapterFactory instances.
 *
 * Ensures consistent JSON serialization across all modules (CLI, MCP, benchmark, storage):
 * - OptionalTypeAdapterFactory: handles Optional fields, prevents InaccessibleObjectException on JDK 17+
 * - JsonNameEnumTypeAdapterFactory: serializes enums via jsonName() method (e.g. "supported" not "SUPPORTED")
 * - ClassExpressionAdapter: handles the sealed ClassExpression interface (v0.8.1 ISSUE-03)
 *
 * All modules should use {@link #createGson()} or {@link #builder()} instead of constructing
 * Gson instances manually, to avoid DEFECT-006/DEFECT-023 style crashes.
 */
public final class GsonFactory {

    private static final Gson INSTANCE = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
        .registerTypeAdapterFactory(new JsonNameEnumTypeAdapterFactory())
        .registerTypeAdapter(ClassExpression.class, new ClassExpressionAdapter())
        .create();

    /** Returns a fully-configured, thread-safe Gson instance. */
    public static Gson createGson() {
        return INSTANCE;
    }

    /** Returns a new GsonBuilder with all required factories pre-registered.
     *  Callers can add further configuration (e.g. setPrettyPrinting) before calling create(). */
    public static GsonBuilder builder() {
        return new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
            .registerTypeAdapterFactory(new JsonNameEnumTypeAdapterFactory())
            .registerTypeAdapter(ClassExpression.class, new ClassExpressionAdapter());
    }
}