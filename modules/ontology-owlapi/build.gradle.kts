plugins {
    `java-library`
}

dependencies {
    api(project(":modules:ontology-core"))
    api(project(":modules:ontology-storage"))
    api("net.sourceforge.owlapi:owlapi-distribution:5.1.20")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.jar { archiveBaseName.set("ontology-owlapi") }

tasks.test {
    useJUnitPlatform()
}