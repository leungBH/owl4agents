package org.owl4agents.core.util;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Gson TypeAdapterFactory that serializes enums using their {@code jsonName()} method
 * when available, instead of the default Java {@code name()}.
 *
 * This ensures JSON output matches the contract format
 * (e.g. "supported" not "SUPPORTED", "insufficient_axioms" not "INSUFFICIENT_AXIOMS").
 *
 * Originally in ontology-cli. Moved to ontology-core/util so all modules
 * (CLI, MCP, benchmark) can share it.
 */
public class JsonNameEnumTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<? super T> rawType = type.getRawType();
        if (!rawType.isEnum()) {
            return null;
        }

        Method jsonNameMethod = findJsonNameMethod(rawType);
        if (jsonNameMethod == null) {
            return null; // enum without jsonName — use Gson default
        }

        @SuppressWarnings("unchecked")
        TypeAdapter<T> adapter = (TypeAdapter<T>) new JsonNameEnumTypeAdapter<>(rawType, jsonNameMethod);
        return adapter;
    }

    private static Method findJsonNameMethod(Class<?> rawType) {
        try {
            return rawType.getMethod("jsonName");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static class JsonNameEnumTypeAdapter<E> extends TypeAdapter<E> {

        private final Class<E> enumClass;
        private final Method jsonNameMethod;

        JsonNameEnumTypeAdapter(Class<E> enumClass, Method jsonNameMethod) {
            this.enumClass = enumClass;
            this.jsonNameMethod = jsonNameMethod;
        }

        @Override
        public void write(JsonWriter out, E value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            try {
                String name = (String) jsonNameMethod.invoke(value);
                out.value(name);
            } catch (Exception e) {
                // Fallback to default enum name if jsonName() fails
                out.value(((Enum<?>) value).name());
            }
        }

        @Override
        public E read(JsonReader in) throws IOException {
            String value = in.nextString();
            if (value == null) {
                return null;
            }
            // Try jsonName-based lookup first
            for (E constant : enumClass.getEnumConstants()) {
                try {
                    String name = (String) jsonNameMethod.invoke(constant);
                    if (name.equals(value)) {
                        return constant;
                    }
                } catch (Exception ignored) {}
            }
            // Fallback to Java name-based lookup
            for (E constant : enumClass.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(value)) {
                    return constant;
                }
            }
            return null;
        }
    }
}