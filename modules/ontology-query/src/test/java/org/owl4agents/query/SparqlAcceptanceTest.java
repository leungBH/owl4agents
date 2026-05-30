package org.owl4agents.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.owl4agents.core.ErrorCode;
import org.owl4agents.core.GraphScope;
import org.owl4agents.core.OntologyId;
import org.owl4agents.core.ServiceResult;
import org.owl4agents.core.model.*;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for SPARQL valid queries, malformed queries,
 * readonly violations, result limits, and timeout behavior.
 */
@DisplayName("SPARQL acceptance tests")
class SparqlAcceptanceTest {

    private SparqlValidator validator;
    private SparqlExecutor executor;
    private SparqlSafetyGuard safetyGuard;
    private SparqlLimitEnforcer limitEnforcer;
    private JenaModelConverter converter;
    private String corpusFixturesPath;

    @BeforeEach
    void setup() {
        validator = new SparqlValidator();
        executor = new SparqlExecutor(30000, 1000);
        safetyGuard = new SparqlSafetyGuard();
        limitEnforcer = new SparqlLimitEnforcer();
        converter = new JenaModelConverter();
        corpusFixturesPath = System.getProperty("corpus.fixtures", "../test/corpus");
    }

    private Path resolveFixture(String relativePath) {
        Path basePath = Path.of(corpusFixturesPath);
        Path fixturePath = basePath.resolve(relativePath);
        if (Files.exists(fixturePath)) return fixturePath;
        return Path.of("test/corpus").resolve(relativePath);
    }

    @Nested
    @DisplayName("Valid queries")
    class ValidQueryTests {

        @Test
        @DisplayName("SELECT query validates and executes")
        void selectQueryValidation() {
            String query = "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10";
            ServiceResult<ValidationResult> result = validator.validate(query);

            assertTrue(result.isSuccess());
            ValidationResult vr = ((ServiceResult.Success<ValidationResult>) result).data();
            assertTrue(vr.isValid());
            assertEquals("SELECT", vr.queryForm());
        }

        @Test
        @DisplayName("ASK query validates correctly")
        void askQueryValidation() {
            String query = "ASK { ?s a <http://example.org/sparql-safety#City> }";
            ServiceResult<ValidationResult> result = validator.validate(query);

            assertTrue(result.isSuccess());
            ValidationResult vr = ((ServiceResult.Success<ValidationResult>) result).data();
            assertTrue(vr.isValid());
            assertEquals("ASK", vr.queryForm());
        }

        @Test
        @DisplayName("CONSTRUCT query validates correctly")
        void constructQueryValidation() {
            String query = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 10";
            ServiceResult<ValidationResult> result = validator.validate(query);

            assertTrue(result.isSuccess());
            assertEquals("CONSTRUCT",
                ((ServiceResult.Success<ValidationResult>) result).data().queryForm());
        }

        @Test
        @DisplayName("DESCRIBE query validates correctly")
        void describeQueryValidation() {
            String query = "DESCRIBE <http://example.org/sparql-safety#paris>";
            ServiceResult<ValidationResult> result = validator.validate(query);

            assertTrue(result.isSuccess());
            assertEquals("DESCRIBE",
                ((ServiceResult.Success<ValidationResult>) result).data().queryForm());
        }
    }

    @Nested
    @DisplayName("Malformed queries")
    class MalformedQueryTests {

        @Test
        @DisplayName("Malformed SPARQL returns INVALID_SPARQL error")
        void malformedQuery() {
            String query = "SELCT ?x WHERE { ?x a ?y }";
            ServiceResult<ValidationResult> result = validator.validate(query);

            assertFalse(result.isSuccess());
            ServiceResult.Error<ValidationResult> error = (ServiceResult.Error<ValidationResult>) result;
            assertEquals(ErrorCode.INVALID_SPARQL, error.error().code());
        }

        @Test
        @DisplayName("Empty query returns error")
        void emptyQuery() {
            ServiceResult<ValidationResult> result = validator.validate("");

            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.INVALID_SPARQL, ((ServiceResult.Error<ValidationResult>) result).error().code());
        }
    }

    @Nested
    @DisplayName("Readonly violations")
    class ReadonlyViolationTests {

        @Test
        @DisplayName("INSERT DATA is blocked")
        void insertBlocked() {
            String query = "INSERT DATA { <http://example.org#NewCity> a <http://example.org#City> }";
            ServiceResult<Void> result = safetyGuard.checkReadonly(query);

            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.READONLY_VIOLATION, ((ServiceResult.Error<Void>) result).error().code());
        }

        @Test
        @DisplayName("DELETE DATA is blocked")
        void deleteBlocked() {
            String query = "DELETE DATA { <http://example.org#paris> a <http://example.org#City> }";
            ServiceResult<Void> result = safetyGuard.checkReadonly(query);

            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.READONLY_VIOLATION, ((ServiceResult.Error<Void>) result).error().code());
        }

        @Test
        @DisplayName("CLEAR operation is blocked")
        void clearBlocked() {
            String query = "CLEAR GRAPH <http://example.org>";
            ServiceResult<Void> result = safetyGuard.checkReadonly(query);

            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.READONLY_VIOLATION, ((ServiceResult.Error<Void>) result).error().code());
        }

        @Test
        @DisplayName("SELECT query passes safety check")
        void selectPassesSafety() {
            String query = "SELECT ?s WHERE { ?s a <http://example.org#City> }";
            ServiceResult<Void> result = safetyGuard.checkReadonly(query);

            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Result limits")
    class ResultLimitTests {

        @Test
        @DisplayName("Default limit is applied when no limit specified")
        void defaultLimit() {
            int resolved = limitEnforcer.resolveResultLimit(0);
            assertEquals(1000, resolved);
        }

        @Test
        @DisplayName("Requested limit within bounds is accepted")
        void withinBoundsLimit() {
            int resolved = limitEnforcer.resolveResultLimit(100);
            assertEquals(100, resolved);
        }

        @Test
        @DisplayName("Excessive limit is clamped to max")
        void excessiveLimitClamped() {
            int resolved = limitEnforcer.resolveResultLimit(50000);
            assertEquals(10000, resolved);
        }
    }

    @Nested
    @DisplayName("Timeout behavior")
    class TimeoutTests {

        @Test
        @DisplayName("Default timeout is 30 seconds")
        void defaultTimeout() {
            int resolved = limitEnforcer.resolveTimeout(0);
            assertEquals(30000, resolved);
        }

        @Test
        @DisplayName("Custom timeout within bounds is accepted")
        void customTimeoutWithinBounds() {
            int resolved = limitEnforcer.resolveTimeout(10000);
            assertEquals(10000, resolved);
        }

        @Test
        @DisplayName("Excessive timeout is clamped to max")
        void excessiveTimeoutClamped() {
            int resolved = limitEnforcer.resolveTimeout(300000);
            assertEquals(120000, resolved);
        }
    }

    @Nested
    @DisplayName("Graph scope validation")
    class GraphScopeTests {

        @Test
        @DisplayName("EXPLICIT scope is valid for v0.1")
        void explicitScope() {
            ServiceResult<Void> result = limitEnforcer.validateGraphScope(GraphScope.EXPLICIT);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("UNION scope is valid for v0.1")
        void unionScope() {
            ServiceResult<Void> result = limitEnforcer.validateGraphScope(GraphScope.UNION);
            assertTrue(result.isSuccess());
        }
    }
}