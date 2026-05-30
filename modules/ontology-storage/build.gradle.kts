plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    implementation("com.google.code.gson:gson:2.13.1")
}

tasks.jar {
    archiveBaseName.set("ontology-storage")
}