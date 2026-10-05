package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLogTest {
    @Test fun unavailableDiskCannotGrowLogMemoryWithoutBound() {
        val file = File(RuntimeEnvironment.getApplication().filesDir, "missing/logs/app.log")
        AppLog::class.java.getDeclaredField("logFile").apply { isAccessible = true }.set(AppLog, file)
        repeat(20) { AppLog.e("Test", "entry-$it " + "x".repeat(8192)) }
        val buffer = AppLog::class.java.getDeclaredField("buffer").apply { isAccessible = true }.get(AppLog) as StringBuilder
        assertTrue("Repeated write failures must keep a bounded diagnostic buffer", buffer.length <= 65536)
        checkNotNull(file.parentFile).mkdirs()
        AppLog.flush()
        assertTrue("Logging must resume when storage is writable", file.readText().contains("entry-19"))
        assertEquals(0, buffer.length)
    }
}
