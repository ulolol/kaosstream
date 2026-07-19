plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.lagradost.cloudstream3"
version = "1.0.0"


application {
    mainClass.set("com.lagradost.cloudstream3.server.ApplicationKt")
}

dependencies {
    implementation(project(":library"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.sqlite.jdbc)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(libs.jackson.module.kotlin)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}
