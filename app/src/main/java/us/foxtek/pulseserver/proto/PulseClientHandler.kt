package us.foxtek.pulseserver.proto

import android.util.Log
import us.foxtek.pulseserver.audio.AudioBuffer
import us.foxtek.pulseserver.audio.AudioPlayer
import us.foxtek.pulseserver.audio.pcmBytesPerFrame
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.Socket

private const val TAG = "PulseClientHandler"

/**
 * Handles one connected PA client.
 *
 * Implements the minimal PA native protocol server:
 *   - AUTH handshake
 *   - SET_CLIENT_NAME
 *   - CREATE_PLAYBACK_STREAM  → routes audio to AudioTrack (speaker)
 *   - Info queries (server/sink)
 *   - Graceful cleanup
 *
 * This is a playback-only server: record/source commands are rejected, so no
 * microphone permission or capture code is needed.
 */
class PulseClientHandler(
    private val socket: Socket,
    private val onStatus: (String) -> Unit,
    private val scope: CoroutineScope,
    private val bufferMillis: Int = AudioBuffer.DEFAULT_MILLIS
) {
    private val input  = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Object()

    // Negotiated protocol version (filled in during AUTH)
    private var clientVersion = 0

    // Stream state
    private var playbackChannelId: Int? = null   // channel for incoming PCM from PA client

    private var audioPlayer: AudioPlayer? = null

    // Credit tracking for playback: how many bytes the client is allowed to write
    private var playbackCredit  = 0L
    private var playbackTlength = PAConst.TL_BYTES.toLong()
    private var playbackMinreq  = PAConst.MINREQ_BYTES.toLong()
    private var playbackCorked  = false

    // Volume state is shared across all connections via ServerAudioState
    private var playbackRequestJob: Job? = null

    fun run() {
        try {
            onStatus("Client connected: ${socket.inetAddress.hostAddress}")
            while (!socket.isClosed) {
                val frame = PulseFrame.read(input)
                dispatchFrame(frame)
            }
        } catch (e: EOFException) {
            // Normal disconnect
        } catch (e: Exception) {
            if (!socket.isClosed) Log.w(TAG, "Client handler error", e)
        } finally {
            cleanup()
            onStatus("Client disconnected")
        }
    }

    private fun dispatchFrame(frame: PulseFrame) {
        if (frame.isCommand) {
            handleCommandFrame(frame)
        } else {
            handleAudioFrame(frame)
        }
    }

    // ─── Command dispatch ─────────────────────────────────────────────────────

    private fun handleCommandFrame(frame: PulseFrame) {
        val r = TagStructReader(frame.payload)
        val command = r.readU32().toInt()
        val tag     = r.readU32()

        Log.d(TAG, "CMD $command tag=$tag")

        when (command) {
            PACommand.AUTH                  -> handleAuth(r, tag)
            PACommand.SET_CLIENT_NAME       -> handleSetClientName(r, tag)
            PACommand.CREATE_PLAYBACK_STREAM-> handleCreatePlayback(r, tag)
            PACommand.CREATE_RECORD_STREAM  -> rejectRecordStream(tag)
            PACommand.DELETE_PLAYBACK_STREAM-> handleDeletePlayback(r, tag)
            PACommand.DELETE_RECORD_STREAM  -> sendReply(tag)
            PACommand.GET_SERVER_INFO       -> handleGetServerInfo(tag)
            PACommand.GET_SINK_INFO,
            PACommand.GET_SINK_INFO_LIST    -> handleGetSinkInfo(r, tag)
            PACommand.GET_SOURCE_INFO       -> sendError(tag, PAError.NO_ENTITY)
            PACommand.GET_SOURCE_INFO_LIST  -> sendReply(tag)  // no sources
            PACommand.LOOKUP_SINK           -> handleLookupSink(r, tag)
            PACommand.LOOKUP_SOURCE         -> sendError(tag, PAError.NO_ENTITY)
            PACommand.GET_SINK_INPUT_INFO,
            PACommand.GET_SINK_INPUT_INFO_LIST,
            PACommand.GET_SOURCE_OUTPUT_INFO,
            PACommand.GET_SOURCE_OUTPUT_INFO_LIST,
            PACommand.GET_CLIENT_INFO,
            PACommand.GET_CLIENT_INFO_LIST,
            PACommand.GET_MODULE_INFO,
            PACommand.GET_MODULE_INFO_LIST,
            PACommand.GET_SAMPLE_INFO,
            PACommand.GET_SAMPLE_INFO_LIST,
            PACommand.GET_CARD_INFO,
            PACommand.GET_CARD_INFO_LIST,
            PACommand.SET_CARD_PROFILE,
            PACommand.SET_SINK_PORT,
            PACommand.SET_SOURCE_PORT,
            PACommand.EXTENSION,
            PACommand.SUSPEND_SINK,
            PACommand.SUSPEND_SOURCE,
            PACommand.MOVE_SINK_INPUT,
            PACommand.MOVE_SOURCE_OUTPUT    -> sendReply(tag)  // empty/ack reply
            PACommand.SET_SINK_VOLUME       -> handleSetSinkVolume(r, tag, hasName = true)
            PACommand.SET_SINK_INPUT_VOLUME -> handleSetSinkVolume(r, tag, hasName = false)
            PACommand.SET_SOURCE_VOLUME,
            PACommand.SET_SOURCE_MUTE       -> sendReply(tag)  // playback-only: no source
            PACommand.SET_SINK_MUTE         -> handleSetSinkMute(r, tag)
            PACommand.SET_SINK_INPUT_MUTE,
            PACommand.SET_DEFAULT_SINK,
            PACommand.SET_DEFAULT_SOURCE,
            PACommand.KILL_CLIENT,
            PACommand.KILL_SINK_INPUT,
            PACommand.KILL_SOURCE_OUTPUT,
            PACommand.UPDATE_CLIENT_PROPLIST,
            PACommand.PREBUF_PLAYBACK_STREAM-> sendReply(tag)  // empty/ack reply
            PACommand.SUBSCRIBE             -> sendReply(tag)  // ack, don't track mask
            PACommand.DRAIN_PLAYBACK_STREAM -> handleDrainPlayback(r, tag)
            PACommand.FLUSH_PLAYBACK_STREAM -> handleFlushPlayback(r, tag)
            PACommand.TRIGGER_PLAYBACK_STREAM -> sendReply(tag)
            PACommand.CORK_PLAYBACK_STREAM  -> handleCorkPlayback(r, tag)
            PACommand.CORK_RECORD_STREAM    -> sendReply(tag)
            PACommand.FLUSH_RECORD_STREAM   -> sendReply(tag)
            PACommand.GET_PLAYBACK_LATENCY  -> handleGetPlaybackLatency(r, tag)
            PACommand.GET_RECORD_LATENCY    -> handleGetRecordLatency(r, tag)
            PACommand.STAT                  -> handleStat(tag)
            PACommand.EXIT                  -> { socket.close() }
            // Unsupported commands: send ERROR with PA_ERR_COMMAND
            else -> {
                Log.d(TAG, "Unsupported command $command, sending error")
                sendError(tag, PAError.COMMAND)
            }
        }
    }

    // ─── Handlers ─────────────────────────────────────────────────────────────

    private fun handleAuth(r: TagStructReader, tag: Long) {
        val versionWord = r.readU32()
        val remoteVersion = (versionWord and 0xFFFFL).toInt()
        val _cookie       = r.readArbitrary()  // accept any cookie (open access)

        // Negotiate: use the lower of client and server version.
        // The client will cap itself to our advertised version for subsequent messages.
        clientVersion = minOf(remoteVersion, PAConst.PROTOCOL_VERSION)

        Log.i(TAG, "AUTH: remote version=$remoteVersion negotiated version=$clientVersion")

        // Reply: our server version, no SHM flag bits set
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeU32(PAConst.PROTOCOL_VERSION.toLong())
        sendCommand(w.toByteArray())
    }

    private fun handleSetClientName(r: TagStructReader, tag: Long) {
        // v13+ sends a proplist; earlier sends a string
        if (clientVersion >= 13) {
            r.readProplist()
        } else {
            r.readString()
        }

        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        if (clientVersion >= 13) {
            w.writeU32(0L)  // client_index = 0
        }
        sendCommand(w.toByteArray())
        onStatus("PA client ready (proto v$clientVersion)")
    }

    private fun handleCreatePlayback(r: TagStructReader, tag: Long) {
        // Parse only what we need; the rest we skip via safe read+ignore
        if (clientVersion < 13) r.readString()  // stream name

        val (format, channels, rate) = r.readSampleSpec()
        val channelMap = r.readChannelMap()
        val sinkIndex  = r.readU32()    // PA_INVALID_INDEX or 0
        val sinkName   = r.readString()
        val maxlength  = r.readU32()
        val corked     = r.readBoolean()
        r.readU32()     // client tlength; we advertise our own buffer instead
        val prebuf     = r.readU32()
        val minreq     = r.readU32()
        val syncid     = r.readU32()    // sync group ID (v1+)
        val volume     = r.readCVolume()

        // v12: no_remap, no_remix, fix_format, fix_rate, fix_channels, no_move, variable_rate
        if (clientVersion >= 12 && r.remaining() >= 7) {
            r.readBoolean(); r.readBoolean(); r.readBoolean()
            r.readBoolean(); r.readBoolean(); r.readBoolean(); r.readBoolean()
        }
        // v13: muted, adjust_latency, proplist
        if (clientVersion >= 13 && r.remaining() > 0) {
            r.readBoolean(); r.readBoolean(); r.readProplist()
        }
        // v14: volume_set, early_requests
        if (clientVersion >= 14 && r.remaining() >= 2) { r.readBoolean(); r.readBoolean() }
        // v15: muted_set, dont_inhibit_auto_suspend, fail_on_suspend
        if (clientVersion >= 15 && r.remaining() >= 3) { r.readBoolean(); r.readBoolean(); r.readBoolean() }
        // v17: relative_volume; v18: passthrough
        if (clientVersion >= 17 && r.remaining() > 0) { r.readBoolean() }
        if (clientVersion >= 18 && r.remaining() > 0) { r.readBoolean() }
        // v21: n_formats + format_info list
        if (clientVersion >= 21 && r.remaining() > 0) {
            val nFormats = r.readU8()
            repeat(nFormats) { r.readFormatInfo() }
        }

        Log.i(
            TAG,
            "CREATE_PLAYBACK: format=$format ch=$channels rate=$rate " +
                "corked=$corked buffer=${bufferMillis}ms"
        )

        val actualRate   = if (rate > 0 && rate <= 192000) rate.toInt() else PAConst.SAMPLE_RATE
        val actualFormat = format
        if (!isSupportedPlaybackFormat(actualFormat) || channels !in 1..2) {
            Log.w(TAG, "Unsupported playback format=$actualFormat ch=$channels")
            sendError(tag, PAError.NOT_SUPPORTED)
            return
        }

        // A client may create a second stream without deleting the first.
        if (audioPlayer != null) stopPlaybackStream()

        // The client's requested tlength is only a hint. Advertise the
        // configured buffer instead so the app queue and the client's write
        // credit agree. A small queue fed by a large client burst is what
        // makes audio hiccup and skip.
        val bytesPerFrame = pcmBytesPerFrame(actualFormat, channels)
        val clientPre = if (prebuf > 0 && prebuf != PAConst.INVALID_INDEX)
            prebuf else PAConst.PREBUF_BYTES.toLong()
        val clientMin = if (minreq > 0 && minreq != PAConst.INVALID_INDEX)
            minreq else PAConst.MINREQ_BYTES.toLong()
        val clientMax = if (maxlength > 0 && maxlength != PAConst.INVALID_INDEX)
            maxlength else PAConst.MAX_BYTES.toLong()

        val minreqMs = AudioBuffer.millisForBytes(clientMin, actualRate, bytesPerFrame)
        val effectiveBufferMs = maxOf(AudioBuffer.clampMillis(bufferMillis), minreqMs)
        val actualTl = AudioBuffer.bytesForMillis(
            effectiveBufferMs, actualRate, bytesPerFrame
        ).toLong()
        val actualMax = maxOf(clientMax, actualTl)
        val actualPre = clientPre.coerceAtMost(actualTl)
        val actualMin = clientMin.coerceAtMost(actualTl)

        val chanId = 0  // playback channel
        playbackChannelId = chanId
        playbackCorked   = corked

        val player = AudioPlayer(actualRate, channels, actualFormat)
        player.setVolume(sinkGain())
        player.setMute(ServerAudioState.sinkMuted.get())
        audioPlayer = player
        player.startBuffered(scope, effectiveBufferMs)
        if (corked) player.pause()

        val initialCredit = actualTl
        playbackCredit  = initialCredit
        playbackTlength = actualTl
        playbackMinreq  = actualMin

        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeU32(chanId.toLong())                    // channel
        w.writeU32(0L)                                 // sink_input index
        w.writeU32(initialCredit)                      // requested_bytes (missing data)

        if (clientVersion >= 9) {
            w.writeU32(actualMax)
            w.writeU32(actualTl)
            w.writeU32(actualPre)
            w.writeU32(actualMin)
        }
        if (clientVersion >= 12) {
            w.writeSampleSpec(actualFormat, channels, actualRate.toLong())
            w.writeChannelMap(channelMap)              // echo client's channel map exactly
            w.writeU32(0L)                             // sink_index
            w.writeString("AndroidSink")
            w.writeBoolean(false)                      // not suspended
        }
        if (clientVersion >= 13) {
            w.writeUsec(0L)  // configured_sink_latency_usec
        }
        if (clientVersion >= 21) {
            w.writeFormatInfo(PAConst.SAMPLE_FORMAT, channels, actualRate)
        }
        sendCommand(w.toByteArray())

        // Periodically send REQUEST frames to solicit more audio from client
        startPlaybackRequestLoop(chanId)

        onStatus("Playback stream created (speaker active)")
    }

    private fun rejectRecordStream(tag: Long) {
        Log.i(TAG, "CREATE_RECORD rejected: playback-only server")
        sendError(tag, PAError.NOT_SUPPORTED)
    }

    private fun handleSetSinkVolume(r: TagStructReader, tag: Long, hasName: Boolean = true) {
        r.readU32()           // index
        if (hasName) r.readString()  // name (SET_SINK_VOLUME has name, SET_SINK_INPUT_VOLUME doesn't)
        val volumes = r.readCVolume()
        // Use the max channel volume, normalised to 0..1
        val maxVol = volumes.maxOrNull() ?: PAConst.VOLUME_NORM
        ServerAudioState.sinkVolume.set(maxVol)
        val gain = sinkGain()
        audioPlayer?.setVolume(gain)
        Log.i(TAG, "SET_SINK_VOLUME: pa_vol=$maxVol gain=$gain")
        sendReply(tag)
        sendSubscribeEvent(PASubscription.FACILITY_SINK, 0)
    }

    private fun handleSetSinkMute(r: TagStructReader, tag: Long, hasName: Boolean = true) {
        r.readU32()
        if (hasName) r.readString()
        val mute = r.readBoolean()
        ServerAudioState.sinkMuted.set(mute)
        audioPlayer?.setMute(mute)
        Log.i(TAG, "SET_SINK_MUTE: mute=$mute")
        sendReply(tag)
        sendSubscribeEvent(PASubscription.FACILITY_SINK, 0)
    }

    private fun sendSubscribeEvent(facility: Int, index: Int) {
        val eventType = (PASubscription.EVENT_CHANGE or facility).toLong()
        val w = TagStructWriter()
        w.writeU32(PACommand.SUBSCRIBE_EVENT.toLong())
        w.writeU32(0L)
        w.writeU32(eventType)
        w.writeU32(index.toLong())
        sendCommand(w.toByteArray())
    }

    private fun handleCorkPlayback(r: TagStructReader, tag: Long) {
        r.readU32()  // channel index
        val cork = r.readBoolean()
        playbackCorked = cork
        if (cork) {
            audioPlayer?.pause()
        } else {
            audioPlayer?.resume()
        }
        Log.i(TAG, "Playback ${if (cork) "corked" else "uncorked"}")
        sendReply(tag)
    }

    private fun handleFlushPlayback(r: TagStructReader, tag: Long) {
        r.readU32()  // channel index
        audioPlayer?.flush()
        playbackCredit = playbackTlength
        sendReply(tag)
    }

    private fun handleDrainPlayback(r: TagStructReader, tag: Long) {
        r.readU32()  // channel index
        val drained = audioPlayer?.awaitDrained(2_000L) ?: true
        if (!drained) Log.w(TAG, "DRAIN timed out with audio still queued")
        sendReply(tag)
    }

    private fun sinkGain(): Float =
        (ServerAudioState.sinkVolume.get().toFloat() / PAConst.VOLUME_NORM.toFloat())
            .coerceIn(0f, 1f)

    private fun isSupportedPlaybackFormat(format: Int): Boolean = when (format) {
        PASampleFormat.S16LE, PASampleFormat.S16BE,
        PASampleFormat.S32LE, PASampleFormat.S32BE,
        PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> true
        else -> false
    }

    private fun stopPlaybackStream() {
        playbackRequestJob?.cancel()
        playbackRequestJob = null
        audioPlayer?.stop()
        audioPlayer = null
        playbackChannelId = null
    }

    private fun handleDeletePlayback(r: TagStructReader, tag: Long) {
        r.readU32()
        stopPlaybackStream()
        sendReply(tag)
        onStatus("Playback stream deleted")
    }

    private fun handleGetServerInfo(tag: Long) {
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeString("PulseAudio (Android)")  // server name
        w.writeString("15.0")                  // server version string
        w.writeString("android")               // user name
        w.writeString("android-device")        // host name
        w.writeSampleSpec(PAConst.SAMPLE_FORMAT, PAConst.CHANNELS, PAConst.SAMPLE_RATE.toLong())
        w.writeString("AndroidSink")           // default sink name
        w.writeString("AndroidSource")         // default source name
        w.writeU32(0L)                         // cookie
        if (clientVersion >= 15) {
            w.writeChannelMap(byteArrayOf(1, 2))
        }
        sendCommand(w.toByteArray())
    }

    private fun handleGetSinkInfo(r: TagStructReader, tag: Long) {
        // GET_SINK_INFO: U32 index, STRING name  (GET_SINK_INFO_LIST has no args)
        if (r.remaining() > 0) { r.readU32(); if (r.remaining() > 0) r.readString() }
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        writeSinkInfo(w)
        sendCommand(w.toByteArray())
    }

    private fun writeSinkInfo(w: TagStructWriter) {
        w.writeU32(0L)                        // sink index
        w.writeString("AndroidSink")          // name
        w.writeString("Android Speaker Sink") // description
        w.writeSampleSpec(PAConst.SAMPLE_FORMAT, PAConst.CHANNELS, PAConst.SAMPLE_RATE.toLong())
        w.writeChannelMap(byteArrayOf(1, 2))
        w.writeU32(PAConst.INVALID_INDEX)     // owner module
        w.writeCVolume(LongArray(PAConst.CHANNELS) { ServerAudioState.sinkVolume.get() })
        w.writeBoolean(ServerAudioState.sinkMuted.get())
        w.writeU32(PAConst.INVALID_INDEX)     // monitor source index
        w.writeString("AndroidSink.monitor")  // monitor source name
        w.writeUsec(10_000L)                  // latency usec
        w.writeString("module-null-sink")     // driver
        w.writeU32(0x48L)                     // flags: PA_SINK_LATENCY(0x08)|PA_SINK_DYNAMIC_LATENCY(0x40)
        if (clientVersion >= 13) {
            w.writeProplistStrings(mapOf(
                "device.description" to "Android Speaker",
                "device.class" to "sound"
            ))
            w.writeUsec(10_000L)             // configured_latency
        }
        if (clientVersion >= 15) {
            w.writeVolume(PAConst.VOLUME_NORM) // base_volume (PA_TAG_VOLUME 'V')
            w.writeU32(0L)                     // state (PA_SINK_IDLE=0)
            w.writeU32(65537L)                 // n_volume_steps
            w.writeU32(PAConst.INVALID_INDEX)  // card index
        }
        if (clientVersion >= 16) {
            w.writeU32(1L)  // n_ports = 1
            // port entry: name, description, priority
            w.writeString("analog-output")
            w.writeString("Analog Output")
            w.writeU32(9000L)  // priority
            w.writeString("analog-output")  // active port name
        }
    }

    private fun handleLookupSink(r: TagStructReader, tag: Long) {
        val name = r.readString()
        Log.d(TAG, "LOOKUP_SINK name=$name")
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeU32(0L)  // sink index 0
        sendCommand(w.toByteArray())
    }

    private fun handleGetPlaybackLatency(r: TagStructReader, tag: Long) {
        // Request: U32 stream_index, TIMEVAL timestamp (client sends its local time for RTT calc)
        val streamIdx = r.readU32()
        val (tvSec, tvUsec) = r.readTimeval()

        val now = System.currentTimeMillis()
        val nowSec  = now / 1000L
        val nowUsec = (now % 1000L) * 1000L

        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeUsec(10_000L)          // sink latency usec
        w.writeUsec(0L)               // source output latency usec
        w.writeBoolean(true)          // playing
        w.writeTimeval(tvSec, tvUsec) // echo client's timestamp back
        w.writeTimeval(nowSec, nowUsec) // server's current time
        w.writeS64(0L)                // write_index
        w.writeS64(0L)                // read_index
        if (clientVersion >= 13) {
            w.writeU64(0L)            // underrun_for
            w.writeU64(0L)            // playing_for
        }
        sendCommand(w.toByteArray())
    }

    private fun handleGetRecordLatency(r: TagStructReader, tag: Long) {
        // Request: U32 stream_index, TIMEVAL timestamp
        val streamIdx = r.readU32()
        val (tvSec, tvUsec) = r.readTimeval()

        val now = System.currentTimeMillis()
        val nowSec  = now / 1000L
        val nowUsec = (now % 1000L) * 1000L

        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeUsec(0L)               // monitor_latency
        w.writeUsec(10_000L)          // source_latency usec
        w.writeBoolean(true)          // running
        w.writeTimeval(tvSec, tvUsec) // echo client timestamp
        w.writeTimeval(nowSec, nowUsec) // server now
        w.writeS64(0L)                // write_index
        w.writeS64(0L)                // read_index
        sendCommand(w.toByteArray())
    }

    private fun handleStat(tag: Long) {
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        w.writeU32(0L)  // memblocks
        w.writeU32(0L)  // total memblock size
        w.writeU32(0L)  // allocated memblocks
        w.writeU32(0L)  // total allocated memblock size
        w.writeU32(0L)  // sample cache size
        sendCommand(w.toByteArray())
    }

    // ─── Audio frame handling ─────────────────────────────────────────────────

    private fun handleAudioFrame(frame: PulseFrame) {
        val playbackChan = playbackChannelId
        if (playbackChan != null && frame.channel == playbackChan.toLong()) {
            audioPlayer?.enqueue(frame.payload)
            playbackCredit -= frame.payload.size
            // Replenish credit when it drops below minreq so the client
            // keeps sending without waiting for the next REQUEST
            if (playbackCredit < playbackMinreq) {
                val refill = playbackTlength
                sendRequest(playbackChan, refill)
                playbackCredit += refill
            }
        } else {
            Log.w(TAG, "Audio frame on unknown channel ${frame.channel}")
        }
    }

    // ─── Playback REQUEST loop ────────────────────────────────────────────────

    private fun startPlaybackRequestLoop(channelId: Int) {
        playbackRequestJob?.cancel()
        playbackRequestJob = scope.launch {
            // Underflow guard: if credit goes stale (e.g. client paused),
            // send a REQUEST every 50ms to wake it up.
            delay(200L)
            while (isActive && socket.isConnected && !socket.isClosed) {
                delay(50L)
                if (audioPlayer != null && !playbackCorked && playbackCredit <= 0) {
                    val refill = playbackTlength
                    sendRequest(channelId, refill)
                    playbackCredit += refill
                }
            }
        }
    }

    // ─── Wire helpers ─────────────────────────────────────────────────────────

    /** Send a simple REPLY with no additional fields */
    private fun sendReply(tag: Long) {
        val w = TagStructWriter()
        w.writeU32(PACommand.REPLY.toLong())
        w.writeU32(tag)
        sendCommand(w.toByteArray())
    }

    private fun sendError(tag: Long, errorCode: Int) {
        val w = TagStructWriter()
        w.writeU32(PACommand.ERROR.toLong())
        w.writeU32(tag)
        w.writeU32(errorCode.toLong())
        sendCommand(w.toByteArray())
    }

    private fun sendRequest(channelId: Int, bytes: Long) {
        val w = TagStructWriter()
        w.writeU32(PACommand.REQUEST.toLong())
        w.writeU32(0L)  // tag = 0 for server-push
        w.writeU32(channelId.toLong())
        w.writeU32(bytes)
        sendCommand(w.toByteArray())
    }

    private fun sendCommand(tagstruct: ByteArray) {
        val frame = PulseFrame.command(tagstruct)
        synchronized(writeLock) {
            frame.writeTo(output)
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    private fun cleanup() {
        stopPlaybackStream()
        runCatching { socket.close() }
    }
}
