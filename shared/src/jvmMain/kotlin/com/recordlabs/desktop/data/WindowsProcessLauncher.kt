package com.recordlabs.desktop.data

internal actual fun launchWindowsProcessWithoutWindow(commandLine: String): Boolean = runCatching {
    val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
    val createNoWindow = 0x08000000
    val newProcessGroup = 0x00000200
    val breakawayFromJob = 0x01000000
    for (flags in listOf(createNoWindow or newProcessGroup or breakawayFromJob, createNoWindow or newProcessGroup)) {
        val startup = com.sun.jna.platform.win32.WinBase.STARTUPINFO()
        val info = com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION()
        val ok = kernel.CreateProcess(
            null, commandLine, null, null, false,
            com.sun.jna.platform.win32.WinDef.DWORD(flags.toLong()),
            null, null, startup, info,
        )
        if (ok) {
            kernel.CloseHandle(info.hProcess)
            kernel.CloseHandle(info.hThread)
            return@runCatching true
        }
    }
    false
}.getOrDefault(false)

