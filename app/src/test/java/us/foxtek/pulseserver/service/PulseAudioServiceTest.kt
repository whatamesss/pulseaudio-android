package us.foxtek.pulseserver.service

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.Socket

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

        // Port 0 lets the OS pick a free port; 4713 may be occupied by another
        // PulseAudio instance on the machine running the tests.
        service.startServer(0)
        Thread.sleep(150)
        assertTrue(service.state is ServerState.Starting || service.state is ServerState.Running)

        service.stopServer()
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun onStartCommandStartThenDestroyEndsIdle() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()
        val startIntent = Intent(service, PulseAudioService::class.java)
            .setAction(PulseAudioService.ACTION_START)
            .putExtra(PulseAudioService.EXTRA_PORT, 0)
        service.onStartCommand(startIntent, 0, 1)
        Thread.sleep(100)
        assertTrue(service.state is ServerState.Starting || service.state is ServerState.Running)

        service.onDestroy()
        assertTrue(service.state is ServerState.Idle)
    }

    @Test
    fun onlyOneClientIsAcceptedAtATime() {
        val service = Robolectric.buildService(PulseAudioService::class.java).create().get()
        service.startServer(0)
        try {
            val port = awaitServerSocket(service).localPort

            Socket("127.0.0.1", port).use { first ->
                awaitState<ServerState.ClientConnected>(service)

                Socket("127.0.0.1", port).use { second ->
                    second.soTimeout = 10_000
                    // The server refuses the second connection by closing it.
                    assertEquals(-1, second.getInputStream().read())
                }
                assertTrue(service.state is ServerState.ClientConnected)
                assertTrue(!first.isClosed)
            }

            awaitState<ServerState.Running>(service)
            awaitNoActiveClient(service)
            Socket("127.0.0.1", port).use {
                awaitState<ServerState.ClientConnected>(service)
            }
        } finally {
            service.stopServer()
        }
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

    private fun awaitServerSocket(service: PulseAudioService): ServerSocket {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val ss = getPrivateField(service, "serverSocket") as ServerSocket?
            if (ss != null) return ss
            Thread.sleep(20)
        }
        fail("Server socket did not open")
        error("unreachable")
    }

    private inline fun <reified T : ServerState> awaitState(
        service: PulseAudioService,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.state is T) return
            Thread.sleep(20)
        }
        fail("State never became ${T::class.simpleName}: ${service.state}")
    }

    private fun awaitNoActiveClient(service: PulseAudioService, timeoutMs: Long = 10_000) {
        val ref = getPrivateField(service, "activeClient")
            as java.util.concurrent.atomic.AtomicReference<*>
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ref.get() == null) return
            Thread.sleep(20)
        }
        fail("Active client was not released")
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
}
