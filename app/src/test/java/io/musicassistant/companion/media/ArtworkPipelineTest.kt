package io.musicassistant.companion.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkPipelineTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch returns null for blank url`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        assertNull(pipeline.fetch(""))
        assertNull(pipeline.fetch(null))
        assertNull(pipeline.fetch("   "))
    }

    @Test
    fun `fetch successful 200 returns non-empty bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val result = pipeline.fetch(server.url("/img.png").toString())
        advanceUntilIdle()
        assertNotNull(result)
        assert((result?.size ?: 0) > 0)
    }

    @Test
    fun `fetch cache hit returns cached bytes without a second request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val url = server.url("/img.png").toString()

        val first = pipeline.fetch(url)
        advanceUntilIdle()
        val second = pipeline.fetch(url)
        advanceUntilIdle()

        assertArrayEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fetch returns null on HTTP 404 with no retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(404))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val result = pipeline.fetch(server.url("/missing.png").toString())
        advanceUntilIdle()
        assertNull(result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fetch retries on HTTP 500 up to max attempts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val result = pipeline.fetch(server.url("/flaky.png").toString())
        advanceUntilIdle()
        assertNull(result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fetch succeeds on retry after first 500`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val result = pipeline.fetch(server.url("/flaky.png").toString())
        advanceUntilIdle()
        assertNotNull(result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cachedOrNull returns null before fetch and bytes after`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val url = server.url("/img.png").toString()
        assertNull(pipeline.cachedOrNull(url))
        pipeline.fetch(url)
        advanceUntilIdle()
        assertNotNull(pipeline.cachedOrNull(url))
    }

    @Test
    fun `fetch returns null for undecodable bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody("not an image"))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        val result = pipeline.fetch(server.url("/bad.png").toString())
        advanceUntilIdle()
        assertNull(result)
    }

    @Test
    fun `fetch adds Authorization header when token provided`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher, authTokenProvider = { "tok123" })
        pipeline.fetch(server.url("/img.png").toString())
        advanceUntilIdle()
        val recorded = server.takeRequest()
        assertEquals("Bearer tok123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `fetch sends no Authorization header without a token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngBytes())))
        val pipeline = ArtworkPipeline(client, ioDispatcher = dispatcher)
        pipeline.fetch(server.url("/img.png").toString())
        advanceUntilIdle()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    // Minimal valid 1x1 PNG (well-known bytes).
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F.toByte(), 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x62, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60, 0x82.toByte()
    )
}
