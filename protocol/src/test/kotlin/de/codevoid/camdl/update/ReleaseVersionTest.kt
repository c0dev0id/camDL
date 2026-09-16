package de.codevoid.camdl.update

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ReleaseVersionTest {

    private fun apk(name: String) = ReleaseAsset(name, "https://example.invalid/$name")

    @Test
    fun `the version is the middle of the artifact name`() {
        assertEquals("dev-2ad9c2e", ReleaseVersion.versionOf("camDL-dev-2ad9c2e.apk"))
    }

    @Test
    fun `anything not shaped like our artifact has no version`() {
        // A CI rename would otherwise go unnoticed, and the failure mode is silent either way:
        // never offering an update, or offering the same one forever.
        assertNull(ReleaseVersion.versionOf("app-release.apk"))
        assertNull(ReleaseVersion.versionOf("camDL-dev-2ad9c2e.apk.sha256"))
        assertNull(ReleaseVersion.versionOf("camdl-dev-2ad9c2e.apk"))
        assertNull(ReleaseVersion.versionOf("camDL-.apk"))
        assertNull(ReleaseVersion.versionOf(""))
    }

    @Test
    fun `a build that differs from the installed one is offered`() {
        val asset = apk("camDL-dev-9999999.apk")

        assertEquals(asset, ReleaseVersion.pick("dev-2ad9c2e", listOf(asset)))
    }

    @Test
    fun `the installed build is not offered back`() {
        assertNull(ReleaseVersion.pick("dev-2ad9c2e", listOf(apk("camDL-dev-2ad9c2e.apk"))))
    }

    @Test
    fun `assets that are not our apk are ignored`() {
        val apk = apk("camDL-dev-9999999.apk")
        val assets = listOf(apk("notes.txt"), ReleaseAsset("sources.zip", "u"), apk)

        assertEquals(apk, ReleaseVersion.pick("dev-2ad9c2e", assets))
    }

    @Test
    fun `a release with no apk offers nothing`() {
        assertNull(ReleaseVersion.pick("dev-2ad9c2e", listOf(ReleaseAsset("notes.txt", "u"))))
        assertNull(ReleaseVersion.pick("dev-2ad9c2e", emptyList()))
    }

    @Test
    fun `a locally built apk always sees the published one as different`() {
        // versionName falls back to "dev-local" when the CI properties are absent, so a
        // developer build is never mistaken for a published one.
        assertEquals(
            "dev-2ad9c2e",
            ReleaseVersion.pick("dev-local", listOf(apk("camDL-dev-2ad9c2e.apk")))?.let {
                ReleaseVersion.versionOf(it.name)
            },
        )
    }
}
