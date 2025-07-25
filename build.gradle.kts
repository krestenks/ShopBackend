plugins {
    kotlin("jvm") version "1.9.10"
    kotlin("plugin.serialization") version "1.9.0"
    application
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}
val ktorVersion = "2.3.7"

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:${ktorVersion}")
    implementation("io.ktor:ktor-server-auth-jwt:${ktorVersion}")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:${ktorVersion}")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

application {
    mainClass.set("MainKt")
}
