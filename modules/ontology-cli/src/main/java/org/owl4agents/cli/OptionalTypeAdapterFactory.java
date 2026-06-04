package org.owl4agents.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Gson TypeAdapterFactory that handles {@link Optional} fields.
 * Serializes Optional.empty() as null, Optional.of(value) as the inner value.
 * Deserializes null as Optional.empty(), non-null as Optional.ofNullable(value).
 *
 * Required because JDK 17+ module system blocks reflective access to
 * Optional.value, causing InaccessibleObjectException with default Gson.
 */
public class OptionalTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (!Optional.class.isAssignableFrom(type.getRawType())) {
            return null;
        }

        Type innerType = extractInnerType(type.getType());
        TypeAdapter<?> innerAdapter = gson.getAdapter(TypeToken.get(innerType));

        @SuppressWarnings("unchecked")
        TypeAdapter<T> result = (TypeAdapter<T>) new OptionalTypeAdapter<>(innerAdapter);
        return result;
    }

    private static Type extractInnerType(Type type) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static class OptionalTypeAdapter<E> extends TypeAdapter<Optional<E>> {

        private final TypeAdapter<E> innerAdapter;

        OptionalTypeAdapter(TypeAdapter<E> innerAdapter) {
            this.innerAdapter = innerAdapter;
        }

        @Override
        public void write(JsonWriter out, Optional<E> value) throws IOException {
            if (value == null || value.isEmpty()) {
                out.nullValue();
            } else {
                innerAdapter.write(out, value.get());
            }
        }

        @Override
        public Optional<E> read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return Optional.empty();
            } else {
                return Optional.ofNullable(innerAdapter.read(in));
            }
        }
    }
}