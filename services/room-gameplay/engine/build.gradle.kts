plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// No Ktor, no JDBC, no Kafka — deliberately. If this list ever grows a framework, the separation
// that makes the rules testable in isolation has been lost (plan D1).
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-property:6.2.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
