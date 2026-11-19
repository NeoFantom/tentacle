import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val updateRepo = providers.gradleProperty("tentacle.updateRepo").orElse("").get().trim()
require(updateRepo.isEmpty() || updateRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "tentacle.updateRepo must be empty or an owner/repository pair"
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
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
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
    implementation(libs.jsch)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

android.applicationVariants.all {
    if (buildType.name == "release") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "tentacle-${versionName}.apk"
        }
    }
}

val sha256ReleaseApk by tasks.registering {
    group = "build"
    description = "Writes the SHA-256 checksum next to the release APK."
    dependsOn("assembleRelease")
    val outputDirectory = layout.buildDirectory.dir("outputs/apk/release")
    val releaseApk = outputDirectory.map {
        it.file("tentacle-${android.defaultConfig.versionName}.apk")
    }
    val releaseChecksum = releaseApk.map {
        it.asFile.resolveSibling("${it.asFile.name}.sha256")
    }
    inputs.file(releaseApk)
    outputs.file(releaseChecksum)
    doLast {
        val apk = releaseApk.get().asFile
        check(apk.isFile) { "Release APK was not produced: ${apk.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        apk.resolveSibling("${apk.name}.sha256").writeText("$checksum  ${apk.name}\n")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(sha256ReleaseApk)
}
