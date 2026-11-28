package us.foxtek.pulseserver.mpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MpdClientTest {

    @Test
    fun disablesOutputByName() = withFakeMpd { port, commands ->
        val result = MpdClient.setOutputEnabled("127.0.0.1", port, "Android", false)

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("disableoutput 1", commands.last())
    }

    @Test
    fun enablesOutputByName() = withFakeMpd { port, commands ->
        val result = MpdClient.setOutputEnabled("127.0.0.1", port, "Android", true)

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals("enableoutput 1", commands.last())
    }

    @Test
    fun unknownOutputFails() = withFakeMpd { port, commands ->
        val result = MpdClient.setOutputEnabled("127.0.0.1", port, "Missing", false)

        assertTrue(result.isFailure)
        assertEquals(listOf("outputs"), commands.toList())
    }

    private fun withFakeMpd(block: (Int, CopyOnWriteArrayList<String>) -> Unit) {
        ServerSocket(0).use { server ->
            val commands = CopyOnWriteArrayList<String>()
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("OK MPD 0.23.5\n")
                        writer.flush()

                        while (true) {
                            val line = reader.readLine() ?: break
                            commands.add(line)
                            when {
                                line == "outputs" -> {
                                    writer.write(
                                        "outputid: 0\n" +
                                            "outputname: Speakers\n" +
                                            "outputenabled: 1\n" +
                                            "outputid: 1\n" +
                                            "outputname: Android\n" +
                                            "outputenabled: 0\n" +
                                            "OK\n"
                                    )
                                    writer.flush()
                                }
                                line.startsWith("enableoutput") ||
                                    line.startsWith("disableoutput") -> {
                                    writer.write("OK\n")
                                    writer.flush()
                                }
                            }
                        }
                    }
                }
            }

            block(server.localPort, commands)
            worker.join(10_000)
        }
    }
}
