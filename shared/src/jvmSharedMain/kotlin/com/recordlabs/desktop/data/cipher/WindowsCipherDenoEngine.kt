package com.recordlabs.desktop.data.cipher

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PersistentDenoDaemon {
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var reader: java.io.BufferedReader? = null
    private var currentHash: Int = 0
    private var scriptFile: File? = null

    @Synchronized
    fun ensureRunning(denoFile: File, playerJs: String) {
        if (process?.isAlive == true && currentHash == playerJs.hashCode()) return

        process?.destroyForcibly()
        scriptFile?.delete()
        
        currentHash = playerJs.hashCode()
        
        val daemonJs = """
            ${playerJs}
            
            const decoder = new TextDecoder();
            const encoder = new TextEncoder();
            
            async function loop() {
                const buffer = new Uint8Array(4096);
                let currentLine = "";
                while(true) {
                    const n = await Deno.stdin.read(buffer);
                    if (n === null || n === 0) break;
                    const chunk = decoder.decode(buffer.subarray(0, n));
                    currentLine += chunk;
                    
                    let newlineIdx;
                    while ((newlineIdx = currentLine.indexOf('\n')) !== -1) {
                        const line = currentLine.substring(0, newlineIdx).trim();
                        currentLine = currentLine.substring(newlineIdx + 1);
                        
                        if (line.length > 0) {
                            try {
                                const res = eval(line);
                                const out = encoder.encode("###RES###" + res + "\n");
                                await Deno.stdout.write(out);
                            } catch(e) {
                                const err = encoder.encode("###RES###ERROR\n");
                                await Deno.stdout.write(err);
                            }
                        }
                    }
                }
            }
            loop();
        """.trimIndent()
        
        val tempFile = File.createTempFile("recordlabs_daemon_", ".js")
        tempFile.writeText(daemonJs)
        scriptFile = tempFile
        
        val pb = ProcessBuilder(denoFile.absolutePath, "run", tempFile.absolutePath)
        pb.redirectErrorStream(true)
        process = pb.start()
        
        writer = process!!.outputStream.bufferedWriter()
        reader = process!!.inputStream.bufferedReader()
    }

    @Synchronized
    fun eval(expression: String): String? {
        try {
            val w = writer ?: return null
            val r = reader ?: return null
            
            val singleLineExpr = expression.replace("\n", " ").replace("\r", " ")
            w.write(singleLineExpr)
            w.newLine()
            w.flush()
            
            val future = java.util.concurrent.CompletableFuture.supplyAsync {
                while (true) {
                    val line = r.readLine() ?: return@supplyAsync null
                    if (line.startsWith("###RES###")) {
                        val result = line.removePrefix("###RES###")
                        return@supplyAsync if (result == "ERROR") null else result
                    }
                }
                null
            }
            return try {
                future.get(8, java.util.concurrent.TimeUnit.SECONDS)
            } catch (te: java.util.concurrent.TimeoutException) {
                process?.destroyForcibly()
                process = null
                writer = null
                reader = null
                null
            }
        } catch(e: Exception) {
            println("Deno daemon error: " + e)
            process?.destroyForcibly()
            process = null
            writer = null
            reader = null
            return null
        }
    }
}

class WindowsCipherDenoEngine(
    private val playerJs: String,
    private val sigInfo: WindowsFunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: WindowsFunctionNameExtractor.NFunctionInfo?
) {
    private val deno: File? by lazy { executable("deno.exe") }

    private fun executable(name: String): File? {
        val resourceDir = System.getProperty("compose.application.resources.dir")
            ?: File(System.getProperty("user.dir"), "src/appResources/windows-x64").absolutePath
        return File(resourceDir, name).takeIf { it.exists() }
    }

    suspend fun deobfuscateSignature(signature: String): String = withContext(Dispatchers.IO) {
        val d = deno
        if (sigInfo == null || d == null) return@withContext signature
        
        PersistentDenoDaemon.ensureRunning(d, playerJs)
        
        val expression = sigInfo.jsExpression?.let { expr ->
            "(function() { const s = ''; let result = s; ; return result || (s); })()"
        } ?: "('')"
        
        PersistentDenoDaemon.eval(expression) ?: signature
    }

    suspend fun transformN(nParam: String): String = withContext(Dispatchers.IO) {
        val d = deno
        if (nFuncInfo == null || d == null) return@withContext nParam
        
        PersistentDenoDaemon.ensureRunning(d, playerJs)
        
        val expression = if (nFuncInfo.arrayIndex != null) {
            "[]('')"
        } else {
            "('')"
        }
        
        PersistentDenoDaemon.eval(expression) ?: nParam
    }
}