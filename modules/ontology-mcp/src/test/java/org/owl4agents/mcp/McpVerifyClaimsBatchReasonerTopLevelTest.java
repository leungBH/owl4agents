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
 * v0.8.6 Section 1.6 / task 1.6: verify that a top-level
 * {@code reasoner=hermit} overrides {@code options.reasoner=elk}.
 *
 * <p>The fix priority order (D6 / {@code resolveReasonerFromArgs}) is:
 * {@code args.reasoner > args.options.reasoner > "auto"}. When the
 * top-level {@code reasoner} is non-{@code auto}, it wins regardless
 * of what {@code options.reasoner} says, and the resulting override
 * is then injected into every parsed claim (overriding per-claim
 * {@code reasoner} values too).</p>
 *
 * <p>This test verifies the priority via reflection on
 * {@code resolveReasonerFromArgs} and {@code parseClaimsBatchFromArgs}.</p>
 */
@DisplayName("v0.8.6 §1.6: top-level reasoner=hermit overrides options.reasoner=elk")
class McpVerifyClaimsBatchReasonerTopLevelTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("batch-toplevel-test.log").toString();
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

    private Map<String, Object> baseClaimMap(String id) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("claimId", id);
        claim.put("type", "SUBCLASS");
        claim.put("ontologyId", "pizza");
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
    @DisplayName("Reflection: resolveReasonerFromArgs priority")
    class ResolveReasonerPriorityTests {

        @Test
        @DisplayName("top-level reasoner=hermit wins over options.reasoner=elk")
        void topLevelOverridesOptions() throws Exception {
            Map<String, Object> args = new HashMap<>();
            args.put("reasoner", "hermit");
            Map<String, Object> options = new HashMap<>();
            options.put("reasoner", "elk");
            args.put("options", options);

            String resolved = invokeResolveReasoner(args);

            assertEquals("hermit", resolved,
                "top-level reasoner=hermit must win over options.reasoner=elk");
        }

        @Test
        @DisplayName("top-level reasoner=HermiT (mixed case) wins over options.reasoner=ELK")
        void topLevelMixedCaseWinsOverOptions() throws Exception {
            Map<String, Object> args = new HashMap<>();
            args.put("reasoner", "HermiT");
            Map<String, Object> options = new HashMap<>();
            options.put("reasoner", "ELK");
            args.put("options", options);

            String resolved = invokeResolveReasoner(args);

            assertEquals("HermiT", resolved,
                "top-level 'HermiT' must win over options 'ELK' and preserve case");
        }

        @Test
        @DisplayName("top-level reasoner=auto falls through to options.reasoner=elk")
        void topLevelAutoFallsThroughToOptions() throws Exception {
            Map<String, Object> args = new HashMap<>();
            args.put("reasoner", "auto");
            Map<String, Object> options = new HashMap<>();
            options.put("reasoner", "elk");
            args.put("options", options);

            String resolved = invokeResolveReasoner(args);

            assertEquals("elk", resolved,
                "top-level reasoner=auto should fall through to options.reasoner=elk");
        }
    }

    @Nested
    @DisplayName("Reflection: parseClaimsBatchFromArgs injects top-level override")
    class ParseClaimsBatchOverrideTests {

        @Test
        @DisplayName("override=hermit overrides per-claim reasoner=elk")
        void hermitOverridesPerClaimElk() throws Exception {
            Map<String, Object> claim1 = baseClaimMap("c1");
            claim1.put("reasoner", "elk"); // per-claim should be overridden
            Map<String, Object> claim2 = baseClaimMap("c2");
            claim2.put("reasoner", "openllet"); // per-claim should be overridden
            Map<String, Object> args = batchWithClaims(claim1, claim2);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, "hermit");
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(2, claimsList.size());
            assertEquals("hermit", claimsList.get(0).get("reasoner"),
                "claim1.reasoner must be overridden to 'hermit'");
            assertEquals("hermit", claimsList.get(1).get("reasoner"),
                "claim2.reasoner must be overridden to 'hermit'");
        }

        @Test
        @DisplayName("override=HermiT (mixed case) overrides per-claim reasoner=elk")
        void mixedCaseHermiTOverrides() throws Exception {
            Map<String, Object> claim = baseClaimMap("c1");
            claim.put("reasoner", "elk");
            Map<String, Object> args = batchWithClaims(claim);

            Map<String, Object> batchMap = invokeParseClaimsBatch(args, "HermiT");
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals("HermiT", claimsList.get(0).get("reasoner"),
                "claim.reasoner must be overridden to 'HermiT' (case preserved)");
        }

        @Test
        @DisplayName("full end-to-end: top-level + options + per-claim -> top-level wins")
        void endToEndTopLevelWins() throws Exception {
            // This is the canonical scenario from the bug report:
            // args.reasoner=hermit + args.options.reasoner=elk + claim.reasoner=elk
            // Expected: resolveReasonerFromArgs returns "hermit", and that
            // override is injected into the parsed claim (overriding per-claim elk).
            Map<String, Object> claim = baseClaimMap("c1");
            claim.put("reasoner", "elk");
            Map<String, Object> args = batchWithClaims(claim);
            args.put("reasoner", "hermit");
            Map<String, Object> options = new HashMap<>();
            options.put("reasoner", "elk");
            args.put("options", options);

            // Step 1: resolveReasonerFromArgs must return top-level value
            String resolved = invokeResolveReasoner(args);
            assertEquals("hermit", resolved,
                "top-level reasoner=hermit must win");

            // Step 2: parseClaimsBatchFromArgs with that override must inject it
            Map<String, Object> batchMap = invokeParseClaimsBatch(args, resolved);
            assertNotNull(batchMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> claimsList = (List<Map<String, Object>>) batchMap.get("claims");
            assertEquals(1, claimsList.size());
            assertEquals("hermit", claimsList.get(0).get("reasoner"),
                "claim.reasoner must be overridden to 'hermit' (top-level wins)");
        }
    }
}
