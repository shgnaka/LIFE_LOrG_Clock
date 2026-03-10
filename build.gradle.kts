plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.test") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

tasks.register("runDesktop") {
    group = "application"
    description = "Runs the Compose Desktop host."
    dependsOn(":desktopApp:run")
}
tasks.register("verifyDesktopCompile") {
    group = "verification"
    description = "Compiles the Linux desktop MVP host."
    dependsOn(":desktopApp:compileKotlin")
}

tasks.register("packageDesktopLinux") {
    group = "distribution"
    description = "Builds Linux desktop distribution artifacts for the MVP host."
    dependsOn(":desktopApp:packageDistributionForCurrentOS")
}
