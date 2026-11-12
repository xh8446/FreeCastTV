package com.freecast.tv.upnp

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 轻量 HTTP 服务，提供设备描述、SCPD 以及 SOAP 控制入口，
 * 同时兼容 GENA 的 SUBSCRIBE / UNSUBSCRIBE 方法。
 */
class UpnpHttpServer(
    private val requestedPort: Int,
    private val soapHandler: SoapHandler
) {

    companion object {
        private const val TAG = "UpnpHttpServer"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    var listeningPort: Int = requestedPort
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(requestedPort))
            serverSocket = socket
            listeningPort = socket.localPort
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "upnp-http").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "HTTP server listening on $listeningPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server failed on port $requestedPort", e)
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 10000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val headerBytes = readHeaderBlock(input) ?: return
                val headerText = String(headerBytes, Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) return

                val requestLine = lines[0].split(" ")
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase(Locale.US)
                val path = requestLine[1].substringBefore('?')

                val headers = HashMap<String, String>()
                for (index in 1 until lines.size) {
                    val colon = lines[index].indexOf(':')
                    if (colon > 0) {
                        headers[lines[index].substring(0, colon).trim().lowercase(Locale.US)] =
                            lines[index].substring(colon + 1).trim()
                    }
                }

                if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }

                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(bodyBytes, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                val body = String(bodyBytes, 0, read, Charsets.UTF_8)

                val remoteIp = socket.inetAddress?.hostAddress
                route(method, path, headers, body, output, remoteIp)
            } catch (e: Exception) {
                Log.w(TAG, "handle client failed", e)
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream,
        remoteIp: String?
    ) {
        when {
            method == "GET" && (path == "/desc.xml" || path == "/") ->
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", DeviceDescription.build())

            method == "GET" && path == "/scpd/AVTransport.xml" ->
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", Scpd.AV_TRANSPORT)

            method == "GET" && path == "/scpd/RenderingControl.xml" ->
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", Scpd.RENDERING_CONTROL)

            method == "GET" && path == "/scpd/ConnectionManager.xml" ->
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", Scpd.CONNECTION_MANAGER)

            method == "POST" && path.startsWith("/control/") -> {
                val service = path.removePrefix("/control/")
                val soapAction = (headers["soapaction"] ?: "").trim('"', ' ')
                val result = soapHandler.handle(service, soapAction, body, remoteIp)
                writeResponse(
                    output,
                    if (result.fault) "500 Internal Server Error" else "200 OK",
                    "text/xml; charset=utf-8",
                    result.body
                )
            }

            method == "SUBSCRIBE" -> {
                val extra = LinkedHashMap<String, String>()
                extra["SID"] = headers["sid"] ?: "uuid:${UUID.randomUUID()}"
                extra["TIMEOUT"] = "Second-1800"
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", "", extra)
            }

            method == "UNSUBSCRIBE" ->
                writeResponse(output, "200 OK", "text/xml; charset=utf-8", "")

            else ->
                writeResponse(output, "404 Not Found", "text/plain; charset=utf-8", "not found")
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = StringBuilder()
        header.append("HTTP/1.1 ").append(status).append("\r\n")
        header.append("Content-Type: ").append(contentType).append("\r\n")
        header.append("Content-Length: ").append(bytes.size).append("\r\n")
        header.append("Connection: close\r\n")
        header.append("Server: Android UPnP/1.0 FreeCast/1.0\r\n")
        for ((key, value) in extraHeaders) {
            header.append(key).append(": ").append(value).append("\r\n")
        }
        header.append("\r\n")

        output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() > 0) out.toByteArray() else null
            }
            out.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4) return out.toByteArray()
            if (out.size() > 65536) return out.toByteArray()
        }
    }
}
