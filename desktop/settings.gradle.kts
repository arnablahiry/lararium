// The Mac app: its own Gradle build (Compose for Desktop), next to the Android app. It shares the Android app's plain
// Kotlin code (documents, events, invites, encryption) and its pictures and fonts, copied in at build time.
// Build: ./gradlew -p desktop run   (or packageDmg for the installer)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "Lararium-desktop"
