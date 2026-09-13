package com.privateai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class TorEngineMode {
    INBUILT, // Embedded native Tor daemon (libtor.so)
    ORBOT    // External Orbot app (port 9050 or custom)
}

enum class TorState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

object TorManager {
    private const val TAG = "TorManager"

    @Volatile var currentMode: TorEngineMode = TorEngineMode.INBUILT
    @Volatile var currentState: TorState = TorState.STOPPED
    @Volatile var currentStatusText: String = "Tor is stopped"
    @Volatile var lastBootstrappedPort: Int = 9050

    private val listeners = CopyOnWriteArrayList<(TorState, String, Int) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private var isReceiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return

            if (action == TorService.ACTION_STATUS) {
                val status = intent.getStringExtra(TorService.EXTRA_STATUS) ?: return
                Log.d(TAG, "TorService status received: $status")

                when (status) {
                    TorService.STATUS_ON -> {
                        currentState = TorState.RUNNING
                        val port = TorService.socksPort
                        if (port > 0) lastBootstrappedPort = port
                        currentStatusText = "🟢 Native Tor Connected (SOCKS port: $lastBootstrappedPort)"
                        notifyListeners()
                    }
                    TorService.STATUS_STARTING -> {
                        currentState = TorState.STARTING
                        currentStatusText = "🟡 Starting Native Tor & building circuit..."
                        notifyListeners()
                    }
                    TorService.STATUS_STOPPING -> {
                        currentState = TorState.STARTING
                        currentStatusText = "🟡 Stopping Native Tor..."
                        notifyListeners()
                    }
                    TorService.STATUS_OFF -> {
                        currentState = TorState.STOPPED
                        currentStatusText = "⚪ Native Tor Stopped"
                        notifyListeners()
                    }
                    else -> {
                        currentStatusText = "Status: $status"
                        notifyListeners()
                    }
                }
            } else if (action == TorService.ACTION_ERROR) {
                val errorMsg = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor daemon error occurred"
                Log.e(TAG, "TorService error received: $errorMsg")
                currentState = TorState.ERROR
                currentStatusText = "🔴 Tor Error: $errorMsg"
                notifyListeners()
            }
        }
    }

    fun addListener(listener: (TorState, String, Int) -> Unit) {
        listeners.add(listener)
        listener(currentState, currentStatusText, getEffectiveSocksPort())
    }

    fun removeListener(listener: (TorState, String, Int) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val port = getEffectiveSocksPort()
        listeners.forEach { it(currentState, currentStatusText, port) }
    }

    fun getEffectiveSocksPort(): Int {
        if (currentMode == TorEngineMode.INBUILT) {
            val p = TorService.socksPort
            return if (p > 0) p else lastBootstrappedPort
        }
        return 9050
    }

    fun register(context: Context) {
        if (isReceiverRegistered) return
        try {
            TorService.setBroadcastPackageName(context.packageName)
            val filter = IntentFilter().apply {
                addAction(TorService.ACTION_STATUS)
                addAction(TorService.ACTION_ERROR)
            }

            // Register with LocalBroadcastManager
            LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, filter)

            // Also register with system BroadcastReceiver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(statusReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register broadcast receiver: ${e.message}")
        }
    }

    fun startInbuiltTor(context: Context, bridgeLines: String? = null) {
        currentMode = TorEngineMode.INBUILT
        register(context)

        executor.execute {
            try {
                currentState = TorState.STARTING
                currentStatusText = "🟡 Bootstrapping embedded native Tor..."
                notifyListeners()

                // Setup torrc if bridges or custom configs are provided
                configureTorrc(context, bridgeLines)

                val startIntent = Intent(context, TorService::class.java).apply {
                    action = TorService.ACTION_START
                }
                context.startService(startIntent)
                Log.i(TAG, "Started native TorService via startService()")

                // Also run a background check to detect when SOCKS port opens up
                pollSocksReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start native Tor: ${e.message}", e)
                currentState = TorState.ERROR
                currentStatusText = "🔴 Native Tor start failed: ${e.localizedMessage}"
                notifyListeners()
            }
        }
    }

    fun stopInbuiltTor(context: Context) {
        executor.execute {
            try {
                val stopIntent = Intent(context, TorService::class.java)
                context.stopService(stopIntent)
                currentState = TorState.STOPPED
                currentStatusText = "⚪ Native Tor Stopped"
                notifyListeners()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native Tor: ${e.message}")
            }
        }
    }

    fun restartInbuiltTor(context: Context, bridgeLines: String? = null) {
        stopInbuiltTor(context)
        executor.execute {
            Thread.sleep(1000)
            startInbuiltTor(context, bridgeLines)
        }
    }

    fun requestNewIdentity(context: Context) {
        executor.execute {
            try {
                Log.i(TAG, "Requesting new Tor identity - restarting Tor daemon for clean circuit rebuild")
                restartInbuiltTor(context)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cycle Tor identity: ${e.message}")
            }
        }
    }

    private fun configureTorrc(context: Context, bridgeLines: String?) {
        try {
            val torrcFile: File = TorService.getTorrc(context)
            torrcFile.parentFile?.mkdirs()

            val sb = StringBuilder()
            sb.append("# Onion AI embedded Tor configuration\n")
            sb.append("AvoidDiskWrites 1\n")
            sb.append("DisableDebuggerAttachment 0\n")

            if (!bridgeLines.isNullOrBlank()) {
                sb.append("UseBridges 1\n")
                bridgeLines.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        if (trimmed.startsWith("Bridge ", ignoreCase = true)) {
                            sb.append("$trimmed\n")
                        } else {
                            sb.append("Bridge $trimmed\n")
                        }
                    }
                }
            }

            FileOutputStream(torrcFile).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "Written torrc configuration (${torrcFile.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write custom torrc: ${e.message}")
        }
    }

    private fun pollSocksReady() {
        var attempts = 0
        while (attempts < 30 && currentState != TorState.RUNNING && currentState != TorState.STOPPED) {
            Thread.sleep(1000)
            val testPort = getEffectiveSocksPort()
            if (isPortListening("127.0.0.1", testPort)) {
                Log.i(TAG, "SOCKS port $testPort is verified listening!")
                currentState = TorState.RUNNING
                currentStatusText = "🟢 Native Tor Connected (SOCKS port: $testPort)"
                notifyListeners()
                break
            }
            attempts++
        }
    }

    fun isPortListening(host: String, port: Int, timeoutMs: Int = 800): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
