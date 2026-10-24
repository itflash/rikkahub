package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ForkReleaseTest {
    private fun asset(tag: String, abi: String = "arm64-v8a") = ForkReleaseAsset(
        name = "rikkahub-$tag-$abi.apk",
        downloadUrl = "https://github.com/itflash/rikkahub/releases/download/$tag/rikkahub-$tag-$abi.apk",
        size = 42_000_000,
    )
    private fun release(tag: String, assets: List<ForkReleaseAsset> = listOf(asset(tag))) =
        ForkRelease(tagName = tag, publishedAt = "2026-10-24T08:00:00Z", assets = assets)
    private fun select(vararg releases: ForkRelease, abis: List<String> = listOf("arm64-v8a")) = selectForkRelease(releases.toList(), abis)

    @Test fun `dev nightly and upstream style labels never suppress the stable version`() {
        val info = select(release("dev"), release("nightly"), release("2.5.6-workbench.20261017"), release("2.5.9"))
        assertEquals("2.5.9", info?.version)
    }
    @Test fun `highest stable version is chosen regardless of creation order`() {
        assertEquals("2.10.0", select(release("2.9.9"), release("2.10.0"), release("2.5.9"))?.version)
    }
    @Test fun `prereleases drafts and unpublished releases are ignored`() {
        assertNull(select(release("99.0.0").copy(draft = true), release("98.0.0").copy(prerelease = true),
            release("3.0.0").copy(publishedAt = null), release("3.0.0-rc.1")))
    }
    @Test fun `x86 devices do not receive an arm64 only package`() {
        val tag = "2.5.9"
        val info = select(release(tag, listOf(asset(tag), asset(tag, "universal"), asset(tag, "x86_64"))), abis = listOf("x86_64", "x86"))!!
        assertEquals(listOf("rikkahub-2.5.9-x86_64.apk", "rikkahub-2.5.9-universal.apk"), info.downloads.map { it.name })
        assertNull(select(release(tag), abis = listOf("x86_64")))
    }
    @Test fun `a newer release without compatible APKs is skipped`() {
        assertEquals("2.5.8", select(release("2.5.9", emptyList()), release("2.5.8"))?.version)
    }
    @Test fun `cross repository and insecure asset URLs cannot be used for updates`() {
        val base = asset("2.5.9")
        listOf(base.downloadUrl.replace("itflash", "rikkahub"), base.downloadUrl.replace("https:", "http:"),
            base.downloadUrl.replace("github.com", "malicious.example"), base.downloadUrl.replace("/2.5.9/", "/2.5.8/"))
            .forEach { assertNull(select(release("2.5.9", listOf(base.copy(downloadUrl = it))))) }
    }
    @Test fun `v prefixed tags and absent changelogs are supported`() {
        val info = select(release("v2.5.9").copy(body = null))!!
        assertEquals("2.5.9", info.version)
        assertEquals("", info.changelog)
    }
    @Test fun `GitHub payload may have a null changelog and extra fields`() {
        val payload = """[{"tag_name":"dev","published_at":"2026-10-24T08:00:00Z","body":null,"id":42,"assets":[]}]"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<ForkRelease>>(payload)
        assertNull(selectForkRelease(decoded, listOf("arm64-v8a")))
    }
    @Test fun `an empty stable list is a quiet no update not an upstream fallback`() {
        assertNull(selectForkRelease(emptyList(), listOf("arm64-v8a")))
        assertTrue(FORK_RELEASES_API.startsWith("https://api.github.com/repos/itflash/rikkahub/"))
    }
}
