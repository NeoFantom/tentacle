plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingValues = listOf("P2A_KEYSTORE", "P2A_KEYSTORE_PASSWORD", "P2A_KEY_ALIAS", "P2A_KEY_PASSWORD")
    .associateWith { providers.gradleProperty(it).orElse(providers.environmentVariable(it)).orNull }

require(signingValues.values.all { it.isNullOrBlank() } || signingValues.values.all { !it.isNullOrBlank() }) {
    "Release signing requires all four P2A_KEYSTORE, P2A_KEYSTORE_PASSWORD, P2A_KEY_ALIAS and P2A_KEY_PASSWORD values"
}

android {
    namespace = "dev.phone2agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phone2agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    if (signingValues.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("P2A_KEYSTORE")!!)
            storePassword = signingValues.getValue("P2A_KEYSTORE_PASSWORD")
            keyAlias = signingValues.getValue("P2A_KEY_ALIAS")
            keyPassword = signingValues.getValue("P2A_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
