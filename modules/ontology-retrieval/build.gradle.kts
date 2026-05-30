plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:ontology-core"))
    api(project(":modules:ontology-owlapi"))
}

tasks.jar {
    archiveBaseName.set("ontology-retrieval")
}