plugins {
    `java-library`
}

dependencies {
    // Core module defines interfaces, models, error codes, and shared Gson utilities
    api("com.google.code.gson:gson:2.13.1")
}

tasks.jar {
    archiveBaseName.set("ontology-core")
}