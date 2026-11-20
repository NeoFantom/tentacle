import java.io.File
import java.security.MessageDigest
import java.util.Properties

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

val releaseAllAbis = providers.gradleProperty("tentacle.allAbis").orElse("false").get().toBoolean()

android {
    namespace = "dev.tentacle"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tentacle"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.1"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") {
            ndk.abiFilters += setOf("arm64-v8a", "x86_64")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk.abiFilters += if (releaseAllAbis) setOf("arm64-v8a", "x86_64") else setOf("arm64-v8a")
        }
    }
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
    // The Tailscale bridge adds ~21 MB of native code per ABI. Compress it in the
    // APK (extracted at install) so downloads and in-app updates stay small.
    packaging.jniLibs.useLegacyPackaging = true
    // AGP excludes these by default; retain and merge all dependency license/notice texts.
    val licenseFiles = setOf(
        "/META-INF/LICENSE", "/META-INF/LICENSE.txt", "/META-INF/NOTICE", "/META-INF/NOTICE.txt",
        "/LICENSE", "/LICENSE.txt", "/NOTICE", "/NOTICE.txt",
    )
    packaging.resources.excludes.removeAll(licenseFiles)
    packaging.resources.merges += licenseFiles
    // Compiler/debug/OSGi metadata has no runtime consumer here. Preserve licenses and notices.
    packaging.resources.excludes += setOf(
        "kotlin/**.kotlin_builtins", "META-INF/*.version", "META-INF/*.kotlin_module",
        "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
    )
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

// Embedded Tailscale: the Go module in ../tsbridge is bound with gomobile into an
// AAR per target set under build/. Gradle tracks sources, targets, tags and version stamps.
// Toolchain lookup: Go and gomobile from PATH (or the tentacle.goBin and
// tentacle.gomobileBin Gradle properties), the NDK from ANDROID_NDK_HOME or the
// newest <sdk>/ndk/<version>. See android/README.md.
val tsbridgeDir = rootProject.file("tsbridge")
val tsbridgeArm64Aar = layout.buildDirectory.file("tsbridge/arm64/tsbridge.aar")
val tsbridgeAllAar = layout.buildDirectory.file("tsbridge/all/tsbridge.aar")
// Keep connectivity/login/reconnect features: netstack, NAT traversal, UDP, portmapper,
// DNS/fallback roots/cache, health, Tailnet Lock, ACE, netmap caching and system proxies.
// tsnet v1.104.7 references ACME/Serve methods unconditionally; omitting either fails compilation.
// CLI is opt-in upstream (ts_include_cli); this bridge never enables it.
val tsbridgeTags = listOf(
    "advertiseexitnode", "advertiseroutes", "androidbin", "androiddns", "appconnectors", "aws",
    "bird", "c2n", "captiveportal", "capture", "cliconndiag", "clientmetrics", "clientupdate", "cloud",
    "colorable", "completion", "completion_scripts", "conn25", "connreject", "dbus", "debug",
    "debugeventbus", "debugportmapper", "desktop_sessions", "doctor", "drive", "favorites",
    "flashappliance", "gro", "hujsonconf", "identityfederation", "ipnbus", "iptables", "kube",
    "linkspeed", "linuxdnsfight", "listenrawdisco", "logtail", "netlog", "networkmanager", "oauthkey",
    "osrouter", "outboundproxy", "peerapiclient", "peerapiserver", "portlist", "posture", "qrcodes",
    "relayserver", "remoteconfig", "resolved", "routecheck", "runtimemetrics", "sdnotify",
    "serviceclientprefs", "ssh", "synology", "syslog", "syspolicy", "systray", "taildrop", "tap", "tpm",
    "tundevstats", "unixsocketidentity", "useexitnode", "usermetrics", "useroutes", "wakeonlan",
    "webbrowser", "webclient",
).joinToString(",") { "ts_omit_$it" }
// Report the real Tailscale version to the control server (gomobile builds lack Go build info).
val tailscaleVersion = Regex("""tailscale\.com v([0-9][^\s]*)""")
    .find(rootProject.file("tsbridge/go.mod").readText())?.groupValues?.get(1) ?: "unknown"
