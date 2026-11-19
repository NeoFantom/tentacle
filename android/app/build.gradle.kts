plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingValues = listOf("TENTACLE_KEYSTORE", "TENTACLE_KEYSTORE_PASSWORD", "TENTACLE_KEY_ALIAS", "TENTACLE_KEY_PASSWORD")
    .associateWith { providers.gradleProperty(it).orElse(providers.environmentVariable(it)).orNull }

require(signingValues.values.all { it.isNullOrBlank() } || signingValues.values.all { !it.isNullOrBlank() }) {
    "Release signing requires all four TENTACLE_KEYSTORE, TENTACLE_KEYSTORE_PASSWORD, TENTACLE_KEY_ALIAS and TENTACLE_KEY_PASSWORD values"
}

android {
    namespace = "dev.tentacle"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tentacle"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    buildFeatures { buildConfig = true }
    if (signingValues.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("TENTACLE_KEYSTORE")!!)
            storePassword = signingValues.getValue("TENTACLE_KEYSTORE_PASSWORD")
            keyAlias = signingValues.getValue("TENTACLE_KEY_ALIAS")
            keyPassword = signingValues.getValue("TENTACLE_KEY_PASSWORD")
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
