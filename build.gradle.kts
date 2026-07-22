plugins {
    java
}

group = "org.owl4agents"
version = "0.8.7"
description = "Local OWL ontology reasoning and MCP server for LLM agents"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    // Centralize all module build output under root build/modules/
    // instead of each module having its own build/ directory
    layout.buildDirectory = rootProject.layout.buildDirectory.dir("modules/${project.name}")

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
        // v0.8.6: exclude deployment and integration tests from the default
        // test task. They require special environments (shadow jar for
        // deployment, real ontology fixtures for integration) and are run
        // explicitly via the deploymentTest / integrationTest tasks below.
        useJUnitPlatform {
            excludeTags("deployment", "integration")
        }
        testLogging {
            events("passed", "failed", "skipped")
        }
        // v0.8.2: 4GB heap for large ontology tests (HPO 74MB, Mondo 236MB)
        jvmArgs("-Xmx4g")
        // Resolve corpus fixtures from root project directory
        val rootCorpusDir = rootProject.layout.projectDirectory.dir("test/corpus").asFile.absolutePath
        systemProperty("corpus.fixtures", rootCorpusDir)
        // v0.8.6 D9: expose the project version to tests so
        // VersionConsistencyTest can verify SERVER_VERSION matches the
        // gradle version without requiring a shadow jar manifest.
        // Use rootProject.version because subprojects inherit "unspecified"
        // unless they explicitly set their own version; the canonical
        // version lives at the root project level.
        systemProperty("owl4agents.version", rootProject.version.toString())
    }

    // v0.8.6: per-module deployment test task. Runs tests tagged
    // "deployment" (currently only HttpDeploymentSmokeTest in
    // ontology-distribution). Requires the shadow jar to be built.
    tasks.register("deploymentTest", Test::class) {
        group = "verification"
        description = "Runs HTTP deployment smoke tests (requires shadowJar)"
        useJUnitPlatform {
            includeTags("deployment")
        }
        testLogging {
            events("passed", "failed", "skipped")
        }
        jvmArgs("-Xmx4g")
        val rootCorpusDir = rootProject.layout.projectDirectory.dir("test/corpus").asFile.absolutePath
        systemProperty("corpus.fixtures", rootCorpusDir)
        systemProperty("owl4agents.version", rootProject.version.toString())
        // The deployment smoke test spawns a JVM running the shadow jar,
        // so the jar must be built before the test can run.
        dependsOn(":modules:ontology-cli:shadowJar")
    }

    // v0.8.6: per-module integration test task. Runs tests tagged
    // "integration" (currently only RealOntologyIntegrationTest in
    // ontology-distribution). Uses real ontology corpora from test/corpus.
    tasks.register("integrationTest", Test::class) {
        group = "verification"
        description = "Runs real ontology integration tests"
        useJUnitPlatform {
            includeTags("integration")
        }
        testLogging {
            events("passed", "failed", "skipped")
        }
        jvmArgs("-Xmx4g")
        val rootCorpusDir = rootProject.layout.projectDirectory.dir("test/corpus").asFile.absolutePath
        systemProperty("corpus.fixtures", rootCorpusDir)
        systemProperty("owl4agents.version", rootProject.version.toString())
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

// v0.8.6: aggregate deployment test task. Runs deployment-tagged tests
// across all subprojects (only ontology-distribution currently has any).
// The shadowJar dependency is declared per-module above; this aggregate
// task just collects them.
tasks.register("deploymentTest") {
    group = "verification"
    description = "Runs HTTP deployment smoke tests across all modules (requires shadowJar)"
    dependsOn(subprojects.map { it.tasks.named("deploymentTest") })
}

// v0.8.6: aggregate integration test task. Runs integration-tagged tests
// across all subprojects (only ontology-distribution currently has any).
tasks.register("integrationTest") {
    group = "verification"
    description = "Runs real ontology integration tests across all modules"
    dependsOn(subprojects.map { it.tasks.named("integrationTest") })
}
