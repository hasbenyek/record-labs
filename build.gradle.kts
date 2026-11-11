import java.security.SecureRandom
import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    // Used by :shared and :androidApp (the Android app; see settings.gradle.kts).
    kotlin("multiplatform") version "2.3.21" apply false
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
}

// Windows and Linux are built from the same sources. Each build targets the OS
// it runs on (jpackage can't cross-build), so Linux packages come from a Linux
// machine or CI.
val hostOs: String = System.getProperty("os.name").lowercase()
val isWindowsHost = hostOs.startsWith("windows")
val isLinuxHost = hostOs.contains("linux")
val javaFxClassifier = if (isWindowsHost) "win" else if (isLinuxHost) "linux" else "mac"
val nativeResourceDir = if (isWindowsHost) "windows-x64" else "linux-x64"

group = "com.recordlabs.desktop"
// The one place for the app version (installers, update checks). Change it with
// "gradlew setVersion -PnewVersion=1.2.3", which also updates AppInfo.BUILD_VERSION.
val appVersion = "0.1.8"
version = appVersion

kotlin {
    jvmToolchain(21)
}

// The Listen Together key is generated in shared/build.gradle.kts (the app code lives in :shared).

dependencies {
    // Code shared with the Android app (data layer and, step by step, the UI).
    implementation(project(":shared"))
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.ibm.icu:icu4j:76.1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("com.github.MetrolistGroup:MetrolistExtractor:f0a00f5") {
        exclude(group = "com.google.protobuf")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
    implementation("org.openjfx:javafx-base:21.0.2:$javaFxClassifier")
    implementation("org.openjfx:javafx-graphics:21.0.2:$javaFxClassifier")
    implementation("org.openjfx:javafx-controls:21.0.2:$javaFxClassifier")
    implementation("org.openjfx:javafx-media:21.0.2:$javaFxClassifier")
    // JavaFX WebView is not used by the app; Linux packages leave it out (~40 MB).
    if (isWindowsHost) implementation("org.openjfx:javafx-web:21.0.2:$javaFxClassifier")
    // Linux Google login with Firefox reads the temporary profile's cookies.sqlite.
    if (!isWindowsHost) runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    // Linux media keys and desktop media controls (MPRIS over D-Bus). Windows
    // builds only compile against it; the class is never loaded there.
    val dbusJava = listOf("com.github.hypfvieh:dbus-java-core:5.1.1", "com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")
    dbusJava.forEach { if (isWindowsHost) compileOnly(it) else implementation(it) }
    testImplementation(kotlin("test"))
}

val copyJavaFxMediaNatives by tasks.registering(Copy::class) {
    val nativeJars = configurations.runtimeClasspath.get().filter {
        (it.name.startsWith("javafx-media-") || it.name.startsWith("javafx-web-")) && it.name.endsWith(".jar")
    }
    from(nativeJars.map { zipTree(it) }) {
        include("*.dll")
    }
    into(layout.projectDirectory.dir("src/appResources/windows-x64"))
}

val copyVlcNatives by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/main/resources/vlc"))
    into(layout.projectDirectory.dir("src/appResources/windows-x64/vlc"))
}

val compileNativePlayer by tasks.registering(Exec::class) {
    val buildScript = layout.projectDirectory.file("compile_native_player.ps1")
    val nativeSources = layout.projectDirectory.dir("native-player")
    val helperExecutable = layout.projectDirectory.file("src/appResources/windows-x64/RecordLabs.NativePlayer.exe")
    inputs.file(buildScript)
    inputs.dir(nativeSources)
    outputs.file(helperExecutable)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", buildScript.asFile.absolutePath,
    )
}

