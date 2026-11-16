package com.typefree.ime.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class UpdateCheckInfo(
    val currentVersion: String,
    val latestTag: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val publishedAt: String,
    val hasUpdate: Boolean,
    val requiresPrivateDictWarning: Boolean
) {
    val preferredDownloadUrl: String
        get() = apkDownloadUrl ?: htmlUrl
}

private data class GitHubReleaseDto(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
    @SerializedName("assets") val assets: List<GitHubAssetDto?>? = null
)

private data class GitHubAssetDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("browser_download_url") val browserDownloadUrl: String? = null
)

private data class ParsedSemVer(
    val core: List<Int>,
    val preRelease: List<String>
)

object AppUpdateChecker {

    const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/dengxuezhao/TypeFree/releases/latest"

    const val GITHUB_RELEASES_PAGE_URL =
        "https://github.com/dengxuezhao/TypeFree/releases"

    private const val DEFAULT_EMPTY_RELEASE_NOTES =
        "暂无详细更新说明，可前往 GitHub Releases 页面查看完整变更记录。"

    private const val MAX_FETCH_ATTEMPTS = 3
    private val RETRY_BACKOFF_MS = longArrayOf(250L, 600L)
    private val TRANSIENT_HTTP_CODES = setOf(502, 503, 504)

    private val gson = Gson()

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun normalizeVersionTag(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) {
            trimmed.substring(1).trim()
        } else {
            trimmed
        }
    }

    /**
     * Pure-Kotlin semantic version comparator.
     *
     * Returns `< 0` when [current] is older than [remoteTag], `0` when equal,
     * and `> 0` when [current] is newer than [remoteTag].
     */
    fun compareSemanticVersions(current: String, remoteTag: String): Int {
        val currentVer = parseSemVer(current)
        val remoteVer = parseSemVer(remoteTag)

        val maxCoreLen = maxOf(currentVer.core.size, remoteVer.core.size)
        for (i in 0 until maxCoreLen) {
            val left = currentVer.core.getOrElse(i) { 0 }
            val right = remoteVer.core.getOrElse(i) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }

        val leftPre = currentVer.preRelease
        val rightPre = remoteVer.preRelease
        if (leftPre.isEmpty() && rightPre.isEmpty()) return 0
        if (leftPre.isEmpty()) return 1
        if (rightPre.isEmpty()) return -1

        val minPreLen = minOf(leftPre.size, rightPre.size)
        for (i in 0 until minPreLen) {
            val cmp = comparePreReleaseIdentifier(leftPre[i], rightPre[i])
            if (cmp != 0) return cmp
        }
        return leftPre.size.compareTo(rightPre.size)
    }

    /**
     * Pure-Kotlin parser for GitHub Releases `/releases/latest` JSON payload.
     */
    fun parseLatestReleaseJson(
        json: String,
        currentVersion: String,
        isPrivateDictBuild: Boolean
    ): UpdateCheckInfo {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("GitHub Release JSON 格式无效", e)
        }
        if (!root.isJsonObject) {
            throw IllegalArgumentException("GitHub Release 响应格式无效：非 JSON 对象")
        }

        val dto = gson.fromJson(root, GitHubReleaseDto::class.java)
        val latestTag = dto.tagName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("GitHub Release 响应缺少 tag_name")

        val normalizedCurrent = normalizeVersionTag(currentVersion)
        val normalizedLatest = normalizeVersionTag(latestTag)
        val htmlUrl = dto.htmlUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: GITHUB_RELEASES_PAGE_URL
        val releaseName = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: latestTag
        val releaseNotes = dto.body?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_EMPTY_RELEASE_NOTES
        val publishedAt = dto.publishedAt?.trim().orEmpty()

        val apkDownloadUrl = dto.assets.orEmpty()
            .mapNotNull { it?.browserDownloadUrl?.trim()?.takeIf { url -> url.isNotEmpty() } }
            .firstOrNull { url ->
                url.substringBefore('?')
                    .substringBefore('#')
                    .endsWith(".apk", ignoreCase = true)
            }

        val hasUpdate = compareSemanticVersions(normalizedCurrent, latestTag) < 0

        return UpdateCheckInfo(
            currentVersion = normalizedCurrent,
            latestTag = latestTag,
            latestVersion = normalizedLatest,
            releaseName = releaseName,
            releaseNotes = releaseNotes,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl,
            publishedAt = publishedAt,
            hasUpdate = hasUpdate,
            requiresPrivateDictWarning = hasUpdate && isPrivateDictBuild
        )
    }

    suspend fun fetchLatestUpdate(
        currentVersion: String,
        isPrivateDictBuild: Boolean,
        client: OkHttpClient = defaultClient,
        apiUrl: String = GITHUB_LATEST_RELEASE_API
    ): Result<UpdateCheckInfo> = withContext(Dispatchers.IO) {
        val userAgent = "TypeFree-Android/${normalizeVersionTag(currentVersion)}"
        var lastError: IllegalStateException? = null

        for (attempt in 0 until MAX_FETCH_ATTEMPTS) {
            val isRetry = attempt > 0
            if (isRetry) {
                delay(RETRY_BACKOFF_MS.getOrElse(attempt - 1) { RETRY_BACKOFF_MS.last() })
                runCatching { client.connectionPool.evictAll() }
            }

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .get()
            if (isRetry) {
                // Force a fresh socket on retry instead of reusing a half-closed Keep-Alive/HTTP2 connection.
                requestBuilder.header("Connection", "close")
            }
            val request = requestBuilder.build()

            val terminalResult: Result<UpdateCheckInfo>? = try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code in TRANSIENT_HTTP_CODES && attempt < MAX_FETCH_ATTEMPTS - 1) {
                            lastError = IllegalStateException("检查更新失败 (HTTP ${response.code})")
                            return@use null
                        }
                        val message = when (response.code) {
                            403, 429 -> "GitHub API 请求受限 (HTTP ${response.code})，请稍后重试或前往 Releases 页面手动查看"
                            404 -> "未找到已发布的 Release 版本 (HTTP 404)，可前往 Releases 页面查看"
                            else -> "检查更新失败 (HTTP ${response.code})"
                        }
                        return@use Result.failure(IllegalStateException(message))
                    }
                    if (bodyString.isBlank()) {
                        return@use Result.failure(IllegalStateException("服务器返回空响应，请稍后重试"))
                    }
                    val info = parseLatestReleaseJson(
                        json = bodyString,
                        currentVersion = currentVersion,
                        isPrivateDictBuild = isPrivateDictBuild
                    )
                    Result.success(info)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = mapIoException(e)
                null
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException(e.message ?: "解析版本信息失败，可前往 GitHub Releases 查看", e)
                )
            }

            if (terminalResult != null) {
                return@withContext terminalResult
            }
        }

        Result.failure(
            lastError ?: IllegalStateException("网络请求失败：请检查网络后重试")
        )
    }

    private fun mapIoException(e: IOException): IllegalStateException = when (e) {
        is SocketTimeoutException -> IllegalStateException("连接 GitHub 超时，请检查网络或代理设置后重试", e)
        is UnknownHostException -> IllegalStateException("无法连接 GitHub，请检查网络连接", e)
        else -> IllegalStateException("网络请求失败：${e.message ?: "请检查网络后重试"}", e)
    }

    private fun parseSemVer(raw: String): ParsedSemVer {
        val cleaned = normalizeVersionTag(raw).substringBefore('+')
        val hyphenIndex = cleaned.indexOf('-')
        val corePart = if (hyphenIndex >= 0) cleaned.substring(0, hyphenIndex) else cleaned
        val prePart = if (hyphenIndex >= 0) cleaned.substring(hyphenIndex + 1) else ""

        val coreNumbers = if (corePart.isBlank()) {
            listOf(0)
        } else {
            corePart.split('.').map { segment ->
                segment.trim().toIntOrNull()
                    ?: segment.trim().takeWhile { it.isDigit() }.toIntOrNull()
                    ?: 0
            }
        }

        val preIdentifiers = prePart
            .split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return ParsedSemVer(core = coreNumbers, preRelease = preIdentifiers)
    }

    private fun comparePreReleaseIdentifier(left: String, right: String): Int {
        val leftInt = left.toIntOrNull()
        val rightInt = right.toIntOrNull()
        return when {
            leftInt != null && rightInt != null -> leftInt.compareTo(rightInt)
            leftInt != null -> -1
            rightInt != null -> 1
            else -> left.compareTo(right)
        }
    }
}
