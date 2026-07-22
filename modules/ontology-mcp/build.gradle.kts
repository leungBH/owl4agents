plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:ontology-core"))
    implementation(project(":modules:ontology-storage"))
    implementation(project(":modules:ontology-owlapi"))
    implementation(project(":modules:ontology-query"))
    implementation(project(":modules:ontology-retrieval"))
    implementation(project(":modules:ontology-reasoner"))
    implementation(project(":modules:ontology-validation"))
    implementation(project(":modules:ontology-benchmark"))
    // v0.8.7 SHACL MCP tools: ontology_validate_shacl, ontology_list_shape_sets,
    // ontology_get_shape_set.
    implementation(project(":modules:ontology-shacl"))
    // v0.8.7 ToolCall MCP tools: ontology_get_tool_contract,
    // ontology_list_tool_contracts, plus the v0.8.7 Pipeline readonly tools
    // (ontology_validate_tool_call, ontology_explain_tool_call,
    // ontology_preview_tool_call_effects). The Pipeline reuses the overlay
    // service for stage 4 (Build Overlay).
    implementation(project(":modules:ontology-toolcall"))
    // v0.8.7 Pipeline MCP tools: the McpServerAdapter lazy-inits
    // TransientOntologyOverlayServiceImpl for stage 4 (Build Overlay) and
    // for ontology_preview_tool_call_effects.
    implementation(project(":modules:ontology-overlay"))

    // Apache Jena for SPARQL execution
    implementation("org.apache.jena:apache-jena-libs:5.3.0")

    // Gson for JSON processing
    implementation("com.google.code.gson:gson:2.13.1")

    // MCP Java SDK — v0.1 uses a minimal JSON-over-stdin/stdout protocol
    // The official Anthropic MCP Java SDK Maven coordinate will be added
    // once the artifact is published. For now, MCP server communication
    // is handled via the adapter pattern in McpServerAdapter.
}

tasks.jar {
    archiveBaseName.set("ontology-mcp")
}