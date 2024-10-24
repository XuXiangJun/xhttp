plugins {
    kotlin("multiplatform") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
//    id("org.jetbrains.kotlinx.atomicfu") version "0.25.0"
}

val ktorVersion = "3.0.0"

group = "org.xuxiangjun.xhttp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.5.3")
            }
        }
    }

    // mingwX64("native") // on Windows
    // macosX64("native") //on macOS
    // linuxX64("native") // on Linux
    linuxX64("native") {
        binaries {
            executable()
        }
    }
}
