package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal const val FORK_RELEASES_API = "https://api.github.com/repos/itflash/rikkahub/releases?per_page=30"
private val stableTag = Regex("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

@Serializable
internal data class ForkRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ForkReleaseAsset> = emptyList(),
)

@Serializable
internal data class ForkReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** dev/nightly may be newer than a stable release. Never interpret those tags as version numbers. */
internal fun selectForkRelease(releases: List<ForkRelease>, supportedAbis: List<String>): UpdateInfo? {
    val releasesByVersion = releases.asSequence()
        .filter { !it.draft && !it.prerelease && stableTag.matches(it.tagName) && !it.publishedAt.isNullOrBlank() }
        .sortedWith(compareByDescending { Version(it.tagName.removePrefix("v")) })
    for (release in releasesByVersion) {
        val validAssets = release.assets.filter { asset ->
            val url = asset.downloadUrl.toHttpUrlOrNull()
            asset.size > 0 && asset.name.endsWith(".apk") && !asset.name.contains('/') &&
                !asset.name.contains('\\') && url?.scheme == "https" && url.host == "github.com" &&
                url.pathSegments == listOf("itflash", "rikkahub", "releases", "download", release.tagName, asset.name)
        }
        val abiAsset = supportedAbis.firstNotNullOfOrNull { abi ->
            validAssets.firstOrNull { it.name.endsWith("-$abi.apk") }
        }
        val universal = validAssets.firstOrNull { it.name.endsWith("-universal.apk") }
        val compatible = listOfNotNull(abiAsset, universal).distinctBy { it.name }
        if (compatible.isEmpty()) continue
        return UpdateInfo(
            version = release.tagName.removePrefix("v"),
            publishedAt = requireNotNull(release.publishedAt),
            changelog = release.body.orEmpty(),
            downloads = compatible.map { UpdateDownload(it.name, it.downloadUrl,
                String.format(Locale.ROOT, "%.1f MB", it.size / (1024.0 * 1024.0))) },
        )
    }
    return null
}
