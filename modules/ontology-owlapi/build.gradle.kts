plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-storage"))
    api("net.sourceforge.owlapi:owlapi-distribution:5.1.20")

    // v0.8.6 D4: Explicit Caffeine dependency for EntitySignatureCache LRU.
    // Caffeine is transitively present via OWL API, but explicit declaration
    // avoids version drift and enables recordStats() usage.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.jar { archiveBaseName.set("ontology-owlapi") }

tasks.test {
    useJUnitPlatform()
}