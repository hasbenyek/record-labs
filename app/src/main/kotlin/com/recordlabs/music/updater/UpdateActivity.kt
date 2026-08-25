package com.recordlabs.music.updater

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.recordlabs.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private var downloadedApk: File? = null
    private var waitingForInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return finish()
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: return finish()
        val checksumUrl = intent.getStringExtra(EXTRA_CHECKSUM_URL) ?: return finish()

        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, version))
            .setNegativeButton(R.string.update_later) { _, _ -> finish() }
            .setPositiveButton(R.string.update_now) { _, _ ->
                downloadAndInstall(version, apkUrl, checksumUrl)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission && canInstallPackages()) {
            waitingForInstallPermission = false
            downloadedApk?.let(::launchInstaller)
        }
    }

    private fun downloadAndInstall(version: String, apkUrl: String, checksumUrl: String) {
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val status = TextView(this).apply {
            text = getString(R.string.update_downloading)
            setPadding(0, 24, 0, 0)
        }
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * density).toInt()
            setPadding(horizontal, horizontal, horizontal, 0)
            addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setView(content)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val expectedHash = downloadText(checksumUrl).trim().substringBefore(' ')
                    val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                    val target = File(updateDir, "Record-Labs-$version.apk")
                    downloadFile(apkUrl, target)
                    if (!sha256(target).equals(expectedHash, ignoreCase = true)) {
                        target.delete()
                        error(getString(R.string.update_invalid_file))
                    }
                    target
                }
                dialog.dismiss()
                downloadedApk = apk
                requestPermissionOrInstall(apk)
            } catch (error: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@UpdateActivity,
                    getString(R.string.update_download_failed, error.message ?: "unknown error"),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Record-Labs-Updater").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("Empty checksum")
        }
    }

    private fun downloadFile(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", "Record-Labs-Updater").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty APK")
            FileOutputStream(target).use { output -> body.byteStream().use { it.copyTo(output) } }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requestPermissionOrInstall(apk: File) {
        if (!canInstallPackages()) {
            waitingForInstallPermission = true
            Toast.makeText(this, R.string.update_permission_required, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri(),
                ),
            )
            return
        }
        launchInstaller(apk)
    }

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.FileProvider", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }

    companion object {
        const val EXTRA_VERSION = "version"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_CHECKSUM_URL = "checksum_url"
    }
}
