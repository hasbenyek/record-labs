// YouTube Music InnerTube client of the Record Labs Android app (folder 3),
// used by the Android build for playback (stream resolution, poToken, cipher).
plugins {
    id("com.android.library")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.recordlabs.innertube"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("io.ktor:ktor-client-encoding:3.5.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("com.github.MetrolistGroup:MetrolistExtractor:f0a00f5") {
        exclude(group = "com.google.protobuf")
    }
    implementation("com.jakewharton.timber:timber:5.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
