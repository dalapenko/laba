import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
}

// Apply Firebase plugins only when google-services.json is present (release builds / CI)
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val localProperties = Properties()
rootProject.file("local.properties").also { f ->
    if (f.exists()) f.inputStream().use { localProperties.load(it) }
}

android {
    namespace = "com.dalapenko.laba"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dalapenko.laba"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.2.2"

        testInstrumentationRunner = "com.dalapenko.laba.LabaTestRunner"

        @Suppress("UnstableApiUsage")
        androidResources.localeFilters.addAll(setOf("en", "ru"))

        buildConfigField("String", "STORE_LABEL", "\"\"")
    }

    val hasSigningConfig = !localProperties.getProperty("signing.storeFile").isNullOrEmpty()

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(localProperties.getProperty("signing.storeFile"))
                storePassword = localProperties.getProperty("signing.storePassword") ?: ""
                keyAlias = localProperties.getProperty("signing.keyAlias") ?: ""
                keyPassword = localProperties.getProperty("signing.keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base {
    archivesName.set("laba")
}

tasks.whenTaskAdded {
    if (name == "assembleRelease") {
        doFirst {
            if (!file("google-services.json").exists()) {
                val message = "google-services.json is missing — release build requires Firebase config"
                if (System.getenv("CI") != null) error(message) else logger.warn("WARNING: $message")
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    detektPlugins(libs.detekt.formatting)

    // Core
    implementation(libs.androidx.core.ktx)

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Activity
    implementation(libs.androidx.activity.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // DocumentFile
    implementation(libs.androidx.documentfile)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // AppCompat (AppCompatActivity + setApplicationLocales)
    implementation(libs.androidx.appcompat)

    // Coil
    implementation(libs.coil.compose)

    // Firebase (release only — requires google-services.json; excluded from debug builds)
    val firebaseBom = platform(libs.firebase.bom)
    releaseImplementation(firebaseBom)
    releaseImplementation(libs.firebase.analytics)
    releaseImplementation(libs.firebase.crashlytics)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.koin.test)

    // Android UI Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kaspresso)
    androidTestImplementation(libs.kaspresso.compose)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.tooling)
    androidTestImplementation(libs.compose.ui)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Fix for drawerlayout resource linking issue in tests
    debugImplementation(libs.androidx.drawerlayout)
    debugImplementation(libs.androidx.ui.test.manifest)
}