val goBinProperty = providers.gradleProperty("tentacle.goBin").orNull
val gomobileBinProperty = providers.gradleProperty("tentacle.gomobileBin").orNull
val sdkDirectory: String? = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.isFile) Properties().apply { local.inputStream().use { load(it) } }
        .getProperty("sdk.dir") else null
    fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
}

fun findOnPath(name: String, extra: List<String> = emptyList()): File? =
    (extra + System.getenv("PATH").orEmpty().split(File.pathSeparator))
        .filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.canExecute() }

fun registerTsbridgeBuild(name: String, target: String, aar: Provider<RegularFile>) = tasks.register<Exec>(name) {
    group = "build"
    description = "Builds the embedded Tailscale bridge AAR for $target with gomobile."
    inputs.files(fileTree(tsbridgeDir) { include("**/*.go", "go.mod", "go.sum") })
        .withPropertyName("goSources")
    inputs.property("target", target)
    inputs.property("tags", tsbridgeTags)
    inputs.property("tailscaleVersion", tailscaleVersion)
    outputs.file(aar)
    workingDir = tsbridgeDir
    doFirst {
        val go = findOnPath("go", listOfNotNull(goBinProperty, System.getenv("GOROOT")?.let { "$it/bin" }))
            ?: throw GradleException("Go is required to build the embedded Tailscale bridge. Install the Go " +
                "version named in android/tsbridge/go.mod and put it on PATH (or set -Ptentacle.goBin=<dir>).")
        val goPath = providers.exec { commandLine(go.path, "env", "GOPATH") }.standardOutput.asText.get().trim()
        val gomobile = findOnPath("gomobile", listOfNotNull(gomobileBinProperty, "$goPath/bin"))
            ?: throw GradleException("gomobile is required: go install golang.org/x/mobile/cmd/gomobile@latest " +
                "golang.org/x/mobile/cmd/gobind@latest (or set -Ptentacle.gomobileBin=<dir>).")
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)?.takeIf { it.isDirectory }
            ?: sdkDirectory?.let { File(it, "ndk").listFiles() }?.filter { File(it, "source.properties").isFile }
                ?.maxByOrNull { dir -> dir.name.split('.').map { it.toIntOrNull() ?: 0 }
                    .fold(0L) { acc, part -> acc * 100000 + part } }
            ?: throw GradleException("The Android NDK is required: sdkmanager \"ndk;<version>\" " +
                "or set ANDROID_NDK_HOME.")
        aar.get().asFile.parentFile.mkdirs()
        environment("PATH", listOf(go.parent, gomobile.parent, System.getenv("PATH").orEmpty())
            .joinToString(File.pathSeparator))
        environment("ANDROID_NDK_HOME", ndk.path)
        sdkDirectory?.let { environment("ANDROID_HOME", it) }
        environment("CGO_ENABLED", "1")
        // Reproducible builds; also avoids "error obtaining VCS status" inside gomobile's work dir.
        environment("GOFLAGS", (listOfNotNull(System.getenv("GOFLAGS")) + "-buildvcs=false").joinToString(" "))
        commandLine(gomobile.path, "bind", "-target=$target", "-androidapi", "26",
            "-javapkg", "dev.tentacle.tailnet.bridge", "-trimpath", "-tags", tsbridgeTags,
            "-ldflags", "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 " +
                "-X tailscale.com/version.longStamp=$tailscaleVersion-tentacle " +
                "-X tailscale.com/version.shortStamp=$tailscaleVersion",
            "-o", aar.get().asFile.path, ".")
    }
}

val buildTsbridgeArm64 = registerTsbridgeBuild("buildTsbridgeArm64", "android/arm64", tsbridgeArm64Aar)
val buildTsbridgeAll = registerTsbridgeBuild("buildTsbridgeAll", "android/arm64,android/amd64", tsbridgeAllAar)
val releaseTsbridgeAar = if (releaseAllAbis) tsbridgeAllAar else tsbridgeArm64Aar
val buildReleaseTsbridge = if (releaseAllAbis) buildTsbridgeAll else buildTsbridgeArm64

dependencies {
    implementation(project(":core"))
    implementation(libs.jsch)
    debugImplementation(files(tsbridgeAllAar).builtBy(buildTsbridgeAll))
    releaseImplementation(files(releaseTsbridgeAar).builtBy(buildReleaseTsbridge))
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
