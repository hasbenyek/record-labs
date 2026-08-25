package com.recordlabs.music.updater

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.recordlabs.music.BuildConfig
import com.recordlabs.music.R
import com.recordlabs.music.utils.compareVersionNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object AppUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/hasbenyek/record-labs/releases/latest"
    private const val PREFS_NAME = "record_labs_updater"
    private const val LAST_CHECK_KEY = "last_update_check"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    private const val NOTIFICATION_ID = 1909

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    suspend fun checkForUpdate(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_CHECK_KEY, 0L) < CHECK_INTERVAL_MS) return@withContext

        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Record-Labs/${BuildConfig.VERSION_NAME}")
            .build()

        val release = client.newCall(request).execute().use { response ->
            prefs.edit { putLong(LAST_CHECK_KEY, now) }
            if (!response.isSuccessful) return@withContext
            val body = response.body?.string() ?: return@withContext
            json.decodeFromString<GitHubRelease>(body)
        }

        if (release.draft || release.prerelease) return@withContext
        val latestVersion = release.tagName.removePrefix("v")
        if (compareVersionNames(latestVersion, BuildConfig.VERSION_NAME) <= 0) return@withContext

        val apk = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.name.contains("foss", ignoreCase = true)
        } ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext
        val checksum = release.assets.firstOrNull {
            it.name == "${apk.name}.sha256" || it.name.endsWith(".sha256", ignoreCase = true)
        } ?: return@withContext

        showUpdateNotification(
            context = context,
            version = latestVersion,
            apkUrl = apk.downloadUrl,
            checksumUrl = checksum.downloadUrl,
        )
    }

    private fun showUpdateNotification(
        context: Context,
        version: String,
        apkUrl: String,
        checksumUrl: String,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, UpdateActivity::class.java).apply {
            putExtra(UpdateActivity.EXTRA_VERSION, version)
            putExtra(UpdateActivity.EXTRA_APK_URL, apkUrl)
            putExtra(UpdateActivity.EXTRA_CHECKSUM_URL, checksumUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_message, version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

}
