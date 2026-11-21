package com.example.tls_venus.vpn

import org.json.JSONObject

data class VpnConfig(
    val serverHost: String = "",
    val serverPort: Int = 443,
    val sni: String = "",
    val customPayload: String = "",
    val dnsServer: String = "8.8.8.8"
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("serverHost", serverHost)
        obj.put("serverPort", serverPort)
        obj.put("sni", sni)
        obj.put("customPayload", customPayload)
        obj.put("dnsServer", dnsServer)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): VpnConfig {
            if (jsonStr.isNullOrBlank()) return VpnConfig()
            return try {
                val obj = JSONObject(jsonStr)
                VpnConfig(
                    serverHost = obj.optString("serverHost", ""),
                    serverPort = obj.optInt("serverPort", 443),
                    sni = obj.optString("sni", ""),
                    customPayload = obj.optString("customPayload", ""),
                    dnsServer = obj.optString("dnsServer", "8.8.8.8")
                )
            } catch (e: Exception) {
                VpnConfig()
            }
        }
    }
}
