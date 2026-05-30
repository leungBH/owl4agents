plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:ontology-core"))
    api(project(":modules:ontology-owlapi"))
    implementation("org.apache.jena:apache-jena-libs:5.3.0")
}

tasks.jar { archiveBaseName.set("ontology-query") }