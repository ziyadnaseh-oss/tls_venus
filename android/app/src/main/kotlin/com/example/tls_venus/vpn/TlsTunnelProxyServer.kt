package com.example.tls_venus.vpn

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TlsTunnelProxyServer(
    private val vpnService: VpnService,
    private val config: VpnConfig,
    val listenPort: Int = 10808
) {
    companion object {
        private const val TAG = "TlsTunnelProxy"
        private const val BUFFER_SIZE = 16384
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val SOCKET_TIMEOUT_MS = 60000
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val totalBytesUp = AtomicLong(0)
    val totalBytesDown = AtomicLong(0)

    private val sslContext: SSLContext by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAllCerts, SecureRandom())
        ctx
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        executor = Executors.newCachedThreadPool()
        serverSocket = ServerSocket(listenPort, 128, InetAddress.getByName("127.0.0.1")).apply {
            reuseAddress = true
        }

        Log.i(TAG, "TLS SOCKS5 Proxy listening on 127.0.0.1:$listenPort")

        executor?.execute {
            while (isRunning.get()) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    activeSockets.add(clientSocket)
                    executor?.execute {
                        try {
                            handleClient(clientSocket)
                        } catch (e: Exception) {
                            Log.d(TAG, "Client handle exception: ${e.message}")
                        } finally {
                            activeSockets.remove(clientSocket)
                            closeQuietly(clientSocket)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping TLS SOCKS5 Proxy...")

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serverSocket: ${e.message}")
        }
        serverSocket = null

        activeSockets.forEach { closeQuietly(it) }
        activeSockets.clear()

        executor?.shutdownNow()
        executor = null
    }

    private fun handleClient(clientSocket: Socket) {
        clientSocket.soTimeout = SOCKET_TIMEOUT_MS
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()

        // 1. SOCKS5 Greeting Handshake
        val ver = clientIn.read()
        if (ver != 0x05) {
            return
        }
        val nmethods = clientIn.read()
        val methods = ByteArray(nmethods)
        readFully(clientIn, methods)

        // Reply: Version 5, No authentication (0x00)
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        // 2. SOCKS5 Request
        val reqVer = clientIn.read()
        if (reqVer != 0x05) return
        val cmd = clientIn.read()
        clientIn.read() // RSV (reserved)
        val atyp = clientIn.read()

        val destHost: String
        when (atyp) {
            0x01 -> { // IPv4
                val addrBytes = ByteArray(4)
                readFully(clientIn, addrBytes)
                destHost = InetAddress.getByAddress(addrBytes).hostAddress ?: "0.0.0.0"
            }
            0x03 -> { // Domain name
                val len = clientIn.read()
                val domainBytes = ByteArray(len)
                readFully(clientIn, domainBytes)
                destHost = String(domainBytes, Charsets.US_ASCII)
            }
            0x04 -> { // IPv6
                val addrBytes = ByteArray(16)
                readFully(clientIn, addrBytes)
                destHost = InetAddress.getByAddress(addrBytes).hostAddress ?: "::"
            }
            else -> return
        }

        val portBytes = ByteArray(2)
        readFully(clientIn, portBytes)
        val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        if (cmd == 0x01) { // CONNECT
            handleConnect(clientSocket, clientIn, clientOut, destHost, destPort)
        } else {
            // Command not supported
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
        }
    }

    private fun handleConnect(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        destHost: String,
        destPort: Int
    ) {
        var remoteRawSocket: Socket? = null
        var remoteSslSocket: SSLSocket? = null

        try {
            // Create raw TCP connection to the VPN server
            remoteRawSocket = Socket()
            // CRITICAL: Protect socket from being routed through the VPN TUN
            if (!vpnService.protect(remoteRawSocket)) {
                Log.w(TAG, "Failed to protect socket from VPN routing!")
            }

            remoteRawSocket.tcpNoDelay = true
            remoteRawSocket.soTimeout = SOCKET_TIMEOUT_MS
            remoteRawSocket.connect(
                InetSocketAddress(config.serverHost, config.serverPort),
                CONNECT_TIMEOUT_MS
            )

            // Upgrade connection to TLS with SNI
            val sniHost = if (config.sni.isNotBlank()) config.sni.trim() else config.serverHost.trim()
            val sslSocket = sslContext.socketFactory.createSocket(
                remoteRawSocket,
                config.serverHost,
                config.serverPort,
                true
            ) as SSLSocket

            val sslParams = sslSocket.sslParameters
            if (sniHost.isNotEmpty()) {
                try {
                    sslParams.serverNames = listOf(SNIHostName(sniHost))
                } catch (e: Exception) {
                    Log.w(TAG, "Error setting SNIHostName: ${e.message}")
                }
            }
            sslParams.endpointIdentificationAlgorithm = null
            sslSocket.sslParameters = sslParams
            sslSocket.startHandshake()
            remoteSslSocket = sslSocket
            activeSockets.add(sslSocket)

            val remoteIn = sslSocket.inputStream
            val remoteOut = sslSocket.outputStream

            // Inject custom HTTP payload if configured
            if (config.customPayload.isNotBlank()) {
                val formattedPayload = formatPayload(
                    config.customPayload,
                    destHost,
                    destPort,
                    sniHost
                )
                val payloadBytes = formattedPayload.toByteArray(Charsets.UTF_8)
                remoteOut.write(payloadBytes)
                remoteOut.flush()
                totalBytesUp.addAndGet(payloadBytes.size.toLong())

                // If payload is HTTP CONNECT, read response headers
                if (formattedPayload.startsWith("CONNECT", ignoreCase = true)) {
                    readHttpResponse(remoteIn)
                }
            }

            // SOCKS5 reply: 0x05, 0x00 (Success), 0x00, 0x01 (IPv4), 0,0,0,0, 0,0
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            // Bi-directional pipe
            val t1 = Thread {
                pipe(clientIn, remoteOut, totalBytesUp)
                closeQuietly(sslSocket)
            }
            val t2 = Thread {
                pipe(remoteIn, clientOut, totalBytesDown)
                closeQuietly(clientSocket)
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join()

        } catch (e: Exception) {
            Log.d(TAG, "Tunnel connection error for $destHost:$destPort: ${e.message}")
            try {
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } catch (_: Exception) {}
        } finally {
            remoteSslSocket?.let { activeSockets.remove(it) }
            closeQuietly(remoteSslSocket)
            closeQuietly(remoteRawSocket)
        }
    }

    private fun formatPayload(
        template: String,
        destHost: String,
        destPort: Int,
        sniHost: String
    ): String {
        var payload = template
            .replace("[host_port]", "$destHost:$destPort", ignoreCase = true)
            .replace("[host]", destHost, ignoreCase = true)
            .replace("[port]", destPort.toString(), ignoreCase = true)
            .replace("[sni]", sniHost, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("[cr]", "\r", ignoreCase = true)
            .replace("[lf]", "\n", ignoreCase = true)
            .replace("[raw]", "", ignoreCase = true)

        // Ensure CRLF standard line endings
        payload = payload.replace("\r\n", "\n").replace("\n", "\r\n")
        if (!payload.endsWith("\r\n\r\n")) {
            payload += "\r\n\r\n"
        }
        return payload
    }

    private fun readHttpResponse(input: InputStream) {
        val buffer = ByteArray(4096)
        val response = StringBuilder()
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            totalBytesDown.addAndGet(count.toLong())
            val chunk = String(buffer, 0, count, Charsets.US_ASCII)
            response.append(chunk)
            if (response.contains("\r\n\r\n")) {
                break
            }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (isRunning.get()) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
                counter.addAndGet(bytesRead.toLong())
            }
        } catch (_: Exception) {
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw java.io.EOFException("Premature EOF while reading SOCKS request")
            offset += count
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }
}
