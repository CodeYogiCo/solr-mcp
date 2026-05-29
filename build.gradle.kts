plugins {
    kotlin("jvm") version "2.3.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("com.google.cloud.tools.jib") version "3.4.4"
}

group = "co.codeyogi"
version = "0.1.0"

repositories {
    mavenCentral()
}

val mcpVersion = "1.1.3"
val http4kVersion = "6.48.0.0"
val solrjVersion = "9.10.1"

dependencies {
    // --- MCP Java SDK (protocol core + Jackson JSON mapper) ---
    implementation("io.modelcontextprotocol.sdk:mcp-core:$mcpVersion")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:$mcpVersion")

    // --- Apache Solr client ---
    implementation("org.apache.solr:solr-solrj:$solrjVersion")
    implementation("org.apache.commons:commons-csv:1.12.0")

    // --- http4k (HTTP edge: routing, SSE, Jetty backend) ---
    implementation(platform("org.http4k:http4k-bom:$http4kVersion"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-realtime-core")
    implementation("org.http4k:http4k-server-jetty")

    // --- JSON (Kotlin support for our own tool-result serialization) ---
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // --- JWT validation for HTTP mode ---
    implementation("com.nimbusds:nimbus-jose-jwt:9.48")

    // --- Logging: slf4j-simple writes to System.err, keeping stdout clean for STDIO ---
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- Tests ---
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("co.codeyogi.solrmcp.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Functional tests drive a real Solr via Testcontainers + docker compose.
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.shadowJar {
    archiveClassifier.set("all") // runnable fat jar: solr-mcp-<version>-all.jar
    mergeServiceFiles() // preserve META-INF/services (MCP JSON mapper SPI, etc.)
}

// Container image, no Dockerfile/daemon required.
//   ./gradlew jibDockerBuild   -> build into local Docker (solr-mcp:<version>)
//   ./gradlew jib              -> build and push to the registry
// Jib uses a clean `java -cp ... MainKt` entrypoint (no launcher script writing
// to stdout), which keeps the MCP STDIO JSON-RPC stream uncorrupted. The default
// profile is stdio; set PROFILES=http at runtime for HTTP mode.
jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            platform { architecture = "amd64"; os = "linux" }
            platform { architecture = "arm64"; os = "linux" }
        }
    }
    to {
        image = "ghcr.io/codeyogico/solr-mcp"
        tags = setOf("latest", version.toString())
    }
    container {
        mainClass = "co.codeyogi.solrmcp.MainKt"
        ports = listOf("8080")
        environment = mapOf("PROFILES" to "stdio", "SOLR_URL" to "http://localhost:8983/solr/")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
