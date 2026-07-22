plugins {
    `java-library`
}

// v0.8.7 D1 / OA-005: Tool call validation pipeline module.
// Top-level orchestration layer that composes:
//   - ontology-core (ServiceResult, ErrorCode)
//   - ontology-validation (ClaimWorkflowService.verifyBatch for CL-002)
//   - ontology-shacl (pipeline stage 7)
//   - ontology-overlay (pipeline stage 4)
//   - ontology-retrieval (entity lookup for claim decomposition)
dependencies {
    implementation(project(":modules:ontology-core"))
    api(project(":modules:ontology-validation"))
    api(project(":modules:ontology-shacl"))
    api(project(":modules:ontology-overlay"))
    implementation(project(":modules:ontology-retrieval"))

    // TC-005: JSON Schema pre-validation for ToolContract.inputSchema
    implementation("com.networknt:json-schema-validator:1.5.2")

    // PL-001: Jena Model for stage 7 overlay → Jena conversion (SHACL data graph)
    implementation("org.apache.jena:apache-jena-libs:5.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.jar { archiveBaseName.set("ontology-toolcall") }

tasks.test {
    useJUnitPlatform()
}
