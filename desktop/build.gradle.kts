import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// macOS wants versions starting at 1 (the phone app's 0.x numbering can't be used here)
val macVersion = providers.environmentVariable("MAC_VERSION").orElse("1.0.0").get()

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // the same libraries the shared Android code uses
    implementation("org.json:json:20240303")
    implementation("androidx.room:room-common:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // showing PDF scans
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
}

kotlin { jvmToolchain(17) }

// The Android app's plain Kotlin (no Android in it): documents and their types, countries, events, expiry dates,
// invites and group codes, the vault's data classes, field names, encryption and Drive access, the icons' paths.
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../app/src/main/java") {
        include(
            "com/familydocs/app/domain/**",
            "com/familydocs/app/data/Entities.kt",
            "com/familydocs/app/ocr/FieldMerge.kt",
            "com/familydocs/app/backup/BackupCrypto.kt",
            "com/familydocs/app/backup/DriveClient.kt",
            "com/familydocs/app/ui/ds/IconPaths.kt",
        )
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}

// The Google sign-in client for the Mac (a "Desktop app" OAuth client in the same Google Cloud project as the phone),
// from the build's environment (GitHub secrets), never from the repository.
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val id = providers.environmentVariable("MAC_GOOGLE_CLIENT_ID").orElse("")
    val secret = providers.environmentVariable("MAC_GOOGLE_CLIENT_SECRET").orElse("")
    inputs.property("id", id); inputs.property("secret", secret); inputs.property("version", macVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/familydocs/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package com.familydocs.desktop\n\n" +
                "object BuildInfo {\n" +
                "    const val CLIENT_ID = \"${id.get().trim()}\"\n" +
                "    const val CLIENT_SECRET = \"${secret.get().trim()}\"\n" +
                "    const val VERSION = \"$macVersion\"\n" +
                "}\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(sharedSources)
    kotlin.srcDir(buildInfo)
}

// The phone's pictures (card art, flags, characters, event art) and fonts, unchanged.
tasks.processResources {
    from("../app/src/main/res/drawable-nodpi") { into("drawable") }
    from("../app/src/main/res/font") { into("font") }
    from("icons/app_icon.png") { into("drawable") }
}

compose.desktop {
    application {
        mainClass = "com.familydocs.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Lararium"
            packageVersion = macVersion
            description = "Your family's documents, from your Lararium vault"
            copyright = "Arnab Lahiry"
            vendor = "Arnab Lahiry"
            includeAllModules = true
            windows {
                menuGroup = "Lararium"
                // the same id for every version, so a new installer replaces the old one
                upgradeUuid = "6f1c9b8e-4d2a-4b7e-9a51-3c8e2f0d7a14"
                perUserInstall = true
                shortcut = true
                dirChooser = false
                iconFile.set(project.file("icons/Lararium.ico"))
            }
            linux {
                packageName = "lararium"
                debMaintainer = "Arnab Lahiry <arnablahiry08@gmail.com>"
                menuGroup = "Office"
                appCategory = "Office"
                shortcut = true
                iconFile.set(project.file("icons/Lararium.png"))
            }
            macOS {
                bundleID = "com.familydocs.mac"
                dockName = "Lararium"
                iconFile.set(project.file("icons/Lararium.icns"))
                appCategory = "public.app-category.productivity"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
