import java.util.Properties

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Record Labs Windows"

// Code shared by the desktop app (this root project) and the Android app.
include(":shared")

// The Android app is only part of the build where an Android SDK is installed
// (sdk.dir in local.properties, or ANDROID_HOME). Without it everything builds
// as before: desktop only, and :shared without its Android target.
val androidSdkAvailable: Boolean = run {
    val properties = Properties()
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use(properties::load)
    val dir = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    dir != null && File(dir).isDirectory
}
gradle.extensions.extraProperties["recordLabs.androidSdk"] = androidSdkAvailable
if (androidSdkAvailable) {
    include(":androidApp")
    // The Android app's YouTube Music client (playback engine of the original Android app).
    include(":innertube")
}