compose.desktop {
    application {
        mainClass = "com.recordlabs.desktop.RecordLabsLauncher"
        jvmArgs("-Djava.library.path=src/appResources/$nativeResourceDir")
        // Linux: dragging the borderless floating lyrics window hands the pointer
        // to the window manager, which needs AWT's X11 connection (LinuxX11Window).
        if (!isWindowsHost) jvmArgs("--add-exports=java.desktop/sun.awt=ALL-UNNAMED", "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED")
        // Memory: a ceiling for the Java heap and a collector that hands unused
        // memory back to Windows (periodic GC while idle, smaller free margin).
        jvmArgs(
            "-Xmx1536m",
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=15000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:+UseStringDeduplication",
        )

        nativeDistributions {
            val librarySeparator = if (isWindowsHost) ";" else ":"
            jvmArgs("-Djava.library.path=${'$'}APPDIR/resources$librarySeparator${'$'}APPDIR/resources/vlc")
            modules(
                "jdk.unsupported",
                "jdk.unsupported.desktop",
                "jdk.httpserver",
                "java.naming",
                "java.management",
                "jdk.crypto.ec",
                "jdk.crypto.cryptoki",
            )
            // Windows certificate store access exists only in Windows JDKs.
            if (isWindowsHost) modules("jdk.crypto.mscapi")
            // Windows keeps the full runtime as before. Linux ships only the modules
            // the app needs (jdeps + reflection users such as locale data, TLS, Rhino).
            includeAllModules = isWindowsHost
            if (!isWindowsHost) modules(
                "java.desktop", "java.datatransfer", "java.instrument", "java.logging", "java.net.http",
                "java.prefs", "java.scripting", "java.sql", "java.xml", "java.security.jgss",
                "jdk.localedata", "jdk.charsets", "jdk.zipfs", "jdk.jsobject", "jdk.xml.dom", "jdk.net",
                "jdk.security.auth", "jdk.accessibility", "jdk.naming.dns",
            )
            outputBaseDir.set(project.layout.buildDirectory.dir("compose-$appVersion-final/binaries"))
            if (isWindowsHost) {
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                )
            } else {
                // .deb (Ubuntu/Debian) and .rpm (Fedora/openSUSE). The AppImage
                // and the Arch package are made from createDistributable by
                // packaging/linux scripts.
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                )
            }
            packageName = "Record Labs"
            packageVersion = appVersion
            description = "Record Labs desktop music player"
            vendor = "Record Labs"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                iconFile.set(project.file("src/main/resources/recordlabs.ico"))
            }
            linux {
                packageName = "record-labs"
                shortcut = true
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "sound"
                debMaintainer = "Record Labs <hasbenyek@users.noreply.github.com>"
                rpmLicenseType = "Proprietary"
                iconFile.set(project.file("src/main/resources/recordlabs.png"))
            }
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/appResources"))
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// Windows VLC and the .NET helper live in src/main/resources for the Windows
// build; Linux packages bundle their own VLC, so keep ~185 MB of DLLs out of the jar.
if (!isWindowsHost) {
    tasks.named<ProcessResources>("processResources") {
        exclude("vlc/**", "win-x64/**", "*.dll")
    }
}

// Linux: "./gradlew run" works from a fresh checkout like "gradlew.bat run" on
// Windows; the bundled helper programs (yt-dlp, Deno) are downloaded on first use.
if (isLinuxHost) {
    val fetchLinuxTools by tasks.registering(Exec::class) {
        val toolsDir = layout.projectDirectory.dir("src/appResources/linux-x64")
        onlyIf { !toolsDir.file("yt-dlp").asFile.exists() || !toolsDir.file("deno").asFile.exists() }
        commandLine("bash", layout.projectDirectory.file("packaging/linux/fetch-tools.sh").asFile.absolutePath)
    }
    tasks.matching { it.name == "run" || it.name == "createDistributable" }.configureEach { dependsOn(fetchLinuxTools) }
}

// The .NET media helper, JavaFX DLLs and Windows VLC are Windows-only inputs.
if (isWindowsHost) {
    tasks.matching { it.name == "prepareAppResources" || it.name == "packageExe" || it.name == "packageMsi" }
        .configureEach { dependsOn(copyJavaFxMediaNatives, compileNativePlayer, copyVlcNatives) }
}

