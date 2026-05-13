plugins {
    id("maven-publish")
    kotlin("jvm")
    id("kotlin-language-server.publishing-conventions")
    id("kotlin-language-server.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.org.tinylog.api)

    implementation(platform(project(":platform")))

    implementation(kotlin("stdlib"))
    implementation(libs.org.jetbrains.exposed.core)
    implementation(libs.org.jetbrains.exposed.dao)
    implementation(libs.bundles.tinylog)

    testImplementation(libs.hamcrest.all)
    testImplementation(libs.junit.junit)
}
