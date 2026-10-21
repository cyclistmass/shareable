plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("wgbg.cli.MainKt")
    applicationName = "wgbg"
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":ai"))
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // WGBG_TEST_SCALE=10 (nightly) multiplies the fuzz games and dice throws; a test input, so a new scale reruns.
    systemProperty("wgbg.testScale", System.getenv("WGBG_TEST_SCALE") ?: "1")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
