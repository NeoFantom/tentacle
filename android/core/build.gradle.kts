plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val dev by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("devServer") {
    group = "application"
    description = "Runs the phone2agent HTTP server against a desktop directory."
    classpath = dev.runtimeClasspath
    mainClass = "dev.phone2agent.core.dev.DevServerKt"
}
