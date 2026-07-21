plugins {
    java
    application
}

dependencies {
    implementation(project(":modules:ontology-core"))
    implementation(project(":modules:ontology-storage"))
    implementation(project(":modules:ontology-owlapi"))
    implementation(project(":modules:ontology-query"))
    implementation(project(":modules:ontology-retrieval"))
    implementation(project(":modules:ontology-reasoner"))
    implementation(project(":modules:ontology-validation"))
    implementation(project(":modules:ontology-cli"))
    implementation(project(":modules:ontology-mcp"))

    // Test dependencies for the end-to-end acceptance suite
    testImplementation(project(":modules:ontology-core"))
    testImplementation(project(":modules:ontology-storage"))
    testImplementation(project(":modules:ontology-owlapi"))
    testImplementation(project(":modules:ontology-query"))
    testImplementation(project(":modules:ontology-retrieval"))
    testImplementation(project(":modules:ontology-reasoner"))
    testImplementation(project(":modules:ontology-validation"))
    testImplementation(project(":modules:ontology-cli"))
    testImplementation(project(":modules:ontology-mcp"))

    // v0.8.6: Jena is required for the real ontology integration test
    // (RealOntologyIntegrationTest) which directly references
    // org.apache.jena.rdf.model.Model to run SPARQL SELECT queries via
    // SparqlExecutor. Jena is `implementation` in ontology-query, so it
    // is not transitively on the test compile classpath; expose it here.
    testImplementation("org.apache.jena:apache-jena-libs:5.3.0")
}

application {
    mainClass.set("org.owl4agents.cli.Owl4AgentsCli")
}

tasks.jar {
    archiveBaseName.set("ontology-distribution")
    manifest {
        attributes["Main-Class"] = "org.owl4agents.cli.Owl4AgentsCli"
    }
}