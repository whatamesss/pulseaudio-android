package us.foxtek.pulseserver.proto

/** PulseAudio tagstruct type tag constants */
object PATag {
    const val STRING: Byte        = 0x74  // 't'
    const val STRING_NULL: Byte   = 0x4E  // 'N'
    const val U32: Byte           = 0x4C  // 'L'
    const val U8: Byte            = 0x42  // 'B'
    const val U64: Byte           = 0x52  // 'R'
    const val S64: Byte           = 0x72  // 'r'
    const val SAMPLE_SPEC: Byte   = 0x61  // 'a'
    const val ARBITRARY: Byte     = 0x78  // 'x'
    const val BOOLEAN_TRUE: Byte  = 0x31  // '1'
    const val BOOLEAN_FALSE: Byte = 0x30  // '0'
    const val TIMEVAL: Byte       = 0x54  // 'T'
    const val USEC: Byte          = 0x55  // 'U'
    const val CHANNEL_MAP: Byte   = 0x6D  // 'm'
    const val CVOLUME: Byte       = 0x76  // 'v'
    const val VOLUME: Byte        = 0x56  // 'V'
    const val PROPLIST: Byte      = 0x50  // 'P'
    const val FORMAT_INFO: Byte   = 0x66  // 'f'
}

/** PulseAudio sample format constants */
object PASampleFormat {
    const val U8       = 0
    const val ALAW     = 1
    const val ULAW     = 2
    const val S16LE    = 3
    const val S16BE    = 4
    const val FLOAT32LE = 5
    const val FLOAT32BE = 6
    const val S32LE    = 7
    const val S32BE    = 8
}

/** PulseAudio command enum values */
object PACommand {
    const val ERROR                      = 0
    const val TIMEOUT                    = 1
    const val REPLY                      = 2
    const val CREATE_PLAYBACK_STREAM     = 3
    const val DELETE_PLAYBACK_STREAM     = 4
    const val CREATE_RECORD_STREAM       = 5
    const val DELETE_RECORD_STREAM       = 6
    const val EXIT                       = 7
    const val AUTH                       = 8
    const val SET_CLIENT_NAME            = 9
    const val LOOKUP_SINK                = 10
    const val LOOKUP_SOURCE              = 11
    const val DRAIN_PLAYBACK_STREAM      = 12
    const val STAT                       = 13
    const val GET_PLAYBACK_LATENCY       = 14
    const val GET_SERVER_INFO            = 20
    const val GET_SINK_INFO              = 21
    const val GET_SINK_INFO_LIST         = 22
    const val GET_SOURCE_INFO            = 23
    const val GET_SOURCE_INFO_LIST       = 24
    const val GET_MODULE_INFO            = 25
    const val GET_MODULE_INFO_LIST       = 26
    const val GET_CLIENT_INFO            = 27
    const val GET_CLIENT_INFO_LIST       = 28
    const val GET_SINK_INPUT_INFO        = 29
    const val GET_SINK_INPUT_INFO_LIST   = 30
    const val GET_SOURCE_OUTPUT_INFO     = 31
    const val GET_SOURCE_OUTPUT_INFO_LIST = 32
    const val GET_SAMPLE_INFO            = 33
    const val GET_SAMPLE_INFO_LIST       = 34
    const val SUBSCRIBE                  = 35
    const val SET_SINK_VOLUME            = 36
    const val SET_SINK_INPUT_VOLUME      = 37
    const val SET_SOURCE_VOLUME          = 38
    const val SET_SINK_MUTE              = 39
    const val SET_SOURCE_MUTE            = 40
    const val CORK_PLAYBACK_STREAM       = 41
    const val FLUSH_PLAYBACK_STREAM      = 42
    const val TRIGGER_PLAYBACK_STREAM    = 43
    const val SET_DEFAULT_SINK           = 44
    const val SET_DEFAULT_SOURCE         = 45
    const val SET_PLAYBACK_STREAM_NAME   = 46
    const val SET_RECORD_STREAM_NAME     = 47
    const val KILL_CLIENT                = 48
    const val KILL_SINK_INPUT            = 49
    const val KILL_SOURCE_OUTPUT         = 50
    const val GET_RECORD_LATENCY         = 57
    const val CORK_RECORD_STREAM         = 58
    const val FLUSH_RECORD_STREAM        = 59
    const val PREBUF_PLAYBACK_STREAM     = 60
    const val UPDATE_CLIENT_PROPLIST     = 82
    const val REQUEST                    = 61  // server → client: write credit
    const val OVERFLOW                   = 62
    const val UNDERFLOW                  = 63
    const val PLAYBACK_STREAM_KILLED     = 64
    const val RECORD_STREAM_KILLED       = 65
    const val SUBSCRIBE_EVENT            = 66
    const val MOVE_SINK_INPUT            = 67
    const val MOVE_SOURCE_OUTPUT         = 68
    const val SET_SINK_INPUT_MUTE        = 69
    const val SUSPEND_SINK               = 70
    const val SUSPEND_SOURCE             = 71
    const val EXTENSION                  = 87
    const val GET_CARD_INFO              = 88
    const val GET_CARD_INFO_LIST         = 89
    const val SET_CARD_PROFILE           = 90
    const val SET_SINK_PORT              = 95
    const val SET_SOURCE_PORT            = 96
}

/** PulseAudio error codes */
object PAError {
    const val ACCESS      = 1
    const val COMMAND     = 2
    const val INVALID     = 3
    const val EXIST       = 4
    const val NO_ENTITY   = 5
    const val CONNECTION_REFUSED = 6
    const val AUTHKEY     = 9
    const val NOT_SUPPORTED = 14
}

/** PA_SUBSCRIPTION_EVENT facility values (low 4 bits of event type) */
object PASubscription {
    const val FACILITY_SINK         = 0x0
    const val FACILITY_SOURCE       = 0x1
    const val FACILITY_SINK_INPUT   = 0x2
    const val FACILITY_SOURCE_OUTPUT= 0x3
    const val FACILITY_CLIENT       = 0x4
    const val FACILITY_SERVER       = 0x7
    const val EVENT_NEW             = 0x00
    const val EVENT_CHANGE          = 0x10
    const val EVENT_REMOVE          = 0x20
}

object PAConst {
    const val DEFAULT_PORT     = 4713
    const val COOKIE_LENGTH    = 256
    const val PROTOCOL_VERSION = 16   // claim v16 to keep things simple
    const val INVALID_INDEX    = 0xFFFFFFFFL
    const val VOLUME_NORM      = 0x10000L
    const val CHANNEL_CMD      = 0xFFFFFFFFL

    // Sample config
    const val SAMPLE_RATE      = 44100
    const val CHANNELS         = 2  // stereo
    const val SAMPLE_FORMAT    = PASampleFormat.S16LE
    const val BYTES_PER_SAMPLE = 2  // S16
    const val BYTES_PER_FRAME  = CHANNELS * BYTES_PER_SAMPLE  // 4

    // Buffer: ~200ms of audio
    const val FRAG_FRAMES      = SAMPLE_RATE / 5   // 8820 frames
    const val FRAG_BYTES       = FRAG_FRAMES * BYTES_PER_FRAME  // 35280

    // Playback buffer attrs
    const val TL_BYTES         = FRAG_BYTES * 4  // tlength
    const val PREBUF_BYTES     = FRAG_BYTES * 2  // prebuf
    const val MINREQ_BYTES     = FRAG_BYTES      // minreq
    const val MAX_BYTES        = FRAG_BYTES * 8  // maxlength
}
