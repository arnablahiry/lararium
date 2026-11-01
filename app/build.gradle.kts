import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.familydocs.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.familydocs.app"
        minSdk = 26
        targetSdk = 35
        // Every release must have a higher versionCode than the last one installed, or Android refuses the update.
        versionCode = (project.findProperty("familydocs.versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("familydocs.versionName") as String?) ?: "0.1.0"
    }

    // Updates only install over the existing app (keeping all data) when they're signed with the SAME key.
    // keystore.properties + the .jks file live outside git; see README → "Shipping updates".
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // the exported schemas, for the database upgrade test (unit tests only: nothing extra in the app)
    sourceSets { getByName("test").assets.srcDir("$projectDir/schemas") }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Room's Gradle plugin keeps one schema copy per build variant, so debug and release KSP runs can't clash over the same file.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Encrypted database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation("${libs.sqlcipher.android.get()}@aar")
    implementation(libs.androidx.sqlite.ktx)

    // On-device OCR
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.exifinterface)

    // Google sign-in (profile photo) + Drive authorization
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.biometric)

    // QR codes for invites (encoding only)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // database upgrades checked against every past version's schema (app/schemas), on the JVM
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
