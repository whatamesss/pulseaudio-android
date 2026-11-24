package us.foxtek.pulseserver.ui

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import us.foxtek.pulseserver.R
import us.foxtek.pulseserver.service.PulseAudioService
import us.foxtek.pulseserver.service.ServerState

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test
    fun stateRenderingUpdatesUiForAllStates() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        invokeOnStateChanged(activity, ServerState.Idle)
        idleMain()
        assertEquals("Start Server", activity.view<Button>(R.id.btnToggle).text.toString())
        assertEquals("Server stopped", activity.view<TextView>(R.id.tvStatus).text.toString())
        assertFalse(activity.view<Button>(R.id.btnCopyIp).isEnabled)

        invokeOnStateChanged(activity, ServerState.Starting)
        idleMain()
        assertEquals("Stop Server", activity.view<Button>(R.id.btnToggle).text.toString())
        assertEquals("Starting…", activity.view<TextView>(R.id.tvStatus).text.toString())

        invokeOnStateChanged(activity, ServerState.Running(port = 4713, ip = "10.0.0.2"))
        idleMain()
        assertEquals("10.0.0.2:4713", activity.view<TextView>(R.id.tvAddress).text.toString())
        assertTrue(activity.view<Button>(R.id.btnCopyIp).isEnabled)
        assertTrue(activity.view<TextView>(R.id.tvHint).text.toString().contains("module-tunnel-sink"))

        invokeOnStateChanged(activity, ServerState.ClientConnected("10.0.0.9"))
        idleMain()
        assertTrue(activity.view<TextView>(R.id.tvStatus).text.toString().contains("Client connected: 10.0.0.9"))

        invokeOnStateChanged(activity, ServerState.Error("boom"))
        idleMain()
        assertEquals("Error: boom", activity.view<TextView>(R.id.tvStatus).text.toString())
        assertFalse(activity.view<Button>(R.id.btnCopyIp).isEnabled)
    }

    @Test
    fun toggleServerWithNoBoundServiceIsNoOp() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        setPrivateField(activity, "service", null)

        val m = MainActivity::class.java.getDeclaredMethod("toggleServer")
        m.isAccessible = true
        m.invoke(activity)

        assertTrue(activity.view<TextView>(R.id.tvStatus).text.isNotEmpty())
    }

    @Test
    fun onRequestPermissionsResultDeniedShowsMessage() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.onRequestPermissionsResult(
            100,
            arrayOf("android.permission.RECORD_AUDIO"),
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        idleMain()
        assertEquals("Microphone permission required", activity.view<TextView>(R.id.tvStatus).text.toString())
    }

    @Test
    fun buildConnectionHintContainsIpPortAndCookie() {
        val hint = buildConnectionHint("10.1.2.3", 4713, "deadbeef")
        assertTrue(hint.contains("tcp:10.1.2.3:4713"))
        assertTrue(hint.contains("module-tunnel-source"))
        assertTrue(hint.contains("deadbeef"))
        assertTrue(hint.contains(".config/pulse/cookie"))
    }

    @Test
    fun serviceConnectionCallbacksUpdateBoundState() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val service = PulseAudioService()
        val connection = getPrivateField(activity, "connection") as ServiceConnection

        connection.onServiceConnected(ComponentName("p", "c"), service.LocalBinder())
        assertEquals(true, getPrivateField(activity, "bound"))

        connection.onServiceDisconnected(ComponentName("p", "c"))
        assertEquals(false, getPrivateField(activity, "bound"))
    }

    @Test
    fun copyConnectionInfoWritesClipboardAndHint() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val service = PulseAudioService()
        setPrivateField(service, "state", ServerState.Running(4713, "10.3.4.5"))
        setPrivateField(activity, "service", service)

        val m = MainActivity::class.java.getDeclaredMethod("copyConnectionInfo")
        m.isAccessible = true
        m.invoke(activity)

        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        assertTrue(clip != null && clip.itemCount > 0)
        assertEquals("10.3.4.5:4713", clip?.getItemAt(0)?.text?.toString())
        assertTrue(activity.view<TextView>(R.id.tvHint).text.toString().contains("Copied:"))
    }

    @Test
    fun copyConnectionInfoWithNoServiceReturnsEarly() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        setPrivateField(activity, "service", null)

        val m = MainActivity::class.java.getDeclaredMethod("copyConnectionInfo")
        m.isAccessible = true
        m.invoke(activity)

        assertTrue(activity.view<TextView>(R.id.tvHint).text.toString().isEmpty())
    }

    @Test
    fun copyConnectionInfoUsesDefaultPortForClientConnectedState() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val service = PulseAudioService()
        setPrivateField(service, "state", ServerState.ClientConnected("10.9.8.7"))
        setPrivateField(activity, "service", service)

        val m = MainActivity::class.java.getDeclaredMethod("copyConnectionInfo")
        m.isAccessible = true
        m.invoke(activity)

        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("10.9.8.7:4713", text)
    }

    @Test
    fun toggleServerStopsWhenServiceRunning() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val service = PulseAudioService()
        setPrivateField(service, "state", ServerState.Running(4713, "10.0.0.8"))
        setPrivateField(activity, "service", service)

        val m = MainActivity::class.java.getDeclaredMethod("toggleServer")
        m.isAccessible = true
        m.invoke(activity)

        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun requiredPermissionsIncludeMicrophone() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(requiredPermissions().contains(android.Manifest.permission.RECORD_AUDIO))
        // Robolectric grants no runtime permissions by default.
        assertFalse(hasRequiredPermissions(activity))
    }

    @Test
    fun onRequestPermissionsResultGrantedInvokesTogglePath() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        setPrivateField(activity, "service", null)
        activity.onRequestPermissionsResult(
            100,
            arrayOf("android.permission.RECORD_AUDIO"),
            intArrayOf(PackageManager.PERMISSION_GRANTED)
        )
        idleMain()
        assertTrue(activity.view<TextView>(R.id.tvStatus).text.isNotEmpty())
    }

    private fun invokeOnStateChanged(activity: MainActivity, state: ServerState) {
        val m = MainActivity::class.java.getDeclaredMethod("onStateChanged", ServerState::class.java)
        m.isAccessible = true
        m.invoke(activity, state)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    private fun getPrivateField(target: Any, name: String): Any? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private inline fun <reified T> MainActivity.view(id: Int): T = findViewById(id)
}
