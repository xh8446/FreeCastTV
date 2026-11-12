package com.freecast.tv.upnp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.freecast.tv.DeviceInfo
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SSDP 发现服务：在所有可用网卡上监听 M-SEARCH 并周期发送 NOTIFY alive。
 *
 * 车机、手机热点等场景经常有多块网卡（Wi-Fi / 有线 / 热点），只在其中一块上
 * 加入多播组会导致控制点搜不到设备。这里对每块网卡分别建 socket、分别加入
 * 239.255.255.250，并用该网卡的地址生成 LOCATION，同时向子网广播地址补发
 * NOTIFY，兼容屏蔽多播但放行广播的路由器与车机。
 */
class SsdpServer(
    private val context: Context,
    private val uuid: String,
    private val locationProvider: () -> String
) {

    companion object {
        private const val TAG = "SsdpServer"
        private const val GROUP_HOST = "239.255.255.250"
        private const val PORT = 1900
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val MAX_AGE = 1800
        private const val NOTIFY_INTERVAL_MS = 3 * 60 * 1000L
    }

    private class Candidate(
        val nif: NetworkInterface,
        val ip: String,
        val broadcast: InetAddress?
    )

    private class Endpoint(
        val nif: NetworkInterface?,
        val ip: String,
        val broadcast: InetAddress?,
        val socket: MulticastSocket
    ) {
        fun location(): String = "http://$ip:${DeviceInfo.httpPort}/desc.xml"
    }

    private val services = listOf(
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
    )

    private val udn = "uuid:$uuid"
    private val sendLock = Any()

    @Volatile
    private var running = false

    private val endpoints = ArrayList<Endpoint>()
    private val listeners = ArrayList<Thread>()
    private var notifyThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true

        acquireMulticastLock()

        val group = InetAddress.getByName(GROUP_HOST)
        val candidates = collectCandidates()
        Log.i(TAG, "candidates: " + candidates.joinToString { "${it.nif.name}/${it.ip}" })

        for (candidate in candidates) {
            try {
                val endpoint = openEndpoint(candidate, group)
                endpoints.add(endpoint)
                startListener(endpoint)
                Log.i(TAG, "listen ${candidate.nif.name} ${candidate.ip} -> ${endpoint.location()}")
            } catch (e: Exception) {
                Log.w(TAG, "bind failed on ${candidate.ip}", e)
            }
        }

        if (endpoints.isEmpty()) {
            try {
                val socket = MulticastSocket(PORT)
                socket.timeToLive = 4
                try {
                    socket.broadcast = true
                } catch (_: Exception) {
                }
                socket.joinGroup(group)
                val endpoint = Endpoint(null, DeviceInfo.ip, null, socket)
                endpoints.add(endpoint)
                startListener(endpoint)
                Log.i(TAG, "fallback listen on ${DeviceInfo.ip}")
            } catch (e: Exception) {
                Log.e(TAG, "fallback listen failed", e)
            }
        }

        DeviceInfo.ssdpInterfaces = endpoints.size

        notifyThread = Thread({ notifyLoop() }, "ssdp-notify").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "started on ${endpoints.size} interface(s), udn=$udn, ${locationProvider()}")
    }

    fun stop() {
        if (!running) return
        running = false

        try {
            sendNotify("ssdp:byebye")
        } catch (_: Exception) {
        }

        val group = try {
            InetAddress.getByName(GROUP_HOST)
        } catch (_: Exception) {
            null
        }
        for (endpoint in endpoints.toList()) {
            if (group != null) {
                try {
                    if (endpoint.nif != null) {
                        endpoint.socket.leaveGroup(InetSocketAddress(group, PORT), endpoint.nif)
                    } else {
                        endpoint.socket.leaveGroup(group)
                    }
                } catch (_: Exception) {
                }
            }
            try {
                endpoint.socket.close()
            } catch (_: Exception) {
            }
        }
        endpoints.clear()
        listeners.forEach { it.interrupt() }
        listeners.clear()
        notifyThread?.interrupt()
        notifyThread = null

        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        DeviceInfo.ssdpInterfaces = 0
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("freecast-ssdp")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed", e)
        }
    }

    private fun collectCandidates(): List<Candidate> {
        val result = ArrayList<Candidate>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp || nif.isLoopback || nif.isPointToPoint) continue
                    for (address in nif.interfaceAddresses) {
                        val addr = address.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip == "0.0.0.0") continue
                            result.add(Candidate(nif, ip, address.broadcast))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "enumerate interfaces failed", e)
        }
        return result.distinctBy { it.ip }.sortedByDescending { score(it.ip) }
    }

    private fun score(ip: String): Int = when {
        ip.startsWith("192.168.") -> 3
        ip.startsWith("10.") -> 2
        ip.startsWith("172.") -> 2
        else -> 1
    }

    private fun openEndpoint(candidate: Candidate, group: InetAddress): Endpoint {
        val socket = MulticastSocket(null as SocketAddress?)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        socket.networkInterface = candidate.nif
        socket.timeToLive = 4
        try {
            socket.broadcast = true
        } catch (_: Exception) {
        }
        socket.joinGroup(InetSocketAddress(group, PORT), candidate.nif)
        return Endpoint(candidate.nif, candidate.ip, candidate.broadcast, socket)
    }

    private fun startListener(endpoint: Endpoint) {
        val thread = Thread({ listenLoop(endpoint) }, "ssdp-listen-${endpoint.ip}")
        thread.isDaemon = true
        thread.start()
        listeners.add(thread)
    }

    private fun listenLoop(endpoint: Endpoint) {
        val buffer = ByteArray(4096)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                endpoint.socket.receive(packet)
            } catch (e: Exception) {
                if (!running) break
                continue
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            if (text.startsWith("M-SEARCH", ignoreCase = true)) {
                try {
                    handleSearch(text, packet.address, packet.port, endpoint)
                } catch (e: Exception) {
                    Log.w(TAG, "handle search failed", e)
                }
            }
        }
    }

    private fun notifyLoop() {
        try {
            Thread.sleep(200)
            // 启动阶段连发 8 次，间隔 300ms，大幅缩短控制点的发现等待时间
            // 部分路由器/手机对新上线设备的多播处理有延迟
            var burst = 0
            while (running && burst < 8) {
                sendNotify("ssdp:alive")
                Thread.sleep(300)
                burst++
            }
            while (running) {
                Thread.sleep(NOTIFY_INTERVAL_MS)
                if (running) sendNotify("ssdp:alive")
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) Log.w(TAG, "notify loop failed", e)
        }
    }

    private fun handleSearch(message: String, address: InetAddress, port: Int, endpoint: Endpoint) {
        val headers = parseHeaders(message)
        val man = headers["man"] ?: ""
        if (!man.contains("ssdp:discover", ignoreCase = true)) return
        val st = headers["st"]?.trim() ?: return

        val targets = ArrayList<String>()
        when {
            st == "ssdp:all" -> {
                targets.add("upnp:rootdevice")
                targets.add(udn)
                targets.add(DEVICE_TYPE)
                targets.addAll(services)
            }

            st == "upnp:rootdevice" -> targets.add("upnp:rootdevice")
            st == udn -> targets.add(udn)
            // 兼容各种 MediaRenderer 变体
            st.startsWith("urn:schemas-upnp-org:device:MediaRenderer") -> targets.add(DEVICE_TYPE)
            st.equals("urn:schemas-upnp-org:device:MediaRenderer", ignoreCase = true) -> targets.add(DEVICE_TYPE)
            st == "MediaRenderer" || st.equals("urn:schemas-upnp-org:service:AVTransport", ignoreCase = true) -> {
                // 部分 App 省略前缀或只发服务名
                targets.add(DEVICE_TYPE)
                targets.add(services[0])
                targets.add(services[1])
                targets.add(services[2])
            }
            services.any { it.equals(st, ignoreCase = true) } -> targets.add(st)
            st.startsWith("urn:schemas-upnp-org:service:AVTransport") -> targets.add(services[0])
            st.startsWith("urn:schemas-upnp-org:service:RenderingControl") -> targets.add(services[1])
            st.startsWith("urn:schemas-upnp-org:service:ConnectionManager") -> targets.add(services[2])
        }

        for (target in targets) {
            sendSearchResponse(target, address, port, endpoint)
        }
    }

    private fun sendSearchResponse(st: String, address: InetAddress, port: Int, endpoint: Endpoint) {
        val usn = when (st) {
            udn -> udn
            "upnp:rootdevice" -> "$udn::upnp:rootdevice"
            else -> "$udn::$st"
        }
        val message = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\n")
            append("DATE: ").append(httpDate()).append("\r\n")
            append("EXT:\r\n")
            append("LOCATION: ").append(endpoint.location()).append("\r\n")
            append("SERVER: Android UPnP/1.0 FreeCast/1.0\r\n")
            append("ST: ").append(st).append("\r\n")
            append("USN: ").append(usn).append("\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }
        sendBytes(endpoint, message.toByteArray(Charsets.UTF_8), address, port)
    }

    private fun sendNotify(nts: String) {
        val group = InetAddress.getByName(GROUP_HOST)
        val targets = ArrayList<Pair<String, String>>()
        targets.add("upnp:rootdevice" to "$udn::upnp:rootdevice")
        targets.add(udn to udn)
        targets.add(DEVICE_TYPE to "$udn::$DEVICE_TYPE")
        for (service in services) {
            targets.add(service to "$udn::$service")
        }

        for (endpoint in endpoints.toList()) {
            for ((nt, usn) in targets) {
                val data = buildNotify(nt, usn, nts, endpoint).toByteArray(Charsets.UTF_8)
                sendBytes(endpoint, data, group, PORT)
                // 部分路由器/车机屏蔽多播但放行广播，补发一份到子网广播地址
                val broadcast = endpoint.broadcast
                if (broadcast != null && nts == "ssdp:alive") {
                    sendBytes(endpoint, data, broadcast, PORT)
                }
            }
        }
    }

    private fun buildNotify(nt: String, usn: String, nts: String, endpoint: Endpoint): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP_HOST).append(":").append(PORT).append("\r\n")
        append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\n")
        if (nts == "ssdp:alive") {
            append("LOCATION: ").append(endpoint.location()).append("\r\n")
            append("SERVER: Android UPnP/1.0 FreeCast/1.0\r\n")
        }
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ").append(nts).append("\r\n")
        append("USN: ").append(usn).append("\r\n")
        append("\r\n")
    }

    private fun sendBytes(endpoint: Endpoint, data: ByteArray, address: InetAddress, port: Int) {
        synchronized(sendLock) {
            try {
                endpoint.socket.send(DatagramPacket(data, data.size, address, port))
            } catch (_: Exception) {
            }
        }
    }

    private fun parseHeaders(message: String): Map<String, String> {
        val result = HashMap<String, String>()
        val lines = message.split("\r\n", "\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            val index = line.indexOf(':')
            if (index > 0) {
                result[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }
        }
        return result
    }

    private fun httpDate(): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }
}
