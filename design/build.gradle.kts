plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.library")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":service"))

    implementation(libs.kotlin.coroutine)
    // Desync presets carry their options as a JsonObject (core model); the picker classifies them by key.
    implementation(libs.kotlin.serialization.json)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.viewpager)
    implementation(libs.androidx.biometric)
    implementation(libs.google.material)
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation(kotlin("test-junit"))
}
