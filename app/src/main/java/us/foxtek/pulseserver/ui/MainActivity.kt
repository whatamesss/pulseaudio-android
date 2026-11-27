package us.foxtek.pulseserver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import us.foxtek.pulseserver.audio.AudioBuffer
import us.foxtek.pulseserver.databinding.ActivityMainBinding
import us.foxtek.pulseserver.mpd.MpdPreferences
import us.foxtek.pulseserver.proto.PAConst
import us.foxtek.pulseserver.service.PulseAudioService
import us.foxtek.pulseserver.service.ServerState

private const val REQUEST_PERMISSIONS = 100

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: PulseAudioService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? PulseAudioService.LocalBinder ?: return
            service = b.service
            bound = true
            b.service.stateListener = ::onStateChanged
            onStateChanged(b.service.state)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved port and populate the field
        val savedPort = PortPreferences.load(this)
        binding.etPort.setText(savedPort.toString())

        val mpd = MpdPreferences.load(this)
        binding.etMpdHost.setText(
            if (mpd.port == MpdPreferences.DEFAULT_PORT) mpd.host
            else "${mpd.host}:${mpd.port}"
        )
        binding.etMpdOutput.setText(mpd.outputName)

        val bufferMillis = BufferPreferences.load(this)
        binding.sbBuffer.max = AudioBuffer.MAX_MILLIS
        binding.sbBuffer.progress = bufferMillis
        updateBufferLabel(bufferMillis)
        binding.sbBuffer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val millis = AudioBuffer.clampMillis(progress)
                BufferPreferences.save(this@MainActivity, millis)
                updateBufferLabel(millis)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // Save port when user confirms via keyboard "Done" action
        binding.etPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                savePort()
                true
            } else {
                false
            }
        }

        binding.btnToggle.setOnClickListener { toggleServer() }

        // Keep the server alive with the screen off; ask once on first launch.
        if (!isIgnoringBatteryOptimizations(this)) {
            requestIgnoreBatteryOptimizations(this)
        }

        // Bind to service if already running
        bindService(
            Intent(this, PulseAudioService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onPause() {
        saveMpdSettings()
        super.onPause()
    }

    override fun onDestroy() {
        if (bound) {
            service?.stateListener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ─── Port helpers ─────────────────────────────────────────────────────────

    private fun savePort() {
        val port = resolvePort()
        PortPreferences.save(this, port)
        binding.etPort.setText(port.toString())
    }

    private fun resolvePort(): Int {
        val text = binding.etPort.text?.toString()?.trim() ?: ""
        val parsed = text.toIntOrNull()
        return if (parsed != null && parsed in 1..65535) parsed else PAConst.DEFAULT_PORT
    }

    private fun updateBufferLabel(millis: Int) {
        binding.tvBufferValue.text = "$millis ms"
    }

    private fun saveMpdSettings() {
        MpdPreferences.save(
            this,
            MpdPreferences.configFrom(
                binding.etMpdHost.text?.toString().orEmpty(),
                binding.etMpdOutput.text?.toString().orEmpty(),
            )
        )
    }

    // ─── Server control ───────────────────────────────────────────────────────

    private fun toggleServer() {
        val svc = service
        if (svc == null) return

        when (svc.state) {
            is ServerState.Idle, is ServerState.Error -> {
                if (!hasRequiredPermissions(this)) {
                    ActivityCompat.requestPermissions(
                        this, requiredPermissions().toTypedArray(), REQUEST_PERMISSIONS
                    )
                    return
                }
                savePort()
                saveMpdSettings()
                val port = resolvePort()
                val intent = Intent(this, PulseAudioService::class.java)
                    .setAction(PulseAudioService.ACTION_START)
                    .putExtra(PulseAudioService.EXTRA_PORT, port)
                ContextCompat.startForegroundService(this, intent)
            }
            else -> {
                svc.stopServer()
            }
        }
    }

    private fun onStateChanged(state: ServerState) {
        runOnUiThread {
            val isRunning = state is ServerState.Running ||
                state is ServerState.Starting ||
                state is ServerState.ClientConnected
            // Disable port field while server is active
            binding.etPort.isEnabled = !isRunning
            binding.etMpdHost.isEnabled = !isRunning
            binding.etMpdOutput.isEnabled = !isRunning
            binding.sbBuffer.isEnabled = !isRunning

            when (state) {
                is ServerState.Idle -> {
                    binding.btnToggle.text = "Start Server"
                    binding.tvStatus.text = "Server stopped"
                    binding.tvAddress.text = "—"
                }
                is ServerState.Starting -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Starting…"
                    binding.tvAddress.text = "…"
                }
                is ServerState.Running -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Listening — waiting for client"
                    binding.tvAddress.text = "${state.ip}:${state.port}"
                }
                is ServerState.ClientConnected -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Client connected: ${state.ip}"
                }
                is ServerState.Error -> {
                    binding.btnToggle.text = "Start Server"
                    binding.tvStatus.text = "Error: ${state.message}"
                    binding.tvAddress.text = "—"
                }
            }
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            toggleServer()
        } else {
            binding.tvStatus.text = "Notification permission required"
        }
    }
}
