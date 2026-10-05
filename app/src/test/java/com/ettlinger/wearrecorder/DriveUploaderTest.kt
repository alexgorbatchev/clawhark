package com.ettlinger.wearrecorder

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveUploaderTest {
    private lateinit var server: HttpTestServer
    private lateinit var directory: File

    @Before fun setUp() {
        server = HttpTestServer()
        val app = RuntimeEnvironment.getApplication()
        val auth = app.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        auth.edit().putString("refresh_token", "test-refresh").putString("access_token", "test-access")
            .putLong("token_expiry", System.currentTimeMillis() + 3_600_000).commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, auth)
        directory = File(app.filesDir, "recordings").apply { mkdirs() }
    }

    @After fun tearDown() {
        server.close()
        directory.deleteRecursively()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, null)
    }

    @Test fun uploadUsesOriginalRecordingName() = runTest {
        val file = File(directory, "chunk_2026-10-04_09-00-00.m4a.uploading").apply { writeText("audio") }
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        server.enqueue(200, "{\"id\":\"recording\",\"name\":\"chunk_2026-10-04_09-00-00.m4a\",\"size\":\"5\",\"md5Checksum\":\"$md5\"}")
        assertTrue(DriveUploader(server.baseUrl).uploadFile(file))
        server.takeRequest()
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertEquals("chunk_2026-10-04_09-00-00.m4a", org.json.JSONObject(body).getString("name"))
    }

    @Test fun failedFolderLookupDoesNotCreateAnotherFolder() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeText("audio") }
        server.enqueue(500, "{\"error\":{\"code\":500}}")
        server.enqueue(200, "{\"id\":\"duplicate-folder\"}")
        server.enqueue(200, "{\"id\":\"recording\"}")
        assertFalse(DriveUploader(server.baseUrl).uploadFile(file))
        assertEquals(1, server.requestCount)
        assertTrue(file.exists())
    }

    @Test fun untrustedHttpsCertificateCannotReceiveRecordings() = runTest {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val https = MockWebServer().apply {
            useHttps(tls.sslSocketFactory(), false)
            start()
        }
        try {
            val file = File(directory, "chunk.m4a").apply { writeText("audio") }
            https.enqueue(MockResponse().setBody("{\"files\":[{\"id\":\"folder\"}]}"))
            https.enqueue(MockResponse().setBody("{\"ids\":[\"recording\"]}"))
            https.enqueue(MockResponse().setHeader("Location", https.url("/session")))
            https.enqueue(MockResponse().setBody(acknowledgement(file)))
            assertFalse(DriveUploader(https.url("/").toString().removeSuffix("/")).uploadFile(file))
            assertTrue(file.exists())
            assertFalse("No upload receipt can be created through an untrusted connection", DriveUploader.receipt(file).baseFile.exists())
        } finally {
            https.shutdown()
        }
    }

    @Test fun emptyFolderListCreatesFolderAndUploads() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeText("audio") }
        server.enqueue(200, "{}") // Drive omits empty fields from partial responses.
        server.enqueue(200, "{\"id\":\"folder\"}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        server.enqueue(200, acknowledgement(file))
        assertTrue(DriveUploader(server.baseUrl).uploadFile(file))
        server.takeRequest()
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun retryAfterLostAcknowledgementReusesIdAndVerifiesExistingFile() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeText("audio") }
        val uploader = DriveUploader(server.baseUrl)
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        server.enqueue(400, "{\"error\":{\"code\":400}}")
        assertFalse(uploader.uploadFile(file))
        repeat(4) { server.takeRequest() }
        // A new worker has no folder cache, but must keep the receipt's remote ID.
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(409, "{\"error\":{\"code\":409}}")
        server.enqueue(200, acknowledgement(file))
        assertTrue(DriveUploader(server.baseUrl).uploadFile(file))
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("recording", org.json.JSONObject(retry.body.readUtf8()).getString("id"))
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertTrue(checkNotNull(lookup.path).startsWith("/drive/v3/files/recording?"))
        assertTrue(file.exists())
    }

    @Test fun checksumMismatchKeepsLocalAudio() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeText("audio") }
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        server.enqueue(200, "{\"id\":\"recording\",\"name\":\"chunk.m4a\",\"size\":\"5\",\"md5Checksum\":\"wrong\"}")
        assertFalse(DriveUploader(server.baseUrl).uploadFile(file))
        assertTrue(file.exists())
    }

    @Test
    @Config(shadows = [FailedReceiptPublication::class])
    fun uploadCannotStartUntilReceiptIsDurablyPublished() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeText("audio") }
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        server.enqueue(200, acknowledgement(file))
        assertFalse(DriveUploader(server.baseUrl).uploadFile(file))
        assertEquals(2, server.requestCount)
        assertTrue(file.exists())
    }

    @Implements(AtomicFile::class)
    class FailedReceiptPublication {
        @RealObject private lateinit var state: AtomicFile
        @Implementation fun finishWrite(stream: FileOutputStream) {
            // Force the actual final rename to fail: a file cannot replace a directory.
            state.baseFile.mkdir()
            File(state.baseFile, "blocker").writeText("prevent replacement")
            Shadow.directlyOn<Any, AtomicFile>(state, AtomicFile::class.java, "finishWrite",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(FileOutputStream::class.java, stream))
        }
    }

    @Test fun resumableUploadHonorsServerOffset() = runTest {
        val file = File(directory, "chunk.m4a").apply { writeBytes(ByteArray(300_000) { (it % 127).toByte() }) }
        server.enqueue(200, "{\"files\":[{\"id\":\"folder\"}]}")
        server.enqueue(200, "{\"ids\":[\"recording\"]}")
        server.enqueue(200, headers = mapOf("Location" to "${server.baseUrl}/session"))
        server.enqueue(308, headers = mapOf("Range" to "bytes=0-262143"))
        server.enqueue(200, acknowledgement(file))
        assertTrue(DriveUploader(server.baseUrl).uploadFile(file))
        repeat(3) { server.takeRequest() }
        assertEquals("bytes 0-262143/300000", server.takeRequest().getHeader("Content-Range"))
        val tail = server.takeRequest()
        assertEquals("bytes 262144-299999/300000", tail.getHeader("Content-Range"))
        assertArrayEquals(file.readBytes().copyOfRange(262144, 300000), tail.body.readByteArray())
    }

    private fun acknowledgement(file: File): String {
        val checksum = java.security.MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return org.json.JSONObject().put("id", "recording").put("name", file.name.removeSuffix(".uploading"))
            .put("size", file.length().toString()).put("md5Checksum", checksum).toString()
    }
}
