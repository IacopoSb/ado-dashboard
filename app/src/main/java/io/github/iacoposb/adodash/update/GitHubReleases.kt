package io.github.iacoposb.adodash.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface CheckResult {
    data object UpToDate : CheckResult
    /** The repo has no usable release, or its releases are not public (GitHub answers 404). */
    data object NoReleases : CheckResult
    data class Available(val release: Release) : CheckResult
}

/**
 * A failed check or download. [reason] (with [code] or [detail]) is what the
 * app shows, translated; [message] is English, for the logs.
 */
class UpdateException(
    val reason: Reason,
    val code: Int = 0,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(reason.describe(code, detail), cause) {
    enum class Reason {
        RATE_LIMITED, HTTP, INVALID_RESPONSE, NOT_IN_SUMS, CHECKSUM_MISMATCH, DOWNLOAD_HTTP, UNREACHABLE;

        fun describe(code: Int, detail: String) = when (this) {
            RATE_LIMITED -> "GitHub rate limit reached"
            HTTP -> "GitHub responded with HTTP $code"
            INVALID_RESPONSE -> "invalid response from GitHub"
            NOT_IN_SUMS -> "$detail is not listed in SHA256SUMS"
            CHECKSUM_MISMATCH -> "the downloaded file doesn't match SHA256SUMS"
            DOWNLOAD_HTTP -> "download failed (HTTP $code)"
            UNREACHABLE -> "can't reach GitHub"
        }
    }
}

/**
 * Releases of a public GitHub [repo], read without authentication: nothing
 * secret is ever baked into the APK.
 */
class GitHubReleases(
    private val repo: String,
    private val http: OkHttpClient = defaultHttp,
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$apiBase/repos/$repo/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = call(request) { r ->
            when {
                r.code == 404 -> null
                r.code == 403 || r.code == 429 -> throw UpdateException(UpdateException.Reason.RATE_LIMITED)
                !r.isSuccessful -> throw UpdateException(UpdateException.Reason.HTTP, code = r.code)
                else -> r.body!!.string()
            }
        } ?: return@withContext CheckResult.NoReleases
        val newest = try {
            newestRelease(body)
        } catch (e: SerializationException) {
            throw UpdateException(UpdateException.Reason.INVALID_RESPONSE, cause = e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateException.Reason.INVALID_RESPONSE, cause = e)
        }
        when {
            newest == null -> CheckResult.NoReleases
            isNewer(newest.version, currentVersion) -> CheckResult.Available(newest)
            else -> CheckResult.UpToDate
        }
    }

    /**
     * Downloads the APK of [release] into [dest], checked against the
     * release's SHA256SUMS when it has one. [progress] goes from 0 to 1.
     */
    suspend fun download(release: Release, dest: File, progress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val expected = release.sumsUrl?.let { url ->
            val sums = call(get(url)) { r -> r.ensureOk(); parseSha256Sums(r.body!!.string()) }
            sums[release.apkName] ?: throw UpdateException(UpdateException.Reason.NOT_IN_SUMS, detail = release.apkName)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        call(get(release.apkUrl)) { r ->
            r.ensureOk()
            val total = r.body!!.contentLength().takeIf { it > 0 } ?: release.apkSize
            r.body!!.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        coroutineContext.ensureActive()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && actual != expected) {
            dest.delete()
            throw UpdateException(UpdateException.Reason.CHECKSUM_MISMATCH)
        }
    }

    private fun get(url: String) = Request.Builder().url(url).header("Accept", "application/octet-stream").build()

    private fun Response.ensureOk() {
        if (!isSuccessful) throw UpdateException(UpdateException.Reason.DOWNLOAD_HTTP, code = code)
    }

    private fun <T> call(request: Request, block: (Response) -> T): T = try {
        http.newCall(request).execute().use(block)
    } catch (e: UpdateException) {
        throw e
    } catch (e: IOException) {
        throw UpdateException(UpdateException.Reason.UNREACHABLE, cause = e)
    }

    companion object {
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
