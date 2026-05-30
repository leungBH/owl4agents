plugins {
    `java-library`
}

dependencies {
    // Core module has no external dependencies — it defines interfaces, models, and error codes
}

tasks.jar {
    archiveBaseName.set("ontology-core")
}