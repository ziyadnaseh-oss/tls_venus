package com.example.tls_venus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.tls_venus.vpn.TlsVpnService
import com.example.tls_venus.vpn.VpnConfig
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "com.example.tls_venus/vpn"
        private const val EVENT_CHANNEL = "com.example.tls_venus/vpn_events"
        private const val PREFS_NAME = "tls_venus_vpn_prefs"
        private const val KEY_CONFIG = "saved_vpn_config"
        private const val REQUEST_VPN_PERMISSION = 101
    }

    private var pendingVpnResult: MethodChannel.Result? = null
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Method Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "prepareVpn" -> {
                    val intent = VpnService.prepare(this)
                    if (intent != null) {
                        pendingVpnResult = result
                        startActivityForResult(intent, REQUEST_VPN_PERMISSION)
                    } else {
                        result.success(true)
                    }
                }
                "startVpn" -> {
                    val args = call.arguments as? Map<*, *>
                    val config = VpnConfig(
                        serverHost = args?.get("serverHost") as? String ?: "",
                        serverPort = (args?.get("serverPort") as? Number)?.toInt() ?: 443,
                        sni = args?.get("sni") as? String ?: "",
                        customPayload = args?.get("customPayload") as? String ?: "",
                        dnsServer = args?.get("dnsServer") as? String ?: "8.8.8.8"
                    )

                    val intent = Intent(this, TlsVpnService::class.java).apply {
                        action = TlsVpnService.ACTION_START
                        putExtra(TlsVpnService.EXTRA_CONFIG, config.toJson())
                    }
                    ContextCompat.startForegroundService(this, intent)
                    result.success(true)
                }
                "stopVpn" -> {
                    val intent = Intent(this, TlsVpnService::class.java).apply {
                        action = TlsVpnService.ACTION_STOP
                    }
                    startService(intent)
                    result.success(true)
                }
                "getStatus" -> {
                    result.success(TlsVpnService.connectionState)
                }
                "saveConfig" -> {
                    val configJson = call.arguments as? String ?: ""
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_CONFIG, configJson).apply()
                    result.success(true)
                }
                "loadConfig" -> {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val saved = prefs.getString(KEY_CONFIG, null)
                    result.success(saved)
                }
                else -> result.notImplemented()
            }
        }

        // Event Channel for real-time stats and state changes
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events

                    TlsVpnService.stateListener = { state ->
                        runOnUiThread {
                            eventSink?.success(mapOf("type" to "state", "state" to state))
                        }
                    }

                    TlsVpnService.statsListener = { stats ->
                        runOnUiThread {
                            val map = HashMap<String, Any>(stats)
                            map["type"] = "stats"
                            eventSink?.success(map)
                        }
                    }

                    // Emit initial state
                    eventSink?.success(
                        mapOf(
                            "type" to "state",
                            "state" to TlsVpnService.connectionState
                        )
                    )
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                    TlsVpnService.stateListener = null
                    TlsVpnService.statsListener = null
                }
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            val granted = (resultCode == Activity.RESULT_OK)
            pendingVpnResult?.success(granted)
            pendingVpnResult = null
        }
    }
}
