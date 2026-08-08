package org.prolibertate.games.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall between the two channels.
 *
 * A dev install may only ever be offered dev builds and a production install
 * production ones. There is no setting for it, so the only way this can go
 * wrong now is here.
 */
class UpdatePolicyTest {

    private fun release(
        tag: String,
        versionCode: Int,
        channel: UpdateChannel,
    ) = Release(
        tag = tag,
        versionCode = versionCode,
        apkUrl = "https://example.invalid/$tag.apk",
        apkName = "ProLibertateGames-$tag.apk",
        channel = channel,
    )

    private val devBuild = release("v1.1.69-dev", 69, UpdateChannel.DEV)
    private val productionBuild = release("v1.1.40", 40, UpdateChannel.PRODUCTION)

    // -- Reading what a build is --------------------------------------------

    @Test
    fun `a version name says which channel it belongs to`() {
        assertEquals(UpdateChannel.DEV, channelOfVersionName("1.1.69-dev"))
        assertEquals(UpdateChannel.PRODUCTION, channelOfVersionName("1.1.40"))
    }

    @Test
    fun `either mark is enough to make a release a dev build`() {
        assertEquals(UpdateChannel.DEV, channelOfTag("v1.1.69-dev", prerelease = false))
        assertEquals(UpdateChannel.DEV, channelOfTag("v1.1.69", prerelease = true))
        assertEquals(UpdateChannel.PRODUCTION, channelOfTag("v1.1.40", prerelease = false))
    }

    @Test
    fun `only the run number is read from a tag`() {
        assertEquals(69, versionCodeOfTag("v1.1.69-dev"))
        assertEquals(40, versionCodeOfTag("v1.1.40"))
        // A series bump must never read as a downgrade.
        assertTrue(versionCodeOfTag("v2.0.41")!! > versionCodeOfTag("v1.1.40")!!)
        assertNull(versionCodeOfTag("nightly"))
    }

    // -- The wall -----------------------------------------------------------

    @Test
    fun `a dev install is never offered a production build`() {
        val verdict = verdictFor(
            latest = productionBuild,
            installedChannel = UpdateChannel.DEV,
            // Deliberately far behind: even a much "newer" number on the other
            // channel must not get through, because the numbers are unrelated.
            installedVersionCode = 1,
        )
        assertTrue("a dev install was offered $productionBuild", verdict is UpdateVerdict.Refused)
    }

    @Test
    fun `a production install is never offered a dev build`() {
        val verdict = verdictFor(
            latest = devBuild,
            installedChannel = UpdateChannel.PRODUCTION,
            installedVersionCode = 1,
        )
        assertTrue("a production install was offered $devBuild", verdict is UpdateVerdict.Refused)
    }

    // -- Within a channel ---------------------------------------------------

    @Test
    fun `a newer build on the same channel is offered`() {
        assertEquals(
            UpdateVerdict.Install(devBuild),
            verdictFor(devBuild, UpdateChannel.DEV, installedVersionCode = 62),
        )
        assertEquals(
            UpdateVerdict.Install(productionBuild),
            verdictFor(productionBuild, UpdateChannel.PRODUCTION, installedVersionCode = 39),
        )
    }

    @Test
    fun `the same build is not offered to itself`() {
        assertEquals(
            UpdateVerdict.UpToDate,
            verdictFor(devBuild, UpdateChannel.DEV, installedVersionCode = 69),
        )
    }

    @Test
    fun `an older build is not offered`() {
        assertEquals(
            UpdateVerdict.UpToDate,
            verdictFor(devBuild, UpdateChannel.DEV, installedVersionCode = 70),
        )
    }

    @Test
    fun `nothing published yet says so rather than failing silently`() {
        val verdict = verdictFor(null, UpdateChannel.DEV, installedVersionCode = 69)
        assertTrue(verdict is UpdateVerdict.Refused)
        assertTrue(
            "the reason should name the channel: ${(verdict as UpdateVerdict.Refused).reason}",
            verdict.reason.contains("dev"),
        )
    }

    // -- What a check on launch is allowed to say ---------------------------

    @Test
    fun `a check on launch speaks only when there is something to install`() {
        assertEquals(devBuild, launchOffer(UpdateVerdict.Install(devBuild)))
    }

    @Test
    fun `a check on launch says nothing when there is nothing to say`() {
        assertNull("an up-to-date app has no business interrupting", launchOffer(UpdateVerdict.UpToDate))
        assertNull(
            "a phone with no network has nothing to apologise for",
            launchOffer(UpdateVerdict.Refused("Unable to resolve host api.github.com")),
        )
        // Including the refusal that this whole change exists to produce: being
        // shown the other channel's build is not a thing to be told about.
        assertNull(
            launchOffer(verdictFor(productionBuild, UpdateChannel.DEV, installedVersionCode = 1)),
        )
    }

    /**
     * The two sequences genuinely overlap — production is on 40 while dev is on
     * 69 — so a build's own number is meaningless on the other channel. This is
     * the case the old channel picker could produce and this one cannot.
     */
    @Test
    fun `overlapping version numbers never cross the channels`() {
        listOf(1, 40, 69, 200).forEach { installed ->
            assertTrue(
                "production build offered to a dev install on $installed",
                verdictFor(productionBuild, UpdateChannel.DEV, installed)
                    is UpdateVerdict.Refused,
            )
            assertTrue(
                "dev build offered to a production install on $installed",
                verdictFor(devBuild, UpdateChannel.PRODUCTION, installed)
                    is UpdateVerdict.Refused,
            )
        }
    }
}
