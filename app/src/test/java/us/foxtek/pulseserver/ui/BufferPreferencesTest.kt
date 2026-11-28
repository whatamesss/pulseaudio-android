package us.foxtek.pulseserver.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import us.foxtek.pulseserver.audio.AudioBuffer

@RunWith(RobolectricTestRunner::class)
class BufferPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        // Tests in this class assume a clean preference store, regardless of
        // what other Robolectric tests may have written.
        context.getSharedPreferences("pulse_server_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsToAudioBufferDefault() {
        assertEquals(AudioBuffer.DEFAULT_MILLIS, BufferPreferences.load(context))
    }

    @Test
    fun savesAndLoadsBufferMillis() {
        BufferPreferences.save(context, 1_500)

        assertEquals(1_500, BufferPreferences.load(context))
    }

    @Test
    fun clampsSavedAndLoadedValuesToSupportedRange() {
        BufferPreferences.save(context, -1)
        assertEquals(AudioBuffer.MIN_MILLIS, BufferPreferences.load(context))

        BufferPreferences.save(context, 9_999)
        assertEquals(AudioBuffer.MAX_MILLIS, BufferPreferences.load(context))
    }
}
