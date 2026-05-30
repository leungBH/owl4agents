package org.owl4agents.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the core result envelope and error mapping.
 * These tests verify the shared ServiceResult, ServiceError, and ErrorCode
 * contracts that CLI and MCP adapters must follow.
 */
@DisplayName("Core contract tests")
class ServiceResultContractTest {

    @Nested
    @DisplayName("ServiceResult envelope")
    class ResultEnvelopeTests {

        @Test
        @DisplayName("Success result contains data and metadata")
        void successResultContainsDataAndMetadata() {
            OntologyId ontologyId = new OntologyId("test");
            ResultMetadata metadata = ResultMetadata.explicit(ontologyId);
            ServiceResult<String> result = ServiceResult.success("data", metadata);

            assertTrue(result.isSuccess());
            assertInstanceOf(ServiceResult.Success.class, result);

            ServiceResult.Success<String> success = (ServiceResult.Success<String>) result;
            assertEquals("data", success.data());
            assertNotNull(success.metadata());
            assertEquals(ontologyId, success.metadata().ontologyId());
            assertEquals(GraphScope.EXPLICIT, success.metadata().graphScope());
            assertEquals(ResultMetadata.EXTRACTION_EXPLICIT, success.metadata().extractionStatus());
        }

        @Test
        @DisplayName("Error result contains structured error")
        void errorResultContainsStructuredError() {
            ServiceResult<Object> result = ServiceResult.error(
                ErrorCode.ONTOLOGY_NOT_FOUND,
                "No ontology with ID 'pizza' found.",
                Map.of("ontologyId", "pizza")
            );

            assertFalse(result.isSuccess());
            assertInstanceOf(ServiceResult.Error.class, result);

            ServiceResult.Error<Object> error = (ServiceResult.Error<Object>) result;
            assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, error.error().code());
            assertEquals("No ontology with ID 'pizza' found.", error.error().message());
            assertEquals("pizza", error.error().details().get("ontologyId"));
        }
    }

    @Nested
    @DisplayName("ErrorCode mapping")
    class ErrorCodeTests {

        @Test
        @DisplayName("All error codes have string representations matching the contract")
        void errorCodesMatchContract() {
            assertEquals("ONTOLOGY_NOT_FOUND", ErrorCode.ONTOLOGY_NOT_FOUND.code());
            assertEquals("ENTITY_NOT_FOUND", ErrorCode.ENTITY_NOT_FOUND.code());
            assertEquals("IMPORT_FAILED", ErrorCode.IMPORT_FAILED.code());
            assertEquals("INVALID_SPARQL", ErrorCode.INVALID_SPARQL.code());
            assertEquals("READONLY_VIOLATION", ErrorCode.READONLY_VIOLATION.code());
            assertEquals("FILE_ACCESS_DENIED", ErrorCode.FILE_ACCESS_DENIED.code());
            assertEquals("QUERY_TIMEOUT", ErrorCode.QUERY_TIMEOUT.code());
        }

        @Test
        @DisplayName("Error codes have human-readable default messages")
        void errorCodesHaveDefaultMessages() {
            assertNotNull(ErrorCode.ONTOLOGY_NOT_FOUND.defaultMessage());
            assertNotNull(ErrorCode.ENTITY_NOT_FOUND.defaultMessage());
            assertNotNull(ErrorCode.IMPORT_FAILED.defaultMessage());
            assertNotNull(ErrorCode.INVALID_SPARQL.defaultMessage());
            assertNotNull(ErrorCode.READONLY_VIOLATION.defaultMessage());
            assertNotNull(ErrorCode.FILE_ACCESS_DENIED.defaultMessage());
            assertNotNull(ErrorCode.QUERY_TIMEOUT.defaultMessage());
        }
    }

    @Nested
    @DisplayName("ServiceError factory methods")
    class ServiceErrorFactoryTests {

        @Test
        @DisplayName("ontologyNotFound creates correct error")
        void ontologyNotFoundError() {
            OntologyId ontologyId = new OntologyId("pizza");
            ServiceError error = ServiceError.ontologyNotFound(ontologyId);

            assertEquals(ErrorCode.ONTOLOGY_NOT_FOUND, error.code());
            assertTrue(error.message().contains("pizza"));
            assertEquals("pizza", error.details().get("ontologyId"));
        }

        @Test
        @DisplayName("entityNotFound creates correct error")
        void entityNotFoundError() {
            EntityId entityIri = new EntityId("http://example.org#Dog");
            OntologyId ontologyId = new OntologyId("subclass");
            ServiceError error = ServiceError.entityNotFound(entityIri, ontologyId);

            assertEquals(ErrorCode.ENTITY_NOT_FOUND, error.code());
            assertTrue(error.message().contains("Dog"));
            assertEquals("http://example.org#Dog", error.details().get("entityIri"));
        }

        @Test
        @DisplayName("importFailed creates correct error")
        void importFailedError() {
            ServiceError error = ServiceError.importFailed("/path/to/bad.owl", "Invalid RDF syntax");

            assertEquals(ErrorCode.IMPORT_FAILED, error.code());
            assertTrue(error.message().contains("/path/to/bad.owl"));
            assertEquals("Invalid RDF syntax", error.details().get("reason"));
        }

        @Test
        @DisplayName("readonlyViolation creates correct error")
        void readonlyViolationError() {
            ServiceError error = ServiceError.readonlyViolation("INSERT");

            assertEquals(ErrorCode.READONLY_VIOLATION, error.code());
            assertTrue(error.message().contains("INSERT"));
            assertEquals("readonly", error.details().get("mode"));
        }

        @Test
        @DisplayName("fileAccessDenied creates correct error")
        void fileAccessDeniedError() {
            ServiceError error = ServiceError.fileAccessDenied("/etc/passwd", "not_cataloged");

            assertEquals(ErrorCode.FILE_ACCESS_DENIED, error.code());
            assertTrue(error.message().contains("/etc/passwd"));
            assertEquals("not_cataloged", error.details().get("reason"));
        }

        @Test
        @DisplayName("queryTimeout creates correct error")
        void queryTimeoutError() {
            ServiceError error = ServiceError.queryTimeout(30000, 35000);

            assertEquals(ErrorCode.QUERY_TIMEOUT, error.code());
            assertEquals(30000, error.details().get("timeoutMs"));
            assertEquals(35000, error.details().get("executionTimeMs"));
        }

        @Test
        @DisplayName("invalidSparql creates correct error")
        void invalidSparqlError() {
            ServiceError error = ServiceError.invalidSparql("Unexpected token 'SELCT'");

            assertEquals(ErrorCode.INVALID_SPARQL, error.code());
            assertTrue(error.message().contains("SELCT"));
            assertEquals("Unexpected token 'SELCT'", error.details().get("parseError"));
        }
    }

    @Nested
    @DisplayName("Model validation")
    class ModelValidationTests {

        @Test
        @DisplayName("OntologyId rejects null or blank")
        void ontologyIdValidation() {
            assertThrows(IllegalArgumentException.class, () -> new OntologyId(null));
            assertThrows(IllegalArgumentException.class, () -> new OntologyId(""));
            assertThrows(IllegalArgumentException.class, () -> new OntologyId("  "));
            assertNotNull(new OntologyId("pizza"));
        }

        @Test
        @DisplayName("EntityId rejects null or blank")
        void entityIdValidation() {
            assertThrows(IllegalArgumentException.class, () -> new EntityId(null));
            assertThrows(IllegalArgumentException.class, () -> new EntityId(""));
            assertNotNull(new EntityId("http://example.org#Class"));
        }

        @Test
        @DisplayName("WorkspaceId rejects null or blank")
        void workspaceIdValidation() {
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceId(null));
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceId(""));
            assertNotNull(new WorkspaceId("default"));
        }

        @Test
        @DisplayName("WorkspaceId DEFAULT constant exists")
        void workspaceDefaultConstant() {
            assertEquals("default", WorkspaceId.DEFAULT.name());
        }

        @Test
        @DisplayName("EntityType jsonName matches contract values")
        void entityTypeJsonNames() {
            assertEquals("class", EntityType.CLASS.jsonName());
            assertEquals("object_property", EntityType.OBJECT_PROPERTY.jsonName());
            assertEquals("data_property", EntityType.DATA_PROPERTY.jsonName());
            assertEquals("annotation_property", EntityType.ANNOTATION_PROPERTY.jsonName());
            assertEquals("individual", EntityType.INDIVIDUAL.jsonName());
            assertEquals("datatype", EntityType.DATATYPE.jsonName());
        }

        @Test
        @DisplayName("GraphScope jsonName matches contract values")
        void graphScopeJsonNames() {
            assertEquals("explicit", GraphScope.EXPLICIT.jsonName());
            assertEquals("inferred", GraphScope.INFERRED.jsonName());
            assertEquals("union", GraphScope.UNION.jsonName());
        }
    }
}