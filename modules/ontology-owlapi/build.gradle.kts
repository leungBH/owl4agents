plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-storage"))
    api("net.sourceforge.owlapi:owlapi-distribution:5.1.20")
}

tasks.jar { archiveBaseName.set("ontology-owlapi") }