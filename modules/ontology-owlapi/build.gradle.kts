plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-storage"))
    api("net.sourceforge.owlapi:owlapi-distribution:4.5.29")
}

tasks.jar { archiveBaseName.set("ontology-owlapi") }