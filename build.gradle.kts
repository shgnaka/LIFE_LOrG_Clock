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
tasks.register("verifyDesktopCurrentOs") {
    group = "verification"
    description = "Compiles the desktop host for the current operating system."
    dependsOn(":desktopApp:compileKotlin")
}

tasks.register("verifyDesktopCompile") {
    group = "verification"
    description = "Alias for verifyDesktopCurrentOs."
    dependsOn("verifyDesktopCurrentOs")
}

tasks.register("packageDesktopCurrentOs") {
    group = "distribution"
    description = "Builds desktop distribution artifacts for the current operating system."
    dependsOn(":desktopApp:packageDistributionForCurrentOS")
}

tasks.register("packageDesktopLinux") {
    group = "distribution"
    description = "Alias for packageDesktopCurrentOs."
    dependsOn("packageDesktopCurrentOs")
}
