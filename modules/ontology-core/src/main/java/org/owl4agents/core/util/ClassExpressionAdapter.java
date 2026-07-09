package org.owl4agents.core.util;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import org.owl4agents.core.model.ClassExpression;
import org.owl4agents.core.model.NamedClass;
import org.owl4agents.core.model.ObjectAllValuesFrom;
import org.owl4agents.core.model.ObjectComplementOf;
import org.owl4agents.core.model.ObjectIntersectionOf;
import org.owl4agents.core.model.ObjectSomeValuesFrom;
import org.owl4agents.core.model.ObjectUnionOf;

/**
 * Gson adapter for the sealed {@link ClassExpression} interface
 * (v0.8.1 ISSUE-03). Reads a JSON object with a {@code type} discriminator
 * and dispatches to the corresponding record constructor.
 *
 * <p>Supported {@code type} values:</p>
 * <ul>
 *   <li>{@code "named"}        → {@link NamedClass} (requires {@code iri})</li>
 *   <li>{@code "existential"}  → {@link ObjectSomeValuesFrom} (requires {@code property}, {@code filler})</li>
 *   <li>{@code "universal"}    → {@link ObjectAllValuesFrom} (requires {@code property}, {@code filler})</li>
 *   <li>{@code "intersection"} → {@link ObjectIntersectionOf} (requires {@code operands} with ≥2 elements)</li>
 *   <li>{@code "union"}        → {@link ObjectUnionOf} (requires {@code operands} with ≥2 elements)</li>
 *   <li>{@code "complement"}   → {@link ObjectComplementOf} (requires {@code operand})</li>
 * </ul>
 *
 * <p>Any other {@code type} value (including the explicitly-unsupported
 * data-side and cardinality constructors such as {@code data_existential},
 * {@code data_universal}, {@code cardinality_restriction},
 * {@code data_intersection}) triggers a {@link JsonParseException} mapped
 * to {@code INVALID_CLAIM_SCHEMA} with an error message listing the six
 * supported types. The nested {@code ClassExpression} fields ({@code filler},
 * {@code operand}, {@code operands[]}) are themselves read through this
 * adapter, so malformed nested expressions surface the same error.</p>
 *
 * <p>This class implements both {@link JsonDeserializer} and
 * {@link JsonSerializer} so it can be registered with
 * {@code GsonBuilder.registerTypeAdapter(ClassExpression.class, new ClassExpressionAdapter())}.
 * A static {@link #fromMap(Map)} helper is also provided for callers that
 * parse JSON into a {@link Map} first (e.g., the CLI {@code ClaimParser},
 * which deliberately avoids Gson record deserialization to handle
 * {@code Optional} fields).</p>
 */
