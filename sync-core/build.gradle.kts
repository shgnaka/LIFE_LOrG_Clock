@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.JavaExec

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
        val androidUnitTest by getting { dependencies { implementation(kotlin("test")); implementation("junit:junit:4.13.2") } }
        val jvmTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

android {
    namespace = "io.github.shgnaka.orgclock.synccore"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val syncCoreBenchmarkCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
val syncCoreBenchmarkReport = layout.buildDirectory.file("reports/sync-core/benchmark.txt")

tasks.register<JavaExec>("syncCoreBenchmark") {
    group = "verification"
    description = "Runs the JVM sync-core benchmark gate and writes p50/p95/max timings."

    dependsOn("jvmTestClasses")
    classpath = files(syncCoreBenchmarkCompilation.output.allOutputs, syncCoreBenchmarkCompilation.runtimeDependencyFiles)
    mainClass.set("io.github.shgnaka.orgclock.synccore.benchmark.SyncCoreBenchmarkMainKt")
    args(syncCoreBenchmarkReport.get().asFile.absolutePath)
    outputs.file(syncCoreBenchmarkReport)
}
