plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    application
}

repositories {
    mavenCentral()
}

// The same set room-gameplay carries, minus the engine and Redis: a tournament holds no game state
// and has no live feed. There is no shared module between the two — kaniko builds each service from
// its own directory, which is the trade P5 made for the Go workers and P6 for the CQRS pair (D8).
dependencies {
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.apache.kafka:kafka-clients:4.3.1")
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-server-status-pages:3.5.2")
    implementation("io.ktor:ktor-server-auth:3.5.2")
    implementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
    // Ktor and Netty log through SLF4J; without a provider an internal error disappears.
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.5.2")
}

application {
    mainClass.set("MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
