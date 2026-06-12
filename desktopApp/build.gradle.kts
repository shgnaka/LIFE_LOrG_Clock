import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val desktopPackageVersion = providers.gradleProperty("desktop.version")
    .orElse(providers.environmentVariable("ORG_CLOCK_DESKTOP_VERSION"))
    .getOrElse("1.0.0")
    .removePrefix("v")

// MSI only accepts a numeric MAJOR.MINOR.BUILD version.
val desktopMsiPackageVersion = desktopPackageVersion.substringBefore("-")
val desktopSmokePackage = providers.gradleProperty("desktop.smoke")
    .map(String::toBoolean)
    .getOrElse(false)

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
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.example.orgclock.desktop.MainKt"

        nativeDistributions {
            targetFormats(*desktopTargetFormats)
            modules("java.sql", "jdk.httpserver")
            packageName = if (desktopSmokePackage) {
                "org-clock-desktop-smoke"
            } else {
                "org-clock-desktop"
            }
            packageVersion = desktopPackageVersion
            description = "Cross-platform desktop MVP host for Org Clock."
            vendor = "shgnaka"

            windows {
                msiPackageVersion = desktopMsiPackageVersion
                if (desktopSmokePackage) {
                    upgradeUuid = "5357c806-49d7-4c2c-b07b-c62bf85cf67a"
                }
                perUserInstall = true
                menu = true
                menuGroup = "Org Clock"
                shortcut = true
            }
        }
    }
}
