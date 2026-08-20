plugins {
    kotlin("jvm")
    // Version comes from the root project's plugin block; declaring it twice is an error.
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

// No Ktor, no JDBC, no Kafka — deliberately. kotlinx.serialization is the one exception (D15): the
// events ARE the persisted and published contract, and hand-writing a codec for twenty-two types
// would put replay correctness in three hundred lines of mapping instead of in the compiler.
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-property:6.2.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
