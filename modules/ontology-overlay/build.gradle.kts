plugins {
    `java-library`
}

// v0.8.7 D1 / OA-005: Transient ontology overlay module.
// Wraps TemporaryOntologyFactory (v0.8.5) to provide general-purpose dynamic
// ABox overlay creation. Depends on:
//   - ontology-core (ServiceResult, ErrorCode, OntologyId)
//   - ontology-owlapi (OWLOntologyManager, OWLAxiom, OntologyCache)
//   - ontology-reasoner (TemporaryOntologyFactory, ReasonerCallWrapper)
dependencies {
    implementation(project(":modules:ontology-core"))
    api(project(":modules:ontology-owlapi"))
    api(project(":modules:ontology-reasoner"))

    // v0.8.7 OV-003 / D10: Jena 5.3.0 for RDF dynamic state parsing
    // (Turtle/JSON-LD/N-Triples -> Jena Model -> OWL API axioms).
    // Version aligned with ontology-query and ontology-shacl.
    implementation("org.apache.jena:apache-jena-libs:5.3.0")

    // v0.8.7 D7: Caffeine cache for any overlay-internal caching needs.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("net.sourceforge.owlapi:owlapi-distribution:5.1.20")
}

tasks.jar { archiveBaseName.set("ontology-overlay") }

tasks.test {
    useJUnitPlatform()
}
