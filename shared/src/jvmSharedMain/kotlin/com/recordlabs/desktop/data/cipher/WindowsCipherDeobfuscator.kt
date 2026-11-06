package com.recordlabs.desktop.data.cipher

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder

object WindowsCipherDeobfuscator {
    private var engine: WindowsCipherDenoEngine? = null
    private val mutex = Mutex()

    private suspend fun getEngine(): WindowsCipherDenoEngine? {
        if (engine != null) return engine
        
        val player = WindowsPlayerJsFetcher.getPlayerJs() ?: return null
        val analysis = WindowsFunctionNameExtractor.analyzePlayerJs(player.first, player.second)
        
        engine = WindowsCipherDenoEngine(player.first, analysis.sigInfo, analysis.nFuncInfo)
        return engine
    }

    suspend fun deobfuscateStreamUrl(signatureCipher: String): String? = mutex.withLock {
        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"] ?: return null
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"] ?: return null

        val eng = getEngine() ?: return null
        val deobfuscatedSig = eng.deobfuscateSignature(obfuscatedSig)

        val separator = if ("?" in baseUrl) "&" else "?"
        return "$baseUrl$separator$sigParam=${URLEncoder.encode(deobfuscatedSig, "UTF-8")}"
    }

    suspend fun transformNParamInUrl(url: String): String = mutex.withLock {
        val nMatch = Regex("[?&]n=([^&]+)").find(url) ?: return url
        val nValue = URLDecoder.decode(nMatch.groupValues[1], "UTF-8")
        
        val eng = getEngine() ?: return url
        val transformedN = eng.transformN(nValue)

        return url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "${"$"}1n=${URLEncoder.encode(transformedN, "UTF-8")}"
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}
