package org.owl4agents.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.owl4agents.core.model.Claim;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.6 Section 7 / task 7.3: verify that {@code parseClaimFromMcpArgs}
 * accepts both {@code claimId} (MCP single-claim schema) and {@code id}
 * (question set file schema) as aliases, with {@code claimId} taking
 * precedence when both are present.
 *
 * <p>The test calls the private {@code parseClaimFromMcpArgs} method via
 * reflection so it can assert the resolved {@link Claim#claimId()} field
 * directly, without depending on a loaded ontology (the parsing stage runs
 * before any ontology lookup).</p>
 *
 * <p>Cases per the task spec:</p>
 * <ol>
 *   <li>{@code {"claimId": "c1"}} -> {@code claimId=c1}</li>
 *   <li>{@code {"id": "c1"}} -> {@code claimId=c1}</li>
 *   <li>{@code {"claimId": "c1", "id": "c2"}} -> {@code claimId=c1} (claimId wins)</li>
 *   <li>{@code {}} -> throws {@link IllegalArgumentException} with a
 *       clarifying message that documents the alias.</li>
 * </ol>
 */
@DisplayName("v0.8.6 Section 7: claimId / id field name alias")
class ClaimSchemaFieldNameAliasTest {

    @TempDir
    Path tempDir;

    private McpServerAdapter createAdapter() {
        Map<String, Object> serviceContext = new HashMap<>();
        String logPath = tempDir.resolve("claim-alias-test.log").toString();
        return new McpServerAdapter(serviceContext, logPath);
    }

    private Claim invokeParseClaim(Object claimObj) throws Exception {
        Method method = McpServerAdapter.class.getDeclaredMethod(
            "parseClaimFromMcpArgs", Object.class, String.class);
        method.setAccessible(true);
        try {
            Object result = method.invoke(createAdapter(), claimObj, "auto");
            return (Claim) result;
        } catch (java.lang.reflect.InvocationTargetException e) {
            // v0.8.6 §7.3: unwrap the cause so assertThrows sees the
            // actual exception type (e.g. IllegalArgumentException) instead
            // of the reflection wrapper. Reflection always wraps the
            // target method's exceptions in InvocationTargetException.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            } else if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private Map<String, Object> baseClaimMap() {
        // Minimal valid claim map with the alias-agnostic fields filled in.
        Map<String, Object> claim = new HashMap<>();
        claim.put("type", "SUBCLASS");
        Map<String, Object> subject = new HashMap<>();
        subject.put("kind", "class");
        subject.put("iri", "http://ex.org/A");
        claim.put("subject", subject);
        claim.put("predicate", "http://ex.org/subClassOf");
        Map<String, Object> object = new HashMap<>();
        object.put("kind", "class");
        object.put("iri", "http://ex.org/B");
        claim.put("object", object);
        return claim;
    }

    @Test
    @DisplayName("case 1: {claimId: c1} -> claimId=c1")
    void claimIdFieldIsAccepted() throws Exception {
        Map<String, Object> claim = baseClaimMap();
        claim.put("claimId", "c1");

        Claim parsed = invokeParseClaim(claim);

        assertNotNull(parsed, "claim with claimId=c1 should parse successfully");
        assertEquals("c1", parsed.claimId(),
            "claimId field should map to Claim.claimId");
    }

    @Test
    @DisplayName("case 2: {id: c1} -> claimId=c1 (id accepted as alias)")
    void idFieldIsAcceptedAsAlias() throws Exception {
        Map<String, Object> claim = baseClaimMap();
        claim.put("id", "c1");

        Claim parsed = invokeParseClaim(claim);

        assertNotNull(parsed, "claim with id=c1 should parse successfully via alias");
        assertEquals("c1", parsed.claimId(),
            "id field should map to Claim.claimId (alias)");
    }

    @Test
    @DisplayName("case 3: {claimId: c1, id: c2} -> claimId=c1 (claimId wins)")
    void claimIdTakesPrecedenceOverId() throws Exception {
        Map<String, Object> claim = baseClaimMap();
        claim.put("claimId", "c1");
        claim.put("id", "c2");

        Claim parsed = invokeParseClaim(claim);

        assertNotNull(parsed, "claim with both fields should parse successfully");
        assertEquals("c1", parsed.claimId(),
            "claimId should take precedence over id when both are present");
    }

    @Test
    @DisplayName("case 4: {} -> throws IllegalArgumentException with clarifying message")
    void emptyClaimThrowsClarifyingError() {
        Map<String, Object> claim = baseClaimMap();
        // No claimId, no id -> must throw with clarifying message.

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> invokeParseClaim(claim),
            "Empty claim (no claimId and no id) should throw IllegalArgumentException");

        String message = ex.getMessage();
        assertNotNull(message, "Exception message must not be null");
        // The clarifying message MUST mention both field names so users
        // understand the alias contract.
        assertTrue(message.contains("claimId"),
            "Error message must mention 'claimId'. Got: " + message);
        assertTrue(message.contains("id"),
            "Error message must mention 'id'. Got: " + message);
        assertTrue(message.contains("alias") || message.contains("accepted"),
            "Error message must explain the alias contract. Got: " + message);
    }
}
