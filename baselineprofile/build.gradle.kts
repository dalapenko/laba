import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.baselineprofile)
}

android {
    namespace = "com.dalapenko.laba.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        // do NOT use ANDROIDX_TEST_ORCHESTRATOR. https://issuetracker.google.com/issues/314821647
        execution = "HOST"
        managedDevices.allDevices {
            create<ManagedVirtualDevice>("pixel7Api33") {
                device = "Pixel 7"
                apiLevel = 33
                systemImageSource = "aosp"
            }
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// This is the configuration block for the Baseline Profile plugin.
// You can specify to run the generators on a managed devices or connected devices.
baselineProfile {
    managedDevices += "pixel7Api33"
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
}
