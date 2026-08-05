package org.prolibertate.games.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates from the repository's GitHub Releases.
 *
 * Release tags are v1.0.<run-number> and the APK's versionCode is that same run
 * number, so comparing the two is enough to tell whether a newer build exists.
 * The repository's releases must be publicly downloadable — no token is
 * embedded in the app, and GitHub blocks anonymous access to private assets.
 */
class Updater(private val activity: Activity) {

    data class Release(val tag: String, val versionCode: Int, val apkUrl: String, val apkName: String)

    sealed interface Result {
        data class Available(val release: Release) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatestRelease()
                ?: return@withContext Result.Failed("No published release found.")
            if (latest.versionCode <= installedVersionCode()) {
                Result.UpToDate
            } else {
                Result.Available(latest)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Update check failed.")
        }
    }

    private fun fetchLatestRelease(): Release? {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ProLibertateGames-Updater")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val versionCode = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    return Release(tag, versionCode, asset.getString("browser_download_url"), name)
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the APK and hands it to the system installer. */
    suspend fun downloadAndInstall(release: Release): String? = withContext(Dispatchers.IO) {
        try {
            val target = File(activity.cacheDir, "update.apk")
            URL(release.apkUrl).openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            withContext(Dispatchers.Main) { launchInstaller(target) }
            null
        } catch (e: Exception) {
            e.message ?: "Download failed."
        }
    }

    /** API 26+ requires the user to allow installs from this app first. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
        }
    }

    private fun launchInstaller(apk: File) {
        if (activity.isFinishing) return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    fun installedVersionCode(): Int =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode

    fun installedVersionName(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "unknown"

    private companion object {
        const val OWNER = "adrianoftyriel"
        const val REPO = "ProLibertateGames"
    }
}
