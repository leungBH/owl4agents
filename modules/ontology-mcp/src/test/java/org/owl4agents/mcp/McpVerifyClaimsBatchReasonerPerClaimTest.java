package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 1.7 / task 1.7: verify that when the resolved reasoner
 * is {@code "auto"} (i.e. no top-level {@code reasoner} and no
 * {@code options.reasoner}), each claim's existing {@code reasoner}
 * value is preserved as-is.
 *
 * <p>This is the third branch of the D6 priority contract: when neither
 * the top-level nor options specifies a reasoner, per-claim values take
 * over (and a missing per-claim value falls back to the runtime default
 * inside {@code parseClaimFromMcpArgs}).</p>
 *
 * <p>Tested via reflection on {@code resolveReasonerFromArgs} and
 * {@code parseClaimsBatchFromArgs}.</p>
 */
@DisplayName("v0.8.6 §1.7: top-level reasoner=auto preserves per-claim reasoner")
class McpVerifyClaimsBatchReasonerPerClaimTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("batch-perclaim-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    private String invokeResolveReasoner(Map<String, Object> args) throws Exception {
        Method method = McpServerAdapter.class.getDeclaredMethod(
            "resolveReasonerFromArgs", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(createAdapter(), args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeParseClaimsBatch(
        Map<String, Object> args, String reasonerOverride) throws Exception {
        Method method = McpServerAdapter.class.getDeclaredMethod(
            "parseClaimsBatchFromArgs", Map.class, String.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(createAdapter(), args, reasonerOverride);
    }

    private Map<String, Object> baseClaimMap(String id, String perClaimReasoner) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("claimId", id);
        claim.put("type", "SUBCLASS");
        claim.put("ontologyId", "pizza");
        if (perClaimReasoner != null) {
            claim.put("reasoner", perClaimReasoner);
        }
        Map<String, Object> subject = new HashMap<>();
        subject.put("kind", "class");
        subject.put("iri", "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita");
        claim.put("subject", subject);
        claim.put("predicate", "http://www.w3.org/2000/01/rdf-schema#subClassOf");
        Map<String, Object> object = new HashMap<>();
        object.put("kind", "class");
        object.put("iri", "http://www.co-ode.org/ontologies/pizza/pizza.owl#NamedPizza");
        claim.put("object", object);
        return claim;
    }

    private Map<String, Object> batchWithClaims(Map<String, Object>... claims) {
        Map<String, Object> batch = new HashMap<>();
        batch.put("claims", List.of(claims));
        Map<String, Object> args = new HashMap<>();
        args.put("ontology_id", "pizza");
        args.put("claims", batch);
        return args;
    }

    @Nested
    @DisplayName("Reflection: resolveReasonerFromArgs returns 'auto' when no override")
    class ResolveReasonerAutoTests {

        @Test
        @DisplayName("empty args resolves to 'auto'")
        void emptyArgs() throws Exception {
            Map<String, Object> args = new HashMap<>();
            String resolved = invokeResolveReasoner(args);
            assertEquals("auto", resolved);
        }

        @Test
        @DisplayName("args with only ontology_id resolves to 'auto'")
        void argsWithoutReasoner() throws Exception {
            Map<String, Object> args = new HashMap<>();
            args.put("ontology_id", "pizza");
            String resolved = invokeResolveReasoner(args);
            assertEquals("auto", resolved);
        }

        @Test
        @DisplayName("args.reasoner=auto resolves to 'auto'")
        void topLevelAuto() throws Exception {
            Map<String, Object> args = new HashMap<>();
            args.put("reasoner", "auto");
            String resolved = invokeResolveReasoner(args);
            assertEquals("auto", resolved);
        }

        @Test
        @DisplayName("options.reasoner=auto resolves to 'auto'")
        void optionsAuto() throws Exception {
            Map<String, Object> args = new HashMap<>();
            Map<String, Object> options = new HashMap<>();
            options.put("reasoner", "auto");
            args.put("options", options);
            String resolved = invokeResolveReasoner(args);
            assertEquals("auto", resolved);
        }
    }

    @Nested
    @DisplayName("Reflection: parseClaimsBatchFromArgs with override=auto preserves per-claim")
    class ParseClaimsBatchPreservesTests {

        @Test
        @DisplayName("override=auto preserves per-claim reasoner=elk")
        void preservesPerClaimElk() throws Exception {
            Map<String, Object> claim = baseClaimMap("c1", "elk");
            Map<String, Object> args = batchWithClaims(claim);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, "auto");
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(1, claimsList.size());
            assertEquals("elk", claimsList.get(0).get("reasoner"),
                "per-claim reasoner=elk must be preserved when override=auto");
        }

        @Test
        @DisplayName("override=auto preserves heterogeneous per-claim reasoners")
        void preservesHeterogeneousPerClaim() throws Exception {
            Map<String, Object> claim1 = baseClaimMap("c1", "elk");
            Map<String, Object> claim2 = baseClaimMap("c2", "hermit");
            Map<String, Object> claim3 = baseClaimMap("c3", "openllet");
            Map<String, Object> args = batchWithClaims(claim1, claim2, claim3);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, "auto");
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(3, claimsList.size());
            assertEquals("elk", claimsList.get(0).get("reasoner"),
                "claim1.reasoner=elk must be preserved");
            assertEquals("hermit", claimsList.get(1).get("reasoner"),
                "claim2.reasoner=hermit must be preserved");
            assertEquals("openllet", claimsList.get(2).get("reasoner"),
                "claim3.reasoner=openllet must be preserved");
        }

        @Test
        @DisplayName("override=auto does NOT inject a 'reasoner' field when claim has none")
        void overrideAutoDoesNotInjectWhenAbsent() throws Exception {
            Map<String, Object> claim = baseClaimMap("c1", null);
            Map<String, Object> args = batchWithClaims(claim);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, "auto");
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(1, claimsList.size());
            assertFalse(claimsList.get(0).containsKey("reasoner"),
                "override=auto must NOT inject a 'reasoner' field into claims that don't have one");
        }

        @Test
        @DisplayName("override=null is treated the same as 'auto' (preserves per-claim)")
        void overrideNullPreservesPerClaim() throws Exception {
            // Null safe-guard: the helper's contract says null override
            // preserves per-claim values (same as "auto").
            Map<String, Object> claim = baseClaimMap("c1", "elk");
            Map<String, Object> args = batchWithClaims(claim);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, null);
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(1, claimsList.size());
            assertEquals("elk", claimsList.get(0).get("reasoner"),
                "null override should be treated as 'auto' (preserve per-claim)");
        }
    }
}
