package de.codevoid.camdl.update

/** One file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String)

/**
 * Decides whether a published build is a different one from the installed build.
 *
 * Different, not newer. CI publishes to a rolling `dev` tag and names the APK after the
 * commit it was built from, and commit hashes have no order, so claiming to know which of two
 * builds is newer would be a lie. "The published build is not the one you are running" is both
 * true and the only thing that matters here.
 *
 * Lives in the pure module so the parsing has tests: a rename of the CI artifact would
 * otherwise fail silently, either never offering an update or offering one forever.
 */
object ReleaseVersion {

    private const val PREFIX = "camDL-"
    private const val SUFFIX = ".apk"

    /** `camDL-dev-2ad9c2e.apk` describes version `dev-2ad9c2e`. */
    fun versionOf(assetName: String): String? {
        if (!assetName.startsWith(PREFIX) || !assetName.endsWith(SUFFIX)) return null
        val version = assetName.substring(PREFIX.length, assetName.length - SUFFIX.length)
        return version.ifEmpty { null }
    }

    /**
     * The asset worth offering, or null when there is nothing to offer - no APK among the
     * assets, or the only one is already installed.
     */
    fun pick(installedVersion: String, assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { asset ->
            val version = versionOf(asset.name)
            version != null && version != installedVersion
        }
}
