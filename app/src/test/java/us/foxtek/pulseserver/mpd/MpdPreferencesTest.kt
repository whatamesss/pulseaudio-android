package us.foxtek.pulseserver.mpd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MpdPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun savesAndLoadsConfig() {
        val config = MpdPreferences.Config("mpd.local", 6601, "Android")

        MpdPreferences.save(context, config)

        assertEquals(config, MpdPreferences.load(context))
        assertTrue(MpdPreferences.load(context).isConfigured)
    }

    @Test
    fun blankConfigIsNotConfigured() {
        MpdPreferences.save(context, MpdPreferences.Config("", 6600, ""))

        assertFalse(MpdPreferences.load(context).isConfigured)
    }

    @Test
    fun parsesServerField() {
        assertEquals(
            MpdPreferences.Config("mpd.local", 6601, "Android"),
            MpdPreferences.configFrom(" mpd.local:6601 ", " Android ")
        )
        assertEquals(
            MpdPreferences.Config("mpd.local", MpdPreferences.DEFAULT_PORT, "Android"),
            MpdPreferences.configFrom("mpd.local", "Android")
        )
    }
}
