package us.foxtek.pulseserver.mpd

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal MPD control client. Speaks the MPD protocol directly instead of
 * pulling in libmpdclient: greeting, `outputs` to resolve a name to an id,
 * then `enableoutput`/`disableoutput`.
 */
object MpdClient {

    private const val CONNECT_TIMEOUT_MS = 1_000
    private const val READ_TIMEOUT_MS    = 1_000

    fun setOutputEnabled(config: MpdPreferences.Config, enabled: Boolean): Result<Unit> =
        setOutputEnabled(config.host, config.port, config.outputName, enabled)

    fun setOutputEnabled(
        host: String,
        port: Int,
        outputName: String,
        enabled: Boolean,
    ): Result<Unit> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()

            val greeting = reader.readLine()
            require(greeting != null && greeting.startsWith("OK MPD")) {
                "Not an MPD server: $greeting"
            }

            val outputId = findOutputId(reader, writer, outputName)
            val command = if (enabled) "enableoutput" else "disableoutput"
            writer.write("$command $outputId\n")
            writer.flush()

            val reply = reader.readLine()
            check(reply == "OK") { "MPD rejected $command: $reply" }
        }
    }

    private fun findOutputId(
        reader: BufferedReader,
        writer: BufferedWriter,
        outputName: String,
    ): Int {
        writer.write("outputs\n")
        writer.flush()

        var currentId: Int? = null
        var matchedId: Int? = null
        while (true) {
            val line = reader.readLine() ?: error("MPD closed the connection")
            when {
                line == "OK" -> break
                line.startsWith("ACK ") -> error("MPD: $line")
                line.startsWith("outputid: ") ->
                    currentId = line.removePrefix("outputid: ").trim().toIntOrNull()
                line.startsWith("outputname: ") -> {
                    val name = line.removePrefix("outputname: ").trim()
                    if (name == outputName) matchedId = currentId
                }
            }
        }
        return matchedId ?: error("MPD output '$outputName' not found")
    }
}
