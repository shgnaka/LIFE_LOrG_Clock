import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val desktopTargetFormats = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> arrayOf(TargetFormat.Dmg)
    org.gradle.internal.os.OperatingSystem.current().isWindows -> arrayOf(TargetFormat.Msi)
    else -> arrayOf(TargetFormat.AppImage, TargetFormat.Deb)
}

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.example.orgclock.desktop.MainKt"

        nativeDistributions {
            targetFormats(*desktopTargetFormats)
            packageName = "org-clock-desktop"
            packageVersion = "1.0.0"
            description = "Linux-first desktop MVP host for Org Clock."
        }
    }
}
