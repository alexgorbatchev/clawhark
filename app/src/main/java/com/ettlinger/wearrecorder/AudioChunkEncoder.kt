package com.ettlinger.wearrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Owns one native AAC codec and muxer. Only a finalized chunk becomes uploadable. */
internal class AudioChunkEncoder(private val finalFile: File, private val sampleRate: Int, bitRate: Int) {
    private val tmpFile = File(finalFile.parentFile, finalFile.name + ".tmp")
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var released = false
    private var samplesWritten = false
    private var pcmBytes = 0L
    private val presentationTimeUs get() = pcmBytes * 1_000_000L / (sampleRate * 2)

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Exception) {
            try { codec.release() } catch (_: Exception) {}
            throw error
        }
    }

    fun feed(data: ByteArray, length: Int = data.size) {
        require(length in 0..data.size && length % 2 == 0)
        check(!released)
        var position = 0
        var stalls = 0
        while (position < length) {
            val index = codec.dequeueInputBuffer(10_000L)
            if (index >= 0) {
                val input = checkNotNull(codec.getInputBuffer(index)) { "Missing codec input buffer" }
                input.clear()
                val count = minOf(length - position, input.remaining()) / 2 * 2
                check(count > 0) { "Codec input cannot hold a PCM sample" }
                input.put(data, position, count)
                codec.queueInputBuffer(index, 0, count, presentationTimeUs, 0)
                pcmBytes += count
                position += count
                stalls = 0
            } else {
                check(++stalls <= 100) { "AAC input stalled" }
            }
            drainOutput(false)
        }
    }

    fun complete(): File? {
        check(!released)
        return try {
            var eosSent = false
            for (attempt in 0 until 100) {
                val index = codec.dequeueInputBuffer(10_000L)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                    break
                }
                drainOutput(false)
            }
            check(eosSent && drainOutput(true)) { "AAC end of stream was not completed" }
            check(muxerStarted && samplesWritten) { "No encoded audio samples" }
            // stop() writes the container's final metadata and must succeed before publication.
            muxer.stop()
            muxerStarted = false
            release()
            // Names include a UUID and have one writer. Never replace a pre-existing chunk.
            check(!finalFile.exists() && tmpFile.length() > 0 && tmpFile.renameTo(finalFile)) {
                "Cannot publish completed recording"
            }
            AppLog.i("Encoder", "Finalized ${finalFile.name} (${finalFile.length()} bytes)")
            finalFile
        } catch (error: Exception) {
            AppLog.e("Encoder", "Finalization failed; preserving ${tmpFile.name}", error)
            release()
            null
        }
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Exception) {}
            muxerStarted = false
        }
        try { muxer.release() } catch (_: Exception) {}
        if (tmpFile.length() == 0L) tmpFile.delete()
    }

    /** Returns true only after consuming the codec's output EOS marker. */
    private fun drainOutput(blocking: Boolean): Boolean {
        repeat(if (blocking) 1000 else 100) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (blocking) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "AAC output format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    try {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                            check(muxerStarted) { "AAC output arrived before its format" }
                            val output = checkNotNull(codec.getOutputBuffer(index)) { "Missing codec output buffer" }
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, output, bufferInfo)
                            samplesWritten = true
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!blocking) return false
            }
        }
        return false
    }
}
