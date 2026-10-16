package io.github.iacoposb.adodash.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published APK: what the update check offers. */
data class Release(
    val version: String,
    val tag: String,
    /** Markdown body of the GitHub release (.github/release-notes.sh). */
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** The SHA256SUMS asset, when the release has one. */
    val sumsUrl: String? = null,
)

/** MAJOR.MINOR.PATCH, ordered numerically ("0.10.0" > "0.9.3"). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")
        private val TAG = Regex("""release/(\d+\.\d+\.\d+)""")

        /** Strict X.Y.Z, null otherwise. */
        fun parse(s: String): Version? = SEMVER.matchEntire(s.trim())?.destructured
            ?.let { (a, b, c) -> runCatching { Version(a.toInt(), b.toInt(), c.toInt()) }.getOrNull() }

        /** Version of a release tag `release/X.Y.Z`, null for any other tag. */
        fun ofTag(tag: String): Version? = TAG.matchEntire(tag)?.groupValues?.get(1)?.let(::parse)
    }
}

/** True when [candidate] is a newer version than [current]; an unparsable current one is always older. */
fun isNewer(candidate: String, current: String): Boolean {
    val c = Version.parse(candidate) ?: return false
    // Local builds may carry suffixes ("0.1.0-dev"): compare the numeric prefix
    val now = Version.parse(current) ?: Version.parse(current.substringBefore('-')) ?: return true
    return c > now
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * The newest published release in a GitHub release list (`GET /repos/{repo}/releases`):
 * not a draft nor a prerelease, tagged `release/X.Y.Z` and with an APK asset.
 */
fun newestRelease(listJson: String): Release? =
    json.decodeFromString<List<ReleaseDto>>(listJson)
        .filter { !it.draft && !it.prerelease }
        .mapNotNull { r ->
            val version = Version.ofTag(r.tagName) ?: return@mapNotNull null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@mapNotNull null
            version to Release(
                version.toString(), r.tagName, r.body.orEmpty(), apk.name, apk.url, apk.size,
                r.assets.firstOrNull { it.name == "SHA256SUMS" }?.url,
            )
        }
        .maxByOrNull { it.first }?.second

/** `sha256sum` output: file name -> lowercase hex digest. */
fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence()
    .mapNotNull { Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(it.trim())?.destructured }
    .associate { (hash, name) -> name.trim() to hash.lowercase() }

/** One line of release notes, for a simple rendering of their markdown. */
sealed interface NoteLine {
    data class Heading(val text: String) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data class Text(val text: String) : NoteLine
}

/** Headings (`#`…) and bullets (`-`, `*`); everything else is plain text, blank lines dropped. */
fun parseNotes(markdown: String): List<NoteLine> = markdown.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        when {
            line.startsWith("#") -> NoteLine.Heading(line.trimStart('#').trim())
            line.startsWith("- ") || line.startsWith("* ") -> NoteLine.Bullet(line.substring(2).trim())
            else -> NoteLine.Text(line)
        }
    }
    .toList()
