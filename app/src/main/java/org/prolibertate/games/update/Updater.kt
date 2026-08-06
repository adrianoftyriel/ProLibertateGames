package org.prolibertate.games.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which builds the updater offers.
 *
 * The two channels are published by different workflows and so have entirely
 * separate version sequences — a dev build's versionCode is its CI run number,
 * a production build's is its release run number, and the two are unrelated.
 * Versions are therefore only ever compared *within* a channel; moving between
 * them is treated as an explicit switch rather than an upgrade.
 */
enum class UpdateChannel(val label: String, val blurb: String) {
    PRODUCTION("Production", "Stable builds released from main"),
    DEV("Dev", "Preview builds from the dev branch — newer, less tested"),
}

/** Marks a version name as belonging to the dev channel. */
private const val DEV_SUFFIX = "-dev"

/**
 * Over-the-air updates from the repository's GitHub Releases.
 *
 * Release tags are v1.0.<run-number> and the APK's versionCode is that same run
 * number, so comparing the two is enough to tell whether a newer build exists.
 * The repository's releases must be publicly downloadable — no token is
 * embedded in the app, and GitHub blocks anonymous access to private assets.
 */
class Updater(private val activity: Activity) {

    data class Release(
        val tag: String,
        val versionCode: Int,
        val apkUrl: String,
        val apkName: String,
        val channel: UpdateChannel,
    )

    sealed interface Result {
        /**
         * [isChannelSwitch] means this build is from a different channel than
         * the one installed, so it is an explicit move rather than a newer
         * version of the same line.
         */
        data class Available(val release: Release, val isChannelSwitch: Boolean) : Result

        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(channel: UpdateChannel): Result = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatestRelease(channel)
                ?: return@withContext Result.Failed(
                    "No ${channel.label.lowercase()} build has been published yet."
                )

            when {
                // Different channel: version numbers are not comparable, so
                // always offer it and let the user decide.
                latest.channel != installedChannel() ->
                    Result.Available(latest, isChannelSwitch = true)

                latest.versionCode > installedVersionCode() ->
                    Result.Available(latest, isChannelSwitch = false)

                else -> Result.UpToDate
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Update check failed.")
        }
    }

    /**
     * Production reads GitHub's "latest release", which by definition skips
     * prereleases. Dev walks the release list for the newest prerelease, which
     * is what the CI pipeline publishes from the dev branch.
     */
    private fun fetchLatestRelease(channel: UpdateChannel): Release? = when (channel) {
        UpdateChannel.PRODUCTION ->
            getJsonObject("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
                ?.let { toRelease(it) }

        UpdateChannel.DEV ->
            getJsonArray("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30")
                ?.let { releases ->
                    // GitHub returns newest first.
                    (0 until releases.length())
                        .map { releases.getJSONObject(it) }
                        .firstOrNull { it.optBoolean("prerelease") && !it.optBoolean("draft") }
                        ?.let { toRelease(it) }
                }
    }

    private fun toRelease(json: JSONObject): Release? {
        val tag = json.optString("tag_name").ifBlank { return null }
        // Tags are v1.0.<n> or v1.0.<n>-dev; the run number is the trailing part.
        val versionCode = tag.substringAfterLast('.')
            .removeSuffix(DEV_SUFFIX)
            .toIntOrNull()
            ?: return null
        val channel =
            if (tag.endsWith(DEV_SUFFIX) || json.optBoolean("prerelease")) {
                UpdateChannel.DEV
            } else {
                UpdateChannel.PRODUCTION
            }

        val assets = json.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk") }

        // A production release carries the same build twice: once named for its
        // version, and once under a fixed name so a permanent download link
        // exists. Pick the versioned one explicitly rather than whichever was
        // uploaded first, so what gets installed is traceable to its tag.
        val chosen = apks.firstOrNull { it.optString("name").contains(tag) }
            ?: apks.firstOrNull()
            ?: return null

        return Release(
            tag = tag,
            versionCode = versionCode,
            apkUrl = chosen.getString("browser_download_url"),
            apkName = chosen.optString("name"),
            channel = channel,
        )
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ProLibertateGames-Updater")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

    private fun getJsonObject(url: String): JSONObject? {
        val connection = openConnection(url)
        try {
            if (connection.responseCode != 200) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun getJsonArray(url: String): JSONArray? {
        val connection = openConnection(url)
        try {
            if (connection.responseCode != 200) return null
            return JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Read back from the installed APK's own version name. */
    fun installedChannel(): UpdateChannel =
        if (installedVersionName().endsWith(DEV_SUFFIX)) UpdateChannel.DEV
        else UpdateChannel.PRODUCTION

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
