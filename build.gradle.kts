import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

val ktorVersion = "3.5.2"
val coroutinesVersion = "1.11.0"
val serializationVersion = "1.11.0"
val ioVersion = "0.9.1"

group = "org.xuxiangjun.xhttp"

val appVersion: Provider<String> = providers.gradleProperty("appVersion").orElse("0.0.0")
version = appVersion.get()

repositories {
    mavenCentral()
}

// Single source of truth for the version: it lives in gradle.properties (`appVersion`) and is
// generated into an `APP_VERSION` constant that `--version` and the User-Agent read.
val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")

val generateVersionFile = tasks.register("generateVersionFile") {
    group = "build"
    description = "Generates Version.kt from the project version"
    // Captured at configuration time: touching `project` inside doLast breaks the configuration cache.
    val versionValue = appVersion.get()
    val outputDir = generatedVersionDir
    inputs.property("appVersion", versionValue)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().file("Version.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
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
    compilerOptions {
        optIn.addAll(
            "kotlinx.cinterop.ExperimentalForeignApi",
            "kotlin.io.encoding.ExperimentalEncodingApi",
        )
        allWarningsAsErrors.set(false)
    }

    linuxX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:$ioVersion")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    sourceSets.getByName("linuxX64Main") {
        // Passing the task provider lets Gradle infer the dependency; no `dependsOn` wiring needed.
        kotlin.srcDir(generateVersionFile)
    }
}

val releaseBinary = kotlin.linuxX64().binaries.getExecutable(NativeBuildType.RELEASE)
val installDir = layout.buildDirectory.dir("install")

// Produces a stripped binary named `xhttp` (rather than `xhttp.kexe`) ready to drop on a PATH.
val installBinary = tasks.register("installBinary") {
    group = "distribution"
    description = "Copies the release binary to build/install/xhttp and strips it"
    dependsOn(releaseBinary.linkTaskProvider)
    val source = releaseBinary.outputFile
    val target = installDir.map { it.file("xhttp") }
    inputs.file(source)
    outputs.file(target)
    doLast {
        val destination = target.get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
        destination.setExecutable(true)
        providers.exec {
            commandLine("strip", destination.absolutePath)
            isIgnoreExitValue = true
        }.result.get()
    }
}

tasks.register<Tar>("distTar") {
    group = "distribution"
    description = "Builds a release tarball with the binary, docs and shell completions"
    dependsOn(installBinary)
    archiveBaseName.set("xhttp")
    archiveVersion.set(version.toString())
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    into("xhttp-$version") {
        from(installDir) { include("xhttp") }
        from("README.md", "LICENSE")
        from("docs") { into("docs") }
        from("completions") { into("completions") }
    }
}
