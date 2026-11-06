// Record Labs for Android. The music data layer (and, step by step, the UI)
// comes from :shared, the same code the desktop app uses.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version people see; the GitHub release tag is "v" + this.
val appVersionName = "2.0.1"
// Always higher than the 1.9.x releases (190000 + build number), so Android accepts
// 2.x as an update of them: 2.0.0 -> 2000000, 2.0.1 -> 2000100, 2.1.0 -> 2010000.
val appVersionCode = appVersionName.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 1_000_000) + (it.getOrElse(1) { 0 } * 10_000) + (it.getOrElse(2) { 0 } * 100) }

android {
    namespace = "com.recordlabs.android"
    compileSdk = 36
    defaultConfig {
        // Releases on GitHub (the workflow) are com.recordlabs.music.debug, the id the
        // 1.9.x app was published with, so they install over it. Local builds keep their own id: they are signed
        // with another key and would not install over a release.
        applicationId = System.getenv("RECORDLABS_APPLICATION_ID")?.takeIf(String::isNotBlank) ?: "com.recordlabs.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    // Release signing: the key stays outside the project (../record-labs-signing) and its
    // passwords in local.properties (never committed). Every release must use this key:
    // Android only updates an installed app signed with the same one.
    val signing = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    // The same names as environment variables (the release workflow, with the key of
    // the GitHub releases) win over local.properties.
    fun signingValue(name: String): String? = System.getenv(name)?.takeIf(String::isNotBlank) ?: signing.getProperty(name)
    val releaseStore = signingValue("RL_RELEASE_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("RL_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RL_RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RL_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        // The build people install. Debug builds of a Compose app run several times
        // slower (no optimisation, debuggable runtime); this one is optimised by R8 and
        // compiled ahead of time from the baseline profile (src/main/baseline-prof.txt).
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe / the shared code use newer Java APIs than some phones have.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            (System.getenv("ROBOLECTRIC_REPO") ?: System.getProperty("robolectric.repo"))?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            test.maxHeapSize = "3g"
        }
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/CONTRIBUTORS.md", "META-INF/*.md")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // FileProvider: hands a downloaded update (.apk) to Android's installer.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Installs the baseline profile (ours and Compose's) on a sideloaded install too,
    // so the app is compiled ahead of time instead of starting interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
