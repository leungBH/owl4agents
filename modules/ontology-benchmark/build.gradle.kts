plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-validation"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.google.code.gson:gson:2.13.1")
    // YAML parsing for experiment configs
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
    // Mockito for service-layer unit tests
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

tasks.jar { archiveBaseName.set("ontology-benchmark") }
