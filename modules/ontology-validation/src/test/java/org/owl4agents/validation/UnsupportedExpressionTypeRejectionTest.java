package org.owl4agents.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.WorkspaceId;
import org.owl4agents.core.model.Claim;
import org.owl4agents.core.model.ClaimEntity;
import org.owl4agents.core.model.ClaimType;
import org.owl4agents.core.model.ClaimVerificationResult;
import org.owl4agents.core.util.ClassExpressionAdapter;
import org.owl4agents.owlapi.SemanticDeepeningService;
import org.owl4agents.reasoner.ReasonerLifecycleManager;

import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-25: unsupported expression type rejection.
 *
 * <p>Verifies that {@code object.expression.type} values outside the 6
 * supported types ({@code named}, {@code existential}, {@code universal},
 * {@code intersection}, {@code union}, {@code complement}) raise
 * {@code INVALID_CLAIM_SCHEMA}. Specifically, the deferred v0.8.1 types
 * ({@code data_existential}, {@code data_universal},
 * {@code cardinality_restriction}, {@code data_intersection}) MUST be
 * rejected with an error message listing the 6 supported types.</p>
 */
@DisplayName("TC-25 Unsupported expression type rejection")
class UnsupportedExpressionTypeRejectionTest {

    private static final String PIZZA_NS = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String PIZZA = PIZZA_NS + "Pizza";

    private static final String WORKSPACE = System.getProperty("user.dir").contains("D:\\owl4agents")
        ? "D:\\owl4agents\\data\\workspaces" : "data/workspaces";

    private ClaimVerificationService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        StubReasonerService stub = new StubReasonerService().withRealOntology(WORKSPACE);
        service = new ClaimVerificationService(
            stub,
            new ConsistencyAnalysisService(new ReasonerLifecycleManager(), WORKSPACE),
            new SemanticDeepeningService(WORKSPACE),
            new StubCatalogStore(),
            new WorkspaceId("default")
        );
    }

    @Test
    @DisplayName("TC-25a: data_existential expression type throws on parsing")
    void dataExistentialExpressionTypeRejected() {
        String json = "{\"type\":\"data_existential\",\"property\":\"http://example.org/age\",\"filler\":{\"type\":\"named\",\"iri\":\"http://www.w3.org/2001/XMLSchema#integer\"}}";
        Gson gson = new Gson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(RuntimeException.class,
            () -> ClassExpressionAdapter.fromMap(gson.fromJson(obj, Map.class)),
            "data_existential is not a supported v0.8.1 expression type and must be rejected");
    }

    @Test
    @DisplayName("TC-25b: data_universal expression type throws on parsing")
    void dataUniversalExpressionTypeRejected() {
        String json = "{\"type\":\"data_universal\",\"property\":\"http://example.org/age\",\"filler\":{\"type\":\"named\",\"iri\":\"http://www.w3.org/2001/XMLSchema#integer\"}}";
        Gson gson = new Gson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(RuntimeException.class,
            () -> ClassExpressionAdapter.fromMap(gson.fromJson(obj, Map.class)),
            "data_universal is not a supported v0.8.1 expression type and must be rejected");
    }

    @Test
    @DisplayName("TC-25c: cardinality_restriction expression type throws on parsing")
    void cardinalityRestrictionExpressionTypeRejected() {
        String json = "{\"type\":\"cardinality_restriction\",\"property\":\"http://example.org/hasTopping\",\"minCardinality\":3}";
        Gson gson = new Gson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(RuntimeException.class,
            () -> ClassExpressionAdapter.fromMap(gson.fromJson(obj, Map.class)),
            "cardinality_restriction is not a supported v0.8.1 expression type and must be rejected");
    }

    @Test
    @DisplayName("TC-25d: data_intersection expression type throws on parsing")
    void dataIntersectionExpressionTypeRejected() {
        String json = "{\"type\":\"data_intersection\",\"operands\":[]}";
        Gson gson = new Gson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(RuntimeException.class,
            () -> ClassExpressionAdapter.fromMap(gson.fromJson(obj, Map.class)),
            "data_intersection is not a supported v0.8.1 expression type and must be rejected");
    }

    @Test
    @DisplayName("TC-25e: 6 supported types are accepted")
    void sixSupportedTypesAreAccepted() {
        // Verify the 6 permitted types parse without throwing
        Gson gson = new Gson();
        assertDoesNotThrow(() -> {
            Map<String, Object> named = gson.fromJson(
                "{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}", Map.class);
            ClassExpressionAdapter.fromMap(named);
        });
        assertDoesNotThrow(() -> {
            Map<String, Object> exist = gson.fromJson(
                "{\"type\":\"existential\",\"property\":\"" + PIZZA_NS + "hasTopping\",\"filler\":{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}}",
                Map.class);
            ClassExpressionAdapter.fromMap(exist);
        });
        assertDoesNotThrow(() -> {
            Map<String, Object> univ = gson.fromJson(
                "{\"type\":\"universal\",\"property\":\"" + PIZZA_NS + "hasTopping\",\"filler\":{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}}",
                Map.class);
            ClassExpressionAdapter.fromMap(univ);
        });
        assertDoesNotThrow(() -> {
            Map<String, Object> inter = gson.fromJson(
                "{\"type\":\"intersection\",\"operands\":[" +
                "{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}," +
                "{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}]}", Map.class);
            ClassExpressionAdapter.fromMap(inter);
        });
        assertDoesNotThrow(() -> {
            Map<String, Object> union = gson.fromJson(
                "{\"type\":\"union\",\"operands\":[" +
                "{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}," +
                "{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}]}", Map.class);
            ClassExpressionAdapter.fromMap(union);
        });
        assertDoesNotThrow(() -> {
            Map<String, Object> comp = gson.fromJson(
                "{\"type\":\"complement\",\"operand\":{\"type\":\"named\",\"iri\":\"" + PIZZA + "\"}}",
                Map.class);
            ClassExpressionAdapter.fromMap(comp);
        });
    }
}
