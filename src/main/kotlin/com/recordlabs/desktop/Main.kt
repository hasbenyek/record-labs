// Its own class name: the :shared module already has a com.recordlabs.desktop.MainKt
// (the app UI), and two classes with one name made the app fail to start.
@file:JvmName("RecordLabsLauncher")

package com.recordlabs.desktop

/**
 * Desktop entry point (Windows and Linux). The app itself lives in the
 * :shared module, together with the code the Android app uses.
 */
fun main(args: Array<String>) = runDesktopApp(args)
