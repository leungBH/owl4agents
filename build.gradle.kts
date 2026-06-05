plugins {
    java
}

group = "org.owl4agents"
version = "0.3.1"
description = "Local OWL ontology reasoning and MCP server for LLM agents"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(22))
        }
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.12.2"))
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    // Wire acceptance fixture resources into the test runtime
    // Tests can access corpus fixtures via the "corpus.fixtures" system property
    // The path resolves from the root project directory to test/corpus
    tasks.test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
        }
        // Resolve corpus fixtures from root project directory
        val rootCorpusDir = rootProject.layout.projectDirectory.dir("test/corpus").asFile.absolutePath
        systemProperty("corpus.fixtures", rootCorpusDir)
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

tasks.register("buildVerification") {
    group = "verification"
    description = "Runs all tests across all modules"
    dependsOn(subprojects.map { it.tasks.named("test") })
}
