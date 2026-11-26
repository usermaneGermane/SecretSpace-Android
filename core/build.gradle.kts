// The parts of SecretSpace that are not Android: formats, cryptography,
// sync. Plain Kotlin on the JVM, so they are tested without a phone.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation(kotlin("test"))
}

tests()
fun tests() = tasks.test { useJUnitPlatform() }

// tools/lab_server.py runs the tests against a real server and says where
// it is; such a run is never "up to date".
tasks.test {
    environment("SECRETSPACE_LAB", System.getenv("SECRETSPACE_LAB") ?: "")
    environment("SECRETSPACE_LAB_REQUIRED", System.getenv("SECRETSPACE_LAB_REQUIRED") ?: "")
    if (!System.getenv("SECRETSPACE_LAB").isNullOrEmpty()) outputs.upToDateWhen { false }
}
