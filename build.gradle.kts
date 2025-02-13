plugins {
    kotlin("multiplatform") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
//    id("org.jetbrains.kotlinx.atomicfu") version "0.25.0"
}

val ktorVersion = "3.1.0"
val cliVersion = "0.3.6"
val coroutinesVersion = "1.10.1"
val serializationVersion = "1.8.0"
val datetimeVersion = "0.6.2"
val ioVersion = "0.5.3"

group = "org.xuxiangjun.xhttp"
version = "0.9.2"

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-cli:$cliVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:$ioVersion")
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
