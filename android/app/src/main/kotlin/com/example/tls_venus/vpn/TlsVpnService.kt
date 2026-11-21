package com.example.tls_venus.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tls_venus.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TlsVpnService : VpnService() {

    companion object {
        const val TAG = "TlsVpnService"
        const val ACTION_START = "com.example.tls_venus.ACTION_START"
        const val ACTION_STOP = "com.example.tls_venus.ACTION_STOP"
        const val EXTRA_CONFIG = "extra_config"

        private const val NOTIFICATION_CHANNEL_ID = "tls_venus_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // Singleton state for UI observation
        val isRunning = AtomicBoolean(false)
        var connectionState: String = "DISCONNECTED"
            set(value) {
                field = value
                stateListener?.invoke(value)
            }

        var stateListener: ((String) -> Unit)? = null
        var statsListener: ((Map<String, Any>) -> Unit)? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: TlsTunnelProxyServer? = null
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var startTimeMs: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                val config = VpnConfig.fromJson(configJson)
                startVpn(config)
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpn(config: VpnConfig) {
        if (isRunning.get()) {
            Log.w(TAG, "VPN already running")
            return
        }

        connectionState = "CONNECTING"
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to ${config.serverHost}..."))

        serviceScope.launch {
            try {
                // 1. Establish TUN interface
                val builder = Builder()
                    .setSession("TLS Venus")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)

                val dns = if (config.dnsServer.isNotBlank()) config.dnsServer else "8.8.8.8"
                try {
                    builder.addDnsServer(dns)
                } catch (e: Exception) {
                    builder.addDnsServer("8.8.8.8")
                }

                // Disallow VPN loopback for this app itself
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disallow app: ${e.message}")
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    connectionState = "DISCONNECTED"
                    stopSelf()
                    return@launch
                }
                vpnInterface = pfd

                // 2. Start local TLS SOCKS5 proxy
                val socksPort = 10808
                val proxy = TlsTunnelProxyServer(this@TlsVpnService, config, socksPort)
                proxy.start()
                proxyServer = proxy

                // 3. Start tun2socks engine
                val key = engine.Key()
                key.device = "fd://${pfd.fd}"
                key.proxy = "socks5://127.0.0.1:$socksPort"
                key.logLevel = "warning"
                engine.Engine.insert(key)
                engine.Engine.start()

                isRunning.set(true)
                startTimeMs = System.currentTimeMillis()
                connectionState = "CONNECTED"

                // Update notification
                updateNotification("Connected to ${config.serverHost}:${config.serverPort}")

                // 4. Start live statistics reporting loop
                startStatsLoop()

                Log.i(TAG, "TLS VPN tunnel established successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN: ${e.message}", e)
                connectionState = "DISCONNECTED"
                stopVpn()
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastUp = 0L
            var lastDown = 0L
            while (isActive && isRunning.get()) {
                delay(1000)
                val proxy = proxyServer ?: continue

                val currentUp = proxy.totalBytesUp.get()
                val currentDown = proxy.totalBytesDown.get()

                val speedUp = (currentUp - lastUp).coerceAtLeast(0)
                val speedDown = (currentDown - lastDown).coerceAtLeast(0)

                lastUp = currentUp
                lastDown = currentDown

                val durationSec = (System.currentTimeMillis() - startTimeMs) / 1000

                val stats = mapOf(
                    "state" to "CONNECTED",
                    "speedUp" to speedUp,
                    "speedDown" to speedDown,
                    "totalUp" to currentUp,
                    "totalDown" to currentDown,
                    "duration" to durationSec
                )

                statsListener?.invoke(stats)
            }
        }
    }

    private fun stopVpn() {
        if (!isRunning.compareAndSet(true, false) && connectionState != "CONNECTING") {
            return
        }

        connectionState = "DISCONNECTING"
        statsJob?.cancel()
        statsJob = null

        serviceScope.launch {
            try {
                // Stop tun2socks
                try {
                    engine.Engine.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping tun2socks: ${e.message}")
                }

                // Stop TLS proxy
                proxyServer?.stop()
                proxyServer = null

                // Close TUN descriptor
                try {
                    vpnInterface?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing VPN fd: ${e.message}")
                }
                vpnInterface = null

            } finally {
                connectionState = "DISCONNECTED"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "TLS VPN tunnel stopped")
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TLS Venus VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for TLS VPN tunnel"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, TlsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TLS Venus VPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
