package com.yarmiplaytv.relay

import com.yarmiplaytv.Logger
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.UploadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/** Serves the server's upload requests for offered files: each a streamed `PUT` of the asked range. */
class RelayUploader(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val local: LocalLibrary,
    private val offers: RelayOffers,
    private val http: OkHttpClient,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Reading the local file failed (as opposed to the network): the server should hear about it. */
    private class ReadFailure(message: String) : IOException(message)

    fun start() {
        scope.launch {
            sync.events.collect { event ->
                when (event) {
                    is SyncplayEvent.UploadRequested -> upload(event.request)
                    is SyncplayEvent.UploadCancelled -> jobs.remove(event.id)?.cancel()
                    is SyncplayEvent.SessionEnded -> cancelAll()
                    else -> Unit
                }
            }
        }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun upload(request: UploadRequest) {
        jobs.remove(request.id)?.cancel()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                serve(request)
            } finally {
                jobs.remove(request.id, coroutineContext[Job])
            }
        }
        jobs[request.id] = job
        job.start()
    }

    private suspend fun serve(request: UploadRequest) {
        val file = offers.fileFor(request.size, request.quickHash)
            ?: return fail(request, "not offered")
        val channel = try {
            local.openChannel(file.uri)
        } catch (e: Exception) {
            return fail(request, "file not found")
        }
        channel.use {
            if (channel.size() != request.size) return fail(request, "file changed")
            if (request.offset < 0 || request.length <= 0 || request.offset + request.length > request.size) {
                return fail(request, "bad range")
            }
            val session = sync.session.value ?: return
            val call = http.newCall(
                Request.Builder()
                    .url("${session.baseUrl}/yarmiplay/upload/${request.id}")
                    .header("Authorization", session.authorization)
                    .put(rangeBody(channel, request.offset, request.length))
                    .build(),
            )
            try {
                call.await().use { response ->
                    when (response.code) {
                        204, 200 -> Unit
                        404, 409 -> Unit
                        else -> Logger.w(TAG, "Relay upload of ${file.name} answered ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReadFailure) {
                fail(request, "read error")
            } catch (e: IOException) {
                // The server takes the request back and asks someone else.
                if (e.cause is ReadFailure) fail(request, "read error")
                else Logger.d(TAG, "Relay upload of ${file.name} ended: ${e.message}")
            }
        }
    }

    private fun fail(request: UploadRequest, error: String) {
        Logger.w(TAG, "Can't serve relay upload of ${request.size} bytes: $error")
        sync.reportUploadFailed(request.id, error)
    }

    private fun rangeBody(channel: FileChannel, offset: Long, length: Long) = object : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = length
        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteBuffer.allocate(BUFFER)
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                buffer.clear()
                if (remaining < BUFFER) buffer.limit(remaining.toInt())
                val n = try {
                    channel.read(buffer, pos)
                } catch (e: IOException) {
                    throw ReadFailure(e.message ?: "read error")
                }
                if (n < 0) throw ReadFailure("file ended early")
                sink.write(buffer.array(), 0, n)
                pos += n
                remaining -= n
            }
        }
    }

    companion object {
        private const val TAG = "RelayUploader"
        private const val BUFFER = 256 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
