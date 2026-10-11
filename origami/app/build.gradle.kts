plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.maynards.origami"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.maynards.origami"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Origami's realistic 3D: Filament (Apache-2.0) renderer, glTF loader and viewer utilities, one release
    implementation("com.google.android.filament:filament-android:1.71.4")
    implementation("com.google.android.filament:gltfio-android:1.71.4")
    implementation("com.google.android.filament:filament-utils-android:1.71.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.8.9")
    testImplementation("org.json:json:20250517")
}
