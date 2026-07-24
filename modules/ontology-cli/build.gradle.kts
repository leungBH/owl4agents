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
    implementation(project(":modules:ontology-benchmark"))
    // v0.8.7 SHACL CLI: shacl-validate and shacl-register commands.
    implementation(project(":modules:ontology-shacl"))
    // v0.8.7 Pipeline CLI: toolcall-validate command. The Pipeline reuses
    // the overlay service for stage 4 (Build Overlay) and the toolcall
    // module for the ToolCallValidationPipeline orchestrator.
    implementation(project(":modules:ontology-toolcall"))
    implementation(project(":modules:ontology-overlay"))

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
        attributes(
            "Main-Class" to "org.owl4agents.cli.Owl4AgentsCli",
            // v0.8.6 D9 / task 4.2: Implementation-Version is read by
            // Package.getImplementationVersion() so McpServerAdapter
            // can resolve SERVER_VERSION from the jar manifest in
            // non-shadowJar runs (e.g. plain `gradle jar`).
            // Use rootProject.version because the subproject's own version
            // defaults to "unspecified" unless explicitly set.
            "Implementation-Version" to rootProject.version.toString()
        )
    }
}

// Shadow jar (fat jar) - includes all dependencies
tasks.shadowJar {
    archiveBaseName.set("owl4agents")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Main-Class" to "org.owl4agents.cli.Owl4AgentsCli",
            // v0.8.6 D9 / task 8.3: Implementation-Version is read by
            // Package.getImplementationVersion() so McpServerAdapter
            // can resolve SERVER_VERSION from the shadow jar manifest
            // in production runs.
            // Use rootProject.version because the subproject's own version
            // defaults to "unspecified" unless explicitly set.
            "Implementation-Version" to rootProject.version.toString(),
            // v0.8.6 D5 / task 4.1: JVM-Args is a documentation-only
            // manifest attribute. `java -jar` does NOT read this; the
            // launch scripts (tools/bin/owl4agents.bat / owl4agents.ps1) hardcode
            // the same flags directly.
            "JVM-Args" to "-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./owl4agents-heapdump.hprof"
        )
    }
    mergeServiceFiles()
}

// Make shadowJar run after build
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}