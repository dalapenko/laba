plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Detekt configuration
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
    ignoreFailures = false
    autoCorrect = false
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

// Convenience tasks
tasks.register("detektAll") {
    description = "Run Detekt on all modules"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.withType<io.gitlab.arturbosch.detekt.Detekt>() })
}

tasks.register("detektFormat") {
    description = "Auto-fix formatting issues with Detekt"
    group = "formatting"
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().matching {
        it.name.contains("Format") || it.autoCorrect
    })
}
