plugins {
    `java-library`
}

// v0.8.7 D1 / OA-005: SHACL validation module.
// Depends on ontology-core (ServiceResult, ErrorCode) and ontology-query
// (Jena Model / RDF parsing). Does NOT depend on ontology-owlapi because
// SHACL operates at the Jena Model layer, not the OWL API object layer.
// Jena version is aligned with ontology-query (5.3.0, includes jena-shacl).
dependencies {
    implementation(project(":modules:ontology-core"))
    api(project(":modules:ontology-query"))
    implementation("org.apache.jena:apache-jena-libs:5.3.0")

    // v0.8.7 D7: Caffeine cache for ShapeRegistry (maximumSize=50).
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Gson for registry.json persistence and report JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.jar { archiveBaseName.set("ontology-shacl") }

tasks.test {
    useJUnitPlatform()
}
