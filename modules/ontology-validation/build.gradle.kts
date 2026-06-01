plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-reasoner"))
    api(project(":modules:ontology-owlapi"))
    api(project(":modules:ontology-storage"))
    api(project(":modules:ontology-query"))
    api(project(":modules:ontology-retrieval"))
    api("net.sourceforge.owlapi:owlapi-distribution:5.1.20")
}

tasks.jar { archiveBaseName.set("ontology-validation") }