package com.typefree.ime.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class AppUpdateCheckerTest {

    @Test
    fun `compareSemanticVersions handles v prefixes, multi-digit segments, and equal versions`() {
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("3.5.1", "v3.5.1"))
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("V3.5.1", " 3.5.1 "))
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("3.6", "v3.6.0"))
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("3.6.0+build42", "v3.6.0"))

        assertTrue(AppUpdateChecker.compareSemanticVersions("3.5.1", "v3.6.0") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.5.2", "v3.5.10") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.5.10", "3.5.2") > 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.10.0", "3.9.99") > 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.5.1", "v3.5.0") > 0)
    }

    @Test
    fun `compareSemanticVersions handles pre-release tags according to semver precedence`() {
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-beta.1", "3.6.0") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0", "v3.6.0-beta.1") > 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-alpha.1", "3.6.0-beta.1") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-beta.2", "3.6.0-beta.10") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-beta.9", "3.6.0-rc.1") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-1", "3.6.0-alpha") < 0)
        assertTrue(AppUpdateChecker.compareSemanticVersions("3.6.0-alpha", "3.6.0-alpha.1") < 0)
        assertEquals(0, AppUpdateChecker.compareSemanticVersions("3.6.0-beta.1", "v3.6.0-beta.1"))
    }

    @Test
    fun `parseLatestReleaseJson selects apk asset among multiple assets and sets update flags`() {
        val json = """
            {
              "tag_name": "v3.6.0",
              "name": "TypeFree v3.6.0",
              "body": "## 更新内容\n- 支持应用内检查更新",
              "html_url": "https://github.com/dengxuezhao/TypeFree/releases/tag/v3.6.0",
              "published_at": "2026-11-16T08:00:00Z",
              "assets": [
                {
                  "name": "checksums.sha256",
                  "browser_download_url": "https://github.com/dengxuezhao/TypeFree/releases/download/v3.6.0/checksums.sha256"
                },
                {
                  "name": "TypeFree-v3.6.0-public.apk",
                  "browser_download_url": "https://github.com/dengxuezhao/TypeFree/releases/download/v3.6.0/TypeFree-v3.6.0-public.apk"
                }
              ]
            }
        """.trimIndent()

        val publicInfo = AppUpdateChecker.parseLatestReleaseJson(
            json = json,
            currentVersion = "3.5.1",
            isPrivateDictBuild = false
        )
        assertEquals("3.5.1", publicInfo.currentVersion)
        assertEquals("v3.6.0", publicInfo.latestTag)
        assertEquals("3.6.0", publicInfo.latestVersion)
        assertEquals("TypeFree v3.6.0", publicInfo.releaseName)
        assertTrue(publicInfo.releaseNotes.contains("支持应用内检查更新"))
        assertEquals(
            "https://github.com/dengxuezhao/TypeFree/releases/download/v3.6.0/TypeFree-v3.6.0-public.apk",
            publicInfo.apkDownloadUrl
        )
        assertEquals(publicInfo.apkDownloadUrl, publicInfo.preferredDownloadUrl)
        assertEquals("2026-11-16T08:00:00Z", publicInfo.publishedAt)
        assertTrue(publicInfo.hasUpdate)
        assertFalse(publicInfo.requiresPrivateDictWarning)

        val privateInfo = AppUpdateChecker.parseLatestReleaseJson(
            json = json,
            currentVersion = "3.5.1",
            isPrivateDictBuild = true
        )
        assertTrue(privateInfo.hasUpdate)
        assertTrue(privateInfo.requiresPrivateDictWarning)
    }

    @Test
    fun `parseLatestReleaseJson falls back to html_url when no apk asset exists and handles empty body`() {
        val json = """
            {
              "tag_name": "v3.5.1",
              "name": "",
              "body": "   ",
              "html_url": "https://github.com/dengxuezhao/TypeFree/releases/tag/v3.5.1",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://github.com/dengxuezhao/TypeFree/archive/refs/tags/v3.5.1.zip"
                }
              ]
            }
        """.trimIndent()

        val info = AppUpdateChecker.parseLatestReleaseJson(
            json = json,
            currentVersion = "3.5.1",
            isPrivateDictBuild = true
        )

        assertNull(info.apkDownloadUrl)
        assertEquals("https://github.com/dengxuezhao/TypeFree/releases/tag/v3.5.1", info.preferredDownloadUrl)
        assertEquals("v3.5.1", info.releaseName)
        assertTrue(info.releaseNotes.isNotBlank())
        assertFalse(info.hasUpdate)
        assertFalse(info.requiresPrivateDictWarning)
    }

    @Test
    fun `parseLatestReleaseJson rejects malformed or incomplete payloads`() {
        val invalidPayloads = listOf(
            "",
            "   ",
            "null",
            "[]",
            "{not-valid-json",
            """{"name": "no tag"}""",
            """{"tag_name": "   "}"""
        )

        invalidPayloads.forEach { payload ->
            val result = runCatching {
                AppUpdateChecker.parseLatestReleaseJson(
                    json = payload,
                    currentVersion = "3.5.1",
                    isPrivateDictBuild = false
                )
            }
            assertTrue("Expected failure for payload: $payload", result.isFailure)
        }
    }

    @Test
    fun `fetchLatestUpdate maps HTTP errors and parses valid responses`() = runBlocking {
        fun clientReturning(code: Int, body: String): OkHttpClient {
            val interceptor = Interceptor { chain ->
                val req = chain.request()
                assertEquals("application/vnd.github+json", req.header("Accept"))
                assertTrue(req.header("User-Agent")?.startsWith("TypeFree-Android/") == true)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Mock")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            return OkHttpClient.Builder().addInterceptor(interceptor).build()
        }

        val forbiddenResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.1",
            isPrivateDictBuild = false,
            client = clientReturning(403, """{"message":"API rate limit exceeded"}""")
        )
        assertTrue(forbiddenResult.isFailure)
        assertTrue(forbiddenResult.exceptionOrNull()?.message?.contains("403") == true)

        val notFoundResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.1",
            isPrivateDictBuild = false,
            client = clientReturning(404, """{"message":"Not Found"}""")
        )
        assertTrue(notFoundResult.isFailure)
        assertTrue(notFoundResult.exceptionOrNull()?.message?.contains("404") == true)

        val emptyResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.1",
            isPrivateDictBuild = false,
            client = clientReturning(200, "   ")
        )
        assertTrue(emptyResult.isFailure)

        val okResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.1",
            isPrivateDictBuild = true,
            client = clientReturning(
                200,
                """{"tag_name":"v3.6.0","html_url":"https://github.com/dengxuezhao/TypeFree/releases/tag/v3.6.0","assets":[]}"""
            )
        )
        assertTrue(okResult.isSuccess)
        val info = okResult.getOrThrow()
        assertTrue(info.hasUpdate)
        assertTrue(info.requiresPrivateDictWarning)
    }

    @Test
    fun `fetchLatestUpdate retries transient connection closed twice and succeeds on third attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val connectionHeaders = mutableListOf<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    connectionHeaders.add(req.header("Connection"))
                    val currentAttempt = attempts.incrementAndGet()
                    if (currentAttempt <= 2) {
                        throw IOException("connection closed")
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"tag_name":"v3.6.0","html_url":"https://github.com/dengxuezhao/TypeFree/releases/tag/v3.6.0","assets":[]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            )
            .build()

        val result = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = client
        )

        assertTrue(result.isSuccess)
        assertEquals(3, attempts.get())
        assertEquals(listOf(null, "close", "close"), connectionHeaders)
        assertTrue(result.getOrThrow().hasUpdate)
    }

    @Test
    fun `fetchLatestUpdate retries EOFException and SocketTimeoutException and returns Chinese error after three failures`() = runBlocking {
        val eofAttempts = AtomicInteger(0)
        val eofThenSuccessClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    if (eofAttempts.incrementAndGet() == 1) {
                        throw EOFException("unexpected end of stream")
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"tag_name":"v3.5.2","html_url":"https://github.com/dengxuezhao/TypeFree/releases/tag/v3.5.2","assets":[]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            )
            .build()

        val eofRecovered = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = eofThenSuccessClient
        )
        assertTrue(eofRecovered.isSuccess)
        assertEquals(2, eofAttempts.get())

        val closedAttempts = AtomicInteger(0)
        val alwaysClosedClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    closedAttempts.incrementAndGet()
                    throw IOException("connection closed")
                }
            )
            .build()

        val closedFailure = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = alwaysClosedClient
        )
        assertTrue(closedFailure.isFailure)
        assertEquals(3, closedAttempts.get())
        assertEquals("网络请求失败：connection closed", closedFailure.exceptionOrNull()?.message)

        val timeoutAttempts = AtomicInteger(0)
        val alwaysTimeoutClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    timeoutAttempts.incrementAndGet()
                    throw SocketTimeoutException("timeout")
                }
            )
            .build()

        val timeoutFailure = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = alwaysTimeoutClient
        )
        assertTrue(timeoutFailure.isFailure)
        assertEquals(3, timeoutAttempts.get())
        assertTrue(timeoutFailure.exceptionOrNull()?.message?.contains("连接 GitHub 超时") == true)
    }

    @Test
    fun `fetchLatestUpdate retries transient 5xx responses and stops after three attempts`() = runBlocking {
        val recoverAttempts = AtomicInteger(0)
        val recoverClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    val code = when (recoverAttempts.incrementAndGet()) {
                        1 -> 502
                        2 -> 503
                        else -> 200
                    }
                    val body = if (code == 200) {
                        """{"tag_name":"v3.6.0","html_url":"https://github.com/dengxuezhao/TypeFree/releases/tag/v3.6.0","assets":[]}"""
                    } else {
                        """{"message":"Bad Gateway"}"""
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Mock")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()

        val recoverResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = recoverClient
        )
        assertTrue(recoverResult.isSuccess)
        assertEquals(3, recoverAttempts.get())

        val exhaustedAttempts = AtomicInteger(0)
        val always504Client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    exhaustedAttempts.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(504)
                        .message("Gateway Timeout")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()

        val exhaustedResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = always504Client
        )
        assertTrue(exhaustedResult.isFailure)
        assertEquals(3, exhaustedAttempts.get())
        assertEquals("检查更新失败 (HTTP 504)", exhaustedResult.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchLatestUpdate does not retry 403 404 429 or invalid JSON and rethrows CancellationException`() = runBlocking {
        listOf(403, 404, 429).forEach { statusCode ->
            val attempts = AtomicInteger(0)
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        attempts.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(statusCode)
                            .message("Error")
                            .body("""{"message":"error"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                )
                .build()

            val result = AppUpdateChecker.fetchLatestUpdate(
                currentVersion = "3.5.2",
                isPrivateDictBuild = false,
                client = client
            )
            assertTrue("Expected failure for HTTP $statusCode", result.isFailure)
            assertEquals("Expected no retries for HTTP $statusCode", 1, attempts.get())
            assertTrue(result.exceptionOrNull()?.message?.contains(statusCode.toString()) == true)
        }

        val jsonAttempts = AtomicInteger(0)
        val invalidJsonClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    jsonAttempts.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{invalid-json".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()
        val jsonResult = AppUpdateChecker.fetchLatestUpdate(
            currentVersion = "3.5.2",
            isPrivateDictBuild = false,
            client = invalidJsonClient
        )
        assertTrue(jsonResult.isFailure)
        assertEquals(1, jsonAttempts.get())

        val cancelAttempts = AtomicInteger(0)
        val cancelClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor {
                    cancelAttempts.incrementAndGet()
                    throw CancellationException("cancelled")
                }
            )
            .build()
        try {
            AppUpdateChecker.fetchLatestUpdate(
                currentVersion = "3.5.2",
                isPrivateDictBuild = false,
                client = cancelClient
            )
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            assertEquals(1, cancelAttempts.get())
        }
    }
}
