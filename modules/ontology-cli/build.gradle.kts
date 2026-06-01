plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":modules:ontology-core"))
    implementation(project(":modules:ontology-storage"))
    implementation(project(":modules:ontology-owlapi"))
    implementation(project(":modules:ontology-query"))
    implementation(project(":modules:ontology-retrieval"))
    implementation(project(":modules:ontology-mcp"))
    implementation(project(":modules:ontology-reasoner"))
    implementation(project(":modules:ontology-validation"))

    // Apache Jena for SPARQL execution
    implementation("org.apache.jena:apache-jena-libs:5.3.0")

    // Gson for JSON-RPC
    implementation("com.google.code.gson:gson:2.13.1")

    // Picocli for CLI implementation
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}

application {
    mainClass.set("org.owl4agents.cli.Owl4AgentsCli")
}

tasks.jar {
    archiveBaseName.set("ontology-cli")
    manifest {
        attributes["Main-Class"] = "org.owl4agents.cli.Owl4AgentsCli"
    }
}

// Shadow jar (fat jar) - includes all dependencies
tasks.shadowJar {
    archiveBaseName.set("owl4agents")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "org.owl4agents.cli.Owl4AgentsCli"
    }
    mergeServiceFiles()
}

// Make shadowJar run after build
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}