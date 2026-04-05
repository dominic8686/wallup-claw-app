package io.livekit.android.example.voiceassistant.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.livekit.android.example.voiceassistant.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val latestVersion: String,
    val apkDownloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean,
)

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val RELEASES_URL =
        "https://api.github.com/repos/dominic8686/wallup/releases/latest"

    private val client = OkHttpClient()

    /**
     * Check GitHub for the latest release. Returns null on failure.
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
            if (BuildConfig.GITHUB_RELEASES_TOKEN.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${BuildConfig.GITHUB_RELEASES_TOKEN}")
            }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JsonParser.parseString(body).asJsonObject

            val tagName = json.get("tag_name")?.asString ?: return@withContext null
            val remoteVersion = tagName.removePrefix("v")
            val releaseNotes = json.get("body")?.asString ?: ""

            // Find the .apk asset
            val assets = json.getAsJsonArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (asset in assets) {
                val obj = asset.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                if (name.endsWith(".apk")) {
                    apkUrl = obj.get("browser_download_url")?.asString
                    break
                }
            }

            if (apkUrl == null) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return@withContext null
            }

            val isNewer = isVersionNewer(remoteVersion, currentVersion)
            Log.i(TAG, "Latest: $remoteVersion, Current: $currentVersion, Newer: $isNewer")

            UpdateInfo(
                latestVersion = remoteVersion,
                apkDownloadUrl = apkUrl,
                releaseNotes = releaseNotes,
                isNewer = isNewer,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download APK to cache and launch install intent.
     */
    suspend fun downloadAndInstall(context: Context, downloadUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val updateDir = File(context.cacheDir, "updates")
                updateDir.mkdirs()
                val apkFile = File(updateDir, "wallup-update.apk")

                // Download
                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code}")
                    return@withContext false
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Launch install
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Download/install failed: ${e.message}")
                false
            }
        }

    /**
     * Simple version comparison: "1.2" > "1.1", "2.0" > "1.9", etc.
     */
    private fun isVersionNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val max = maxOf(r.size, c.size)
        for (i in 0 until max) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }
}