public final class ClassExpressionAdapter
        implements JsonDeserializer<ClassExpression>, JsonSerializer<ClassExpression> {

    private static final String SUPPORTED_TYPES =
        "named, existential, universal, intersection, union, complement";

    @Override
    public ClassExpression deserialize(JsonElement json, Type typeOfT,
                                       JsonDeserializationContext context)
            throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return null;
        }
        if (!json.isJsonObject()) {
            throw new JsonParseException(
                "ClassExpression must be a JSON object with a 'type' field, got: "
                + json);
        }
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("type") || obj.get("type").isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression is missing the required 'type' field. "
                + "Supported types: " + SUPPORTED_TYPES);
        }
        String type = obj.get("type").getAsString();
        return switch (type) {
            case "named"        -> readNamed(obj);
            case "existential"  -> readExistential(obj, context);
            case "universal"    -> readUniversal(obj, context);
            case "intersection" -> readIntersection(obj, context, "intersection");
            case "union"        -> readIntersection(obj, context, "union");
            case "complement"   -> readComplement(obj, context);
            default             -> throw new JsonParseException(
                "Unsupported ClassExpression type '" + type + "'. "
                + "Supported types: " + SUPPORTED_TYPES
                + " (data-property and cardinality constructors are deferred to v0.9+)");
        };
    }

    @Override
    public JsonElement serialize(ClassExpression src, Type typeOfSrc,
                                 JsonSerializationContext context) {
        if (src == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject obj = new JsonObject();
        if (src instanceof NamedClass nc) {
            obj.addProperty("type", "named");
            obj.addProperty("iri", nc.iri());
        } else if (src instanceof ObjectSomeValuesFrom ex) {
            obj.addProperty("type", "existential");
            obj.addProperty("property", ex.propertyIRI());
            obj.add("filler", context.serialize(ex.filler(), ClassExpression.class));
        } else if (src instanceof ObjectAllValuesFrom un) {
            obj.addProperty("type", "universal");
            obj.addProperty("property", un.propertyIRI());
            obj.add("filler", context.serialize(un.filler(), ClassExpression.class));
        } else if (src instanceof ObjectIntersectionOf inter) {
            obj.addProperty("type", "intersection");
            obj.add("operands", serializeOperands(inter.operands(), context));
        } else if (src instanceof ObjectUnionOf un) {
            obj.addProperty("type", "union");
            obj.add("operands", serializeOperands(un.operands(), context));
        } else if (src instanceof ObjectComplementOf comp) {
            obj.addProperty("type", "complement");
            obj.add("operand", context.serialize(comp.operand(), ClassExpression.class));
        } else {
            throw new JsonParseException("Unknown ClassExpression subtype: "
                + src.getClass().getName());
        }
        return obj;
    }

    private JsonArray serializeOperands(List<ClassExpression> operands,
                                        JsonSerializationContext context) {
        JsonArray arr = new JsonArray();
        for (ClassExpression operand : operands) {
            arr.add(context.serialize(operand, ClassExpression.class));
        }
        return arr;
    }

    private static NamedClass readNamed(JsonObject obj) {
        if (!obj.has("iri") || obj.get("iri").isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression type 'named' requires a non-null 'iri' field");
        }
        return new NamedClass(obj.get("iri").getAsString());
    }

    private static ObjectSomeValuesFrom readExistential(JsonObject obj,
                                                       JsonDeserializationContext ctx) {
        String property = readRequiredString(obj, "property", "existential");
        if (!obj.has("filler") || obj.get("filler").isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression type 'existential' requires a non-null 'filler' field");
        }
        ClassExpression filler = ctx.deserialize(obj.get("filler"), ClassExpression.class);
        return new ObjectSomeValuesFrom(property, filler);
    }

    private static ObjectAllValuesFrom readUniversal(JsonObject obj,
                                                     JsonDeserializationContext ctx) {
        String property = readRequiredString(obj, "property", "universal");
        if (!obj.has("filler") || obj.get("filler").isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression type 'universal' requires a non-null 'filler' field");
        }
        ClassExpression filler = ctx.deserialize(obj.get("filler"), ClassExpression.class);
        return new ObjectAllValuesFrom(property, filler);
    }

    private static ClassExpression readIntersection(JsonObject obj,
                                                    JsonDeserializationContext ctx,
                                                    String label) {
        if (!obj.has("operands") || !obj.get("operands").isJsonArray()) {
            throw new JsonParseException(
                "ClassExpression type '" + label + "' requires an 'operands' array "
                + "with at least 2 elements");
        }
        List<ClassExpression> operands = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("operands")) {
            operands.add(ctx.deserialize(el, ClassExpression.class));
        }
        if (operands.size() < 2) {
            throw new JsonParseException(
                "ClassExpression type '" + label + "' requires at least 2 operands (got "
                + operands.size() + ")");
        }
        return "intersection".equals(label)
            ? new ObjectIntersectionOf(operands)
            : new ObjectUnionOf(operands);
    }

    private static ObjectComplementOf readComplement(JsonObject obj,
                                                     JsonDeserializationContext ctx) {
        if (!obj.has("operand") || obj.get("operand").isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression type 'complement' requires a non-null 'operand' field");
        }
        ClassExpression operand = ctx.deserialize(obj.get("operand"), ClassExpression.class);
        return new ObjectComplementOf(operand);
    }

    private static String readRequiredString(JsonObject obj, String field, String type) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            throw new JsonParseException(
                "ClassExpression type '" + type + "' requires a non-null '" + field + "' field");
        }
        return obj.get(field).getAsString();
    }

    /**
     * Helper for callers that want to construct a {@link ClassExpression} from
     * a generic {@link Map} (e.g., parsed manually in {@code ClaimParser}).
     * Unknown {@code type} values throw an {@link IllegalArgumentException}
     * listing the six supported types — this is the non-Gson path used by the
     * CLI {@code ClaimParser}, which deliberately avoids Gson's record
     * deserialization to handle {@code Optional} fields.
     *
     * @param raw a {@link Map} with at minimum a {@code type} field plus the
     *            fields required by that type, or {@code null}
     * @return the corresponding {@link ClassExpression}, or {@code null} when
     *         {@code raw} is null or does not contain a {@code type} field
     * @throws IllegalArgumentException when the {@code type} is not one of
     *         the six supported values
     */
    public static ClassExpression fromMap(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Object typeObj = raw.get("type");
        if (typeObj == null) {
            return null;
        }
        String type = typeObj.toString();
        return switch (type) {
            case "named" -> {
                Object iriObj = raw.get("iri");
                if (iriObj == null) {
                    throw new IllegalArgumentException(
                        "ClassExpression type 'named' requires 'iri' field");
                }
                yield new NamedClass(iriObj.toString());
            }
            case "existential" -> {
                String property = requireString(raw, "property", "existential");
                ClassExpression filler = fromMap(castMap(raw.get("filler")));
                if (filler == null) {
                    throw new IllegalArgumentException(
                        "ClassExpression type 'existential' requires 'filler' field");
                }
                yield new ObjectSomeValuesFrom(property, filler);
            }
            case "universal" -> {
                String property = requireString(raw, "property", "universal");
                ClassExpression filler = fromMap(castMap(raw.get("filler")));
                if (filler == null) {
                    throw new IllegalArgumentException(
                        "ClassExpression type 'universal' requires 'filler' field");
                }
                yield new ObjectAllValuesFrom(property, filler);
            }
            case "intersection" -> {
                List<ClassExpression> operands = operandList(raw, "intersection");
                yield new ObjectIntersectionOf(operands);
            }
            case "union" -> {
                List<ClassExpression> operands = operandList(raw, "union");
                yield new ObjectUnionOf(operands);
            }
            case "complement" -> {
                ClassExpression operand = fromMap(castMap(raw.get("operand")));
                if (operand == null) {
                    throw new IllegalArgumentException(
                        "ClassExpression type 'complement' requires 'operand' field");
                }
                yield new ObjectComplementOf(operand);
            }
            default -> throw new IllegalArgumentException(
                "Unsupported ClassExpression type '" + type + "'. "
                + "Supported types: " + SUPPORTED_TYPES
                + " (data-property and cardinality constructors are deferred to v0.9+)");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ClassExpression> operandList(Map<String, Object> raw, String label) {
        Object arr = raw.get("operands");
        if (!(arr instanceof List)) {
            throw new IllegalArgumentException(
                "ClassExpression type '" + label + "' requires 'operands' array "
                + "with at least 2 elements");
        }
        List<ClassExpression> result = new ArrayList<>();
        for (Object el : (List<Object>) arr) {
            ClassExpression ce = fromMap(castMap(el));
            if (ce != null) {
                result.add(ce);
            }
        }
        if (result.size() < 2) {
            throw new IllegalArgumentException(
                "ClassExpression type '" + label + "' requires at least 2 operands (got "
                + result.size() + ")");
        }
        return result;
    }

    private static String requireString(Map<String, Object> raw, String field, String type) {
        Object v = raw.get(field);
        if (v == null) {
            throw new IllegalArgumentException(
                "ClassExpression type '" + type + "' requires '" + field + "' field");
        }
        return v.toString();
    }
}
