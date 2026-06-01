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