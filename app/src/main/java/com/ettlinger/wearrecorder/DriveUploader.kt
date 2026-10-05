package com.ettlinger.wearrecorder

import android.util.AtomicFile
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Uses the Drive SDK's resumable uploader and persistent IDs for safe retries. */
class DriveUploader(private val apiRoot: String = "https://www.googleapis.com") {
    companion object {
        private const val TAG = "Drive"
        const val FOLDER_NAME = "ClawHark"
        private const val FILE_FIELDS = "id,name,size,md5Checksum"
        fun receipt(file: File) = AtomicFile(File(file.parentFile,
            file.name.removeSuffix(".uploading") + ".drive-id"))
    }

    private var folderId: String? = null

    suspend fun uploadFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) return@withContext false
        val token = AuthManager.getAccessToken() ?: return@withContext false
        val session = AuthManager.authorizationSession() ?: return@withContext false
        val context = currentCoroutineContext()
        val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance()) { request ->
            request.connectTimeout = 30_000
            request.readTimeout = 120_000
            request.numberOfRetries = 0 // WorkManager owns persistent retry/backoff.
            request.isLoggingEnabled = false
            request.headers.authorization = "Bearer $token"
            request.interceptor = com.google.api.client.http.HttpExecuteInterceptor {
                context.ensureActive()
                if (AuthManager.authorizationSession() != session) throw CancellationException("Account disconnected")
            }
        }.setRootUrl("$apiRoot/").setApplicationName("ClawHark").build()
        val originalName = file.name.removeSuffix(".uploading")
        try {
            val folder = folderId ?: run {
                val found = drive.files().list()
                    .setQ("name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
                    .setPageSize(1).setFields("files(id)").execute().files.orEmpty()
                val id = found.firstOrNull()?.id ?: drive.files().create(
                    DriveFile().setName(FOLDER_NAME).setMimeType("application/vnd.google-apps.folder")
                ).setFields("id").execute().id
                checkNotNull(id).also { folderId = it }
            }
            val state = receipt(file)
            val saved = if (state.baseFile.exists()) JSONObject(state.readFully().toString(Charsets.UTF_8)) else null
            val id = if (saved?.optString("session") == session) saved.getString("id") else {
                val generated = drive.files().generateIds().setCount(1).setSpace("drive").execute().ids.single()
                val stream = state.startWrite()
                try {
                    stream.write(JSONObject().put("id", generated).put("session", session).toString().toByteArray())
                    stream.fd.sync()
                    state.finishWrite(stream)
                } catch (error: Exception) {
                    state.failWrite(stream)
                    throw error
                }
                // AtomicFile can log a failed final rename without throwing. Do not upload
                // unless the receipt is actually readable at its committed destination.
                val persisted = JSONObject(state.readFully().toString(Charsets.UTF_8))
                check(persisted.getString("id") == generated && persisted.getString("session") == session) {
                    "Drive upload receipt was not committed"
                }
                generated
            }
            val checksum = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    checksum.update(buffer, 0, count)
                }
            }
            val expectedChecksum = checksum.digest().joinToString("") { "%02x".format(it) }
            val metadata = DriveFile().setId(id).setName(originalName)
                .setMimeType("audio/mp4").setParents(listOf(folder))
            val create = drive.files().create(metadata, FileContent("audio/mp4", file)).setFields(FILE_FIELDS)
                .setDisableGZipContent(true)
            create.mediaHttpUploader.apply {
                isDirectUploadEnabled = false
                chunkSize = 256 * 1024
                disableGZipContent = true
                setProgressListener { context.ensureActive() }
            }
            val remote = try {
                create.execute()
            } catch (error: HttpResponseException) {
                if (error.statusCode != 409) throw error
                // A lost acknowledgement can leave the ID already uploaded. Verify it before cleanup.
                drive.files().get(id).setFields(FILE_FIELDS).execute()
            }
            context.ensureActive()
            val verified = remote.id == id && remote.name == originalName &&
                remote.getSize() == file.length() && remote.md5Checksum == expectedChecksum
            if (!verified) AppLog.e(TAG, "Drive acknowledgement did not match local audio; preserving $originalName")
            verified
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpResponseException) {
            if (error.statusCode == 401) AuthManager.invalidateAccessToken()
            if (error.statusCode == 404) folderId = null
            AppLog.e(TAG, "Drive upload failed HTTP ${error.statusCode}; preserving $originalName")
            false
        } catch (error: IOException) {
            AppLog.e(TAG, "Drive upload interrupted; preserving $originalName")
            false
        } catch (error: Exception) {
            AppLog.e(TAG, "Drive upload failed; preserving $originalName", error)
            false
        }
    }
}
