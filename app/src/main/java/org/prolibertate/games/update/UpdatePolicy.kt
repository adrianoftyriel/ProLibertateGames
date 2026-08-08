package org.prolibertate.games.update

/**
 * Which line of builds an APK belongs to.
 *
 * This is a property of the build, never a preference. The two channels are
 * published by different workflows and have entirely separate version
 * sequences — a dev build's versionCode is its CI run number, a production
 * build's is its release run number, and the two are unrelated. There is no
 * meaning to be had from comparing across them, so nothing does.
 *
 * They also carry different applicationIds — a dev build installs as
 * `…games.dev` — so both can sit on one device, each updating itself down its
 * own line and leaving the other alone.
 */
enum class UpdateChannel(val label: String, val blurb: String) {
    PRODUCTION("Production", "Stable builds released from main"),
    DEV("Dev", "Preview builds from the dev branch — newer, less tested"),
}

/** Marks a tag or a version name as belonging to the dev channel. */
const val DEV_SUFFIX = "-dev"

/** One published build, as read off a GitHub release. */
data class Release(
    val tag: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val channel: UpdateChannel,
)

sealed interface UpdateVerdict {
    data class Install(val release: Release) : UpdateVerdict
    data object UpToDate : UpdateVerdict
    data class Refused(val reason: String) : UpdateVerdict
}

/**
 * The channel an installed build belongs to, read from its own version name.
 *
 * The APK says what it is, so this cannot disagree with what is actually
 * running — which is the whole point of not storing it anywhere.
 */
fun channelOfVersionName(versionName: String): UpdateChannel =
    if (versionName.endsWith(DEV_SUFFIX)) UpdateChannel.DEV else UpdateChannel.PRODUCTION

/**
 * The channel a release belongs to.
 *
 * Two independent marks, and either is enough: the `-dev` suffix the workflow
 * puts on the tag, and GitHub's own prerelease flag. A release would have to
 * lose both to be mistaken for the other channel.
 */
fun channelOfTag(tag: String, prerelease: Boolean): UpdateChannel =
    if (tag.endsWith(DEV_SUFFIX) || prerelease) UpdateChannel.DEV else UpdateChannel.PRODUCTION

/**
 * The run number out of a `v<series>.<n>` tag, which is what an APK's
 * versionCode is.
 *
 * Only the trailing number is read. The series is a name for people, and
 * bumping it must never make an older build look newer.
 */
fun versionCodeOfTag(tag: String): Int? =
    tag.substringAfterLast('.').removeSuffix(DEV_SUFFIX).toIntOrNull()

/**
 * Whether [latest] should be offered to a build of [installedChannel] at
 * [installedVersionCode].
 *
 * **A build only ever updates within its own channel.** A dev install is
 * offered dev builds and a production install production ones; there is no
 * setting for this and no way to cross over, because crossing over is not an
 * upgrade — it is a different app with an unrelated version sequence, and
 * offering it as though it were newer is how a phone ends up being handed a
 * build nobody meant it to have.
 *
 * The channel is checked here rather than trusted from the fetch, so a release
 * that does not belong to this build is refused whatever the endpoint returned.
 */
fun verdictFor(
    latest: Release?,
    installedChannel: UpdateChannel,
    installedVersionCode: Int,
): UpdateVerdict = when {
    latest == null ->
        UpdateVerdict.Refused("No ${installedChannel.label.lowercase()} build has been published yet.")

    latest.channel != installedChannel ->
        UpdateVerdict.Refused(
            "${latest.tag} is a ${latest.channel.label.lowercase()} build, and this is a " +
                "${installedChannel.label.lowercase()} install. The two update separately."
        )

    latest.versionCode > installedVersionCode -> UpdateVerdict.Install(latest)

    else -> UpdateVerdict.UpToDate
}
