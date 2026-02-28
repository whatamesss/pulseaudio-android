package us.foxtek.pulseserver.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import us.foxtek.pulseserver.databinding.ActivityMainBinding
import us.foxtek.pulseserver.proto.PAConst
import us.foxtek.pulseserver.service.PulseAudioService
import us.foxtek.pulseserver.service.ServerState

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
        binding.btnCopyIp.setOnClickListener { copyConnectionInfo() }

        // Bind to service if already running
        bindService(
            Intent(this, PulseAudioService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
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

    // ─── Server control ───────────────────────────────────────────────────────

    private fun toggleServer() {
        val svc = service
        if (svc == null) return

        when (svc.state) {
            is ServerState.Idle, is ServerState.Error -> {
                if (!checkPermissions()) {
                    requestPermissions()
                    return
                }
                savePort()
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

            when (state) {
                is ServerState.Idle -> {
                    binding.btnToggle.text = "Start Server"
                    binding.tvStatus.text = "Server stopped"
                    binding.tvAddress.text = "—"
                    binding.tvHint.text = ""
                    binding.btnCopyIp.isEnabled = false
                }
                is ServerState.Starting -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Starting…"
                    binding.tvAddress.text = "…"
                    binding.btnCopyIp.isEnabled = false
                }
                is ServerState.Running -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Listening — waiting for client"
                    binding.tvAddress.text = "${state.ip}:${state.port}"
                    binding.tvHint.text = buildHint(state.ip, state.port)
                    binding.btnCopyIp.isEnabled = true
                }
                is ServerState.ClientConnected -> {
                    binding.btnToggle.text = "Stop Server"
                    binding.tvStatus.text = "Client connected: ${state.ip}"
                    binding.btnCopyIp.isEnabled = true
                }
                is ServerState.Error -> {
                    binding.btnToggle.text = "Start Server"
                    binding.tvStatus.text = "Error: ${state.message}"
                    binding.tvAddress.text = "—"
                    binding.btnCopyIp.isEnabled = false
                }
            }
        }
    }

    private fun buildHint(ip: String, port: Int): String = """
        On your Linux PC, add to /etc/pulse/default.pa:
        
        load-module module-tunnel-sink   server=tcp:$ip:$port
        load-module module-tunnel-source server=tcp:$ip:$port
        
        Or connect with pactl:
        PULSE_SERVER=tcp:$ip:$port paplay /path/to/file.wav
    """.trimIndent()

    private fun copyConnectionInfo() {
        val s = service ?: return
        val text = when (val st = s.state) {
            is ServerState.Running -> "${st.ip}:${st.port}"
            is ServerState.ClientConnected -> "${st.ip}:${PortPreferences.load(this)}"
            else -> return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PA address", text))
        binding.tvHint.text = "Copied: $text\n\n${binding.tvHint.text}"
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, required.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            toggleServer()
        } else {
            binding.tvStatus.text = "Microphone permission required"
        }
    }
}
