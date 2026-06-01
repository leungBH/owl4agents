plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-owlapi"))
    api(project(":modules:ontology-storage"))

    // OWL reasoners — aligned with OWL API 5.1.20
    // HermiT 1.4.5.519 targets OWL API 5.x — fully compatible
    // ELK (liveontologies) 0.6.0 targets OWL API 5.1.20 — exact match
    // Openllet 2.6.5 targets OWL API 5.x — fully compatible
    // Exclude OWL API transitive deps to let our pinned 5.1.20 take precedence
    implementation("net.sourceforge.owlapi:org.semanticweb.hermit:1.4.5.519") {
        exclude(group = "net.sourceforge.owlapi")
    }
    implementation("io.github.liveontologies:elk-owlapi:0.6.0") {
        exclude(group = "net.sourceforge.owlapi")
    }
    implementation("com.github.galigator.openllet:openllet-owlapi:2.6.5") {
        exclude(group = "net.sourceforge.owlapi")
    }
}

tasks.jar { archiveBaseName.set("ontology-reasoner") }