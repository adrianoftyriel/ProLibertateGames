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
 * Over-the-air updates from plg.wallacenet.ca (Cloudflare Pages).
 *
 * Release tags are v<series>.<run-number> and the APK's versionCode is that same run
 * number, so comparing the two is enough to tell whether a newer build exists.
 * Everything there is public — no token is embedded in the app.
 *
 * There is nothing to choose here. The channel comes from the running APK, so
 * a dev install looks only at dev builds and a production install only at
 * production ones — see [verdictFor], where that rule lives and is tested.
 */
class Updater(private val activity: Activity) {

    /**
     * Looks for a newer build of whatever is installed.
     *
     * Takes no channel: asking for one would be asking which line of builds
     * this phone is on, and the phone already knows.
     */
    suspend fun check(): UpdateVerdict = withContext(Dispatchers.IO) {
        try {
            val channel = installedChannel()
            verdictFor(
                latest = fetchLatestRelease(channel),
                installedChannel = channel,
                installedVersionCode = installedVersionCode(),
            )
        } catch (e: Exception) {
            UpdateVerdict.Refused(e.message ?: "Update check failed.")
        }
    }

    /**
     * Each channel publishes a `latest.json` next to its APK on Cloudflare
     * Pages: `/prod/latest.json` and `/dev/latest.json`. The two live in one
     * site but separate paths, so a build can only ever look down its own line.
     */
    private fun fetchLatestRelease(channel: UpdateChannel): Release? {
        val path = if (channel == UpdateChannel.DEV) "dev" else "prod"
        return getJsonObject("$BASE_URL/$path/latest.json")?.let { toRelease(it) }
    }

    private fun toRelease(json: JSONObject): Release? {
        val tag = json.optString("tag").ifBlank { return null }
        val versionCode = versionCodeOfTag(tag) ?: return null
        val apkUrl = json.optString("apkUrl").ifBlank { return null }
        return Release(
            tag = tag,
            versionCode = versionCode,
            apkUrl = apkUrl,
            apkName = json.optString("apkName"),
            channel = channelOfTag(tag, prerelease = false),
        )
    }

    private fun getJsonObject(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ProLibertateGames-Updater")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            // 404 means the channel has nothing published yet.
            if (connection.responseCode != 200) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Read back from the installed APK's own version name. */
    fun installedChannel(): UpdateChannel = channelOfVersionName(installedVersionName())

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
        const val BASE_URL = "https://plg.wallacenet.ca"
    }
}
