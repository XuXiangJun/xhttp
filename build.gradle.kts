plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

val ktorVersion = "3.5.2"
val cliVersion = "0.3.6"
val coroutinesVersion = "1.11.0"
val serializationVersion = "1.11.0"
val datetimeVersion = "0.8.0"
val ioVersion = "0.9.1"

group = "org.xuxiangjun.xhttp"
version = providers.gradleProperty("appVersion").orElse("0.0.0").get()

repositories {
    mavenLocal()
    mavenCentral()
}

val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")
val generatedVersionFile = generatedVersionDir.map { it.file("Version.kt") }

// Single source of truth for the version: it lives in gradle.properties (`appVersion`),
// is injected into the build as `project.version`, and generated as an `APP_VERSION`
// constant consumed by the CLI's `--version` output.
val generateVersionFile = tasks.register("generateVersionFile") {
    group = "build"
    description = "Generates Version.kt from the project version"
    inputs.property("appVersion", project.version.toString())
    outputs.file(generatedVersionFile)
    doLast {
        val versionValue = project.version.toString()
        val output = generatedVersionFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            |package org.xuxiangjun.xhttp
            |
            |internal const val APP_VERSION = "$versionValue"
            |
            """.trimMargin()
        )
    }
}

kotlin {
    linuxX64() {
        binaries {
            executable()
        }
    }

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

    // Register the generated Version.kt into the target's own source set once it exists.
    sourceSets.getByName("linuxX64Main") {
        kotlin.srcDir(generatedVersionDir.get())
    }
}

tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(generateVersionFile)
}
