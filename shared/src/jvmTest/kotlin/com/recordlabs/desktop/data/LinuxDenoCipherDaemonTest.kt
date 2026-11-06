package com.recordlabs.desktop.data

import com.recordlabs.desktop.data.cipher.PersistentDenoDaemon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signature/n-parameter decoder runs YouTube's player.js functions in a
 * long-lived Deno process. This checks that daemon with the bundled Linux Deno
 * (src/appResources/linux-x64/deno, from packaging/linux/fetch-tools.sh).
 */
class LinuxDenoCipherDaemonTest {
    @Test
    fun denoDaemonEvaluatesPlayerFunctions() {
        if (Platform.isWindows) return
        val deno = BundledTools.find("deno") ?: run {
            println("LinuxDenoCipherDaemonTest skipped (no deno; run packaging/linux/fetch-tools.sh)")
            return
        }
        // Stand-ins for the decipher functions extracted from player.js.
        val playerJs = """
            var Xy = { sw: function(a, b) { var c = a[0]; a[0] = a[b % a.length]; a[b % a.length] = c; }, rv: function(a) { a.reverse(); } };
            function sig(a) { a = a.split(""); Xy.rv(a); Xy.sw(a, 2); return a.join(""); }
            function nfn(a) { return a.toUpperCase() + "_n"; }
        """.trimIndent()
        PersistentDenoDaemon.ensureRunning(deno, playerJs)
        assertEquals("bcda", PersistentDenoDaemon.eval("sig('abcd')"))
        assertEquals("XYZ_n", PersistentDenoDaemon.eval("nfn('xyz')"))
        // A broken expression answers "no result" instead of hanging the daemon.
        assertEquals(null, PersistentDenoDaemon.eval("notDefined(1)"))
        assertEquals("XYZ_n", PersistentDenoDaemon.eval("nfn('xyz')"))

        // An expression that never finishes gives up after the timeout
        // instead of holding the daemon lock forever; the next call restarts it.
        val started = System.currentTimeMillis()
        assertEquals(null, PersistentDenoDaemon.eval("(function(){ while (true) {} })()"))
        assertTrue(System.currentTimeMillis() - started < 15_000, "timed out")
        PersistentDenoDaemon.ensureRunning(deno, playerJs)
        assertEquals("bcda", PersistentDenoDaemon.eval("sig('abcd')"))
    }
}