// The installers are made with jpackage and our WiX template (src/installer/wix): it adds
// the Windows Firewall rules Record Labs Connect needs (UDP 45922, TCP 45923) on install
// and removes them on uninstall. Compose's own packageExe/packageMsi left them out, so a
// phone could not reach the PC (the .msi had no rules at all).
fun registerInstallerWithFirewall(taskName: String, type: String) = tasks.register(taskName, Exec::class) {
    dependsOn("createDistributable", "unzipWix")
    val appImage = layout.buildDirectory.dir("compose-$appVersion-final/binaries/main/app/Record Labs")
    val destination = layout.buildDirectory.dir("compose-$appVersion-firewall-/binaries/main/$type")
    val installer = destination.map { it.file("Record Labs-$appVersion.$type") }
    val wixResources = layout.projectDirectory.dir("src/installer/wix")
    val jpackage = layout.file(
        providers.provider { File(System.getProperty("java.home"), "bin/jpackage.exe") }
    )
    outputs.file(installer)
    // Rebuilt whenever the app or the installer resources change (it used to be skipped
    // as long as an older installer of the same version was there).
    inputs.dir(appImage)
    inputs.dir(wixResources)
    doFirst {
        destination.get().asFile.mkdirs()
        installer.get().asFile.delete()
    }
    executable(jpackage.get().asFile.absolutePath)
    args(
        "--type", type,
        "--name", "Record Labs",
        "--app-version", appVersion,
        "--vendor", "Record Labs",
        "--description", "Record Labs desktop music player",
        "--app-image", appImage.get().asFile.absolutePath,
        "--dest", destination.get().asFile.absolutePath,
        "--resource-dir", wixResources.asFile.absolutePath,
        "--install-dir", "Record Labs",
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        "--verbose",
    )
    environment(
        "PATH",
        layout.buildDirectory.dir("wix311").get().asFile.absolutePath +
            File.pathSeparator + System.getenv("PATH"),
    )
}

val packageExeWithFirewall = registerInstallerWithFirewall("packageExeWithFirewall", "exe")
val packageMsiWithFirewall = registerInstallerWithFirewall("packageMsiWithFirewall", "msi")

if (isWindowsHost) afterEvaluate {
    tasks.named("packageExe").configure {
        actions.clear()
        dependsOn(packageExeWithFirewall)
    }
    tasks.named("packageMsi").configure {
        actions.clear()
        dependsOn(packageMsiWithFirewall)
    }
}

// "gradlew setVersion -PnewVersion=1.2.3": sets the version here and in
// AppInfo.BUILD_VERSION (AppSettings.kt). Then commit and push a tag v1.2.3 to release.
val setVersion by tasks.registering {
    val newVersion = providers.gradleProperty("newVersion")
    doLast {
        val target = newVersion.orNull?.trim()?.removePrefix("v")
            ?: error("Use: gradlew setVersion -PnewVersion=1.2.3")
        require(Regex("""\d+\.\d+\.\d+""").matches(target)) { "Version must look like 1.2.3" }
        fun edit(path: String, pattern: Regex, replacement: String) {
            val file = rootProject.file(path)
            val text = file.readText()
            check(pattern.containsMatchIn(text)) { "Version not found in $path" }
            file.writeText(text.replaceFirst(pattern, replacement))
        }
        edit("build.gradle.kts", Regex("""val appVersion = "[^"]*""""), "val appVersion = \"$target\"")
        edit("shared/src/jvmSharedMain/kotlin/com/recordlabs/desktop/data/AppSettings.kt", Regex("""BUILD_VERSION = "[^"]*""""), "BUILD_VERSION = \"$target\"")
        println("Version set to $target. Commit, then tag and push: git tag v$target && git push origin v$target")
    }
}
