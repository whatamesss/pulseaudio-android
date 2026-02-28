package us.foxtek.pulseserver.service

import android.content.Intent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class PulseAudioServiceTest {

    @Test
    fun onStatusTransitionsStateForConnectedAndDisconnected() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()

        invokeOnStatus(service, "Client connected: 10.0.0.5")
        assertTrue(service.state is ServerState.ClientConnected)

        val ss = ServerSocket(0)
        setPrivateField(service, "serverSocket", ss)
        invokeOnStatus(service, "Client disconnected")
        assertTrue(service.state is ServerState.Running)

        ss.close()
        setPrivateField(service, "serverSocket", null)
        invokeOnStatus(service, "Client disconnected")
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun onStartCommandStopMovesToIdle() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()
        val stopIntent = Intent(service, PulseAudioService::class.java).setAction(PulseAudioService.ACTION_STOP)
        service.onStartCommand(stopIntent, 0, 1)
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun startAndStopServerTransitionsState() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()

        service.startServer()
        Thread.sleep(150)
        assertTrue(service.state is ServerState.Starting || service.state is ServerState.Running)

        service.stopServer()
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun onStartCommandStartThenDestroyEndsIdle() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()
        val startIntent = Intent(service, PulseAudioService::class.java).setAction(PulseAudioService.ACTION_START)
        service.onStartCommand(startIntent, 0, 1)
        Thread.sleep(100)
        assertTrue(service.state is ServerState.Starting || service.state is ServerState.Running)

        service.onDestroy()
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun onBindReturnsLocalBinderAndHelperIpIsAvailable() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()
        val binder = service.onBind(Intent(service, PulseAudioService::class.java))
        assertTrue(binder is PulseAudioService.LocalBinder)

        val m = PulseAudioService::class.java.getDeclaredMethod("getLocalIpAddress")
        m.isAccessible = true
        val ip = m.invoke(service) as String
        assertTrue(ip.isNotBlank())

        val buildNotification = PulseAudioService::class.java.getDeclaredMethod("buildNotification", String::class.java)
        buildNotification.isAccessible = true
        val notification = buildNotification.invoke(service, "hello")
        assertNotNull(notification)
    }

    private fun invokeOnStatus(service: PulseAudioService, msg: String) {
        val m = PulseAudioService::class.java.getDeclaredMethod("onStatus", String::class.java)
        m.isAccessible = true
        m.invoke(service, msg)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
