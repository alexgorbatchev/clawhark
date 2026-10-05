package com.ettlinger.wearrecorder

import android.media.MediaCodec
import android.media.MediaMuxer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaMuxer
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioChunkEncoderTest {
    private val destination get() = File(RuntimeEnvironment.getApplication().filesDir, "audio.m4a")

    private fun encoder(): Any {
        return AudioChunkEncoder(destination, RecordingService.SAMPLE_RATE, RecordingService.AAC_BIT_RATE)
    }

    private fun call(encoder: Any, method: String): Any? = encoder.javaClass.getDeclaredMethod(method)
        .apply { isAccessible = true }.invoke(encoder)

    private fun feed(encoder: Any) {
        encoder.javaClass.getDeclaredMethod("feed", ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(encoder, byteArrayOf(1, 2, 3, 4), 4)
    }

    @Test fun incompleteAudioSurvivesRelease() {
        val encoder = encoder()
        feed(encoder)
        call(encoder, "release")
        assertTrue(File(destination.path + ".tmp").length() > 0)
        assertFalse(destination.exists())
    }

    @Test fun publicationCannotOverwriteAnExistingRecording() {
        destination.writeText("previous audio")
        val encoder = encoder()
        feed(encoder)
        assertNull(call(encoder, "complete"))
        assertEquals("previous audio", destination.readText())
        assertTrue(File(destination.path + ".tmp").exists())
    }

    @Test
    @Config(shadows = [FailingMuxer::class])
    fun failedMuxerFinalizationPreservesTemporaryAudio() {
        val encoder = encoder()
        feed(encoder)
        assertNull(call(encoder, "complete"))
        assertFalse(destination.exists())
        assertTrue(File(destination.path + ".tmp").length() > 0)
    }

    @Test
    @Config(shadows = [StalledCodec::class])
    fun stalledInputFailsInsteadOfSilentlyDroppingAudio() {
        val encoder = encoder()
        try {
            feed(encoder)
            fail("A stalled codec must interrupt capture and enter recovery")
        } catch (error: InvocationTargetException) {
            assertTrue(error.targetException is IllegalStateException)
        } finally {
            call(encoder, "release")
        }
    }

    @Implements(MediaMuxer::class)
    class FailingMuxer : ShadowMediaMuxer() {
        @RealObject private lateinit var muxer: MediaMuxer
        @Implementation fun stop() {
            Shadow.directlyOn<Any, MediaMuxer>(muxer, MediaMuxer::class.java, "stop")
            throw IllegalStateException("Disk failed while finalizing")
        }
    }

    @Implements(MediaCodec::class)
    class StalledCodec : ShadowMediaCodec() {
        @Implementation override fun native_dequeueInputBuffer(timeoutUs: Long): Int = MediaCodec.INFO_TRY_AGAIN_LATER
    }
}
