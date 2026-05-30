package org.owl4agents.mcp;

import java.util.*;

/**
 * Registry of v0.1 readonly MCP tools.
 * Lists available tools and their schemas.
 * Write-style tools are not included.
 */
public class McpToolRegistry {

    private static final List<String> READONLY_TOOLS = List.of(
        "ontology_list",
        "ontology_summary",
        "ontology_get_metadata",
        "ontology_get_profile",
        "ontology_list_graphs",
        "ontology_search_entities",
        "ontology_get_entity_context",
        "ontology_get_class_context",
        "ontology_get_object_property_context",
        "ontology_get_data_property_context",
        "ontology_get_individual_context",
        "ontology_get_graph_neighborhood",
        "ontology_validate_sparql",
        "ontology_sparql_select",
        "ontology_sparql_ask",
        "ontology_sparql_construct",
        "ontology_sparql_describe",
        "ontology_get_qa_context"
    );

    /**
     * Check if a tool name is a v0.1 readonly tool.
     */
    public boolean isReadonlyTool(String toolName) {
        return READONLY_TOOLS.contains(toolName);
    }

    /**
     * List all v0.1 readonly tool schemas.
     */
    public List<Map<String, Object>> listToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();

        // Workspace and ontology tools
        schemas.add(toolSchema("ontology_list", "List imported ontologies in a workspace",
            Map.of()));
        schemas.add(toolSchema("ontology_summary", "Return ontology metadata, profile, imports, and entity counts",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_metadata", "Return ontology IRI, version IRI, source path, canonical path, and timestamps",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_get_profile", "Return OWL profile information and profile violations",
            Map.of("ontology_id", stringParam("Ontology ID"))));
        schemas.add(toolSchema("ontology_list_graphs", "List queryable graph scopes such as explicit, inferred, and union",
            Map.of("ontology_id", stringParam("Ontology ID"))));

        // Entity tools
        schemas.add(toolSchema("ontology_search_entities", "Search classes, properties, and individuals",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("Search query text"))));
        schemas.add(toolSchema("ontology_get_entity_context", "Return labels, comments, class/property/individual context, and related facts",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Entity IRI"))));
        schemas.add(toolSchema("ontology_get_class_context", "Return labels, comments, parents, children, equivalent classes, disjoint classes, and restrictions",
            Map.of("ontology_id", stringParam("Ontology ID"), "class_iri", stringParam("Class IRI"))));
        schemas.add(toolSchema("ontology_get_object_property_context", "Return labels, comments, domain, range, hierarchy, inverse properties, and characteristics",
            Map.of("ontology_id", stringParam("Ontology ID"), "property_iri", stringParam("Object property IRI"))));
        schemas.add(toolSchema("ontology_get_data_property_context", "Return labels, comments, domain, range, datatype, and hierarchy",
            Map.of("ontology_id", stringParam("Ontology ID"), "property_iri", stringParam("Data property IRI"))));
        schemas.add(toolSchema("ontology_get_individual_context", "Return labels, comments, explicit types, object property assertions, and data property assertions",
            Map.of("ontology_id", stringParam("Ontology ID"), "individual_iri", stringParam("Individual IRI"))));
        schemas.add(toolSchema("ontology_get_graph_neighborhood", "Return local graph neighborhood around an entity",
            Map.of("ontology_id", stringParam("Ontology ID"), "entity_iri", stringParam("Entity IRI"), "depth", intParam("Neighborhood depth", 1))));

        // SPARQL tools
        schemas.add(toolSchema("ontology_validate_sparql", "Parse and validate a SPARQL query before execution",
            Map.of("query", stringParam("SPARQL query text"))));
        schemas.add(toolSchema("ontology_sparql_select", "Run a read-only SPARQL SELECT query",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL SELECT query"), "graph_scope", stringParam("Graph scope: explicit, inferred, union"))));
        schemas.add(toolSchema("ontology_sparql_ask", "Run a SPARQL ASK query",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL ASK query"))));
        schemas.add(toolSchema("ontology_sparql_construct", "Run a SPARQL CONSTRUCT query and return RDF triples",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL CONSTRUCT query"))));
        schemas.add(toolSchema("ontology_sparql_describe", "Run a SPARQL DESCRIBE query for one or more resources",
            Map.of("ontology_id", stringParam("Ontology ID"), "query", stringParam("SPARQL DESCRIBE query"))));

        // QA context tool
        schemas.add(toolSchema("ontology_get_qa_context", "Build ontology-grounded context for an agent question",
            Map.of("ontology_id", stringParam("Ontology ID"), "question", stringParam("Question text"), "max_entities", intParam("Max matched entities", 10), "max_depth", intParam("Max context depth", 3))));

        return schemas;
    }

    private Map<String, Object> toolSchema(String name, String description, Map<String, Object> inputSchema) {
        return Map.of(
            "name", name,
            "description", description,
            "inputSchema", Map.of("type", "object", "properties", inputSchema)
        );
    }

    private Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> intParam(String description, int defaultValue) {
        return Map.of("type", "integer", "description", description, "default", defaultValue);
    }
}