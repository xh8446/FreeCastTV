package com.freecast.tv.upnp

import android.content.Context
import com.freecast.tv.Trace
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 投屏发现协议探针。
 *
 * 目的：部分手机 App（如网飞猫）搜不到本机，是因为它们不走标准 DLNA(SSDP)，
 * 而是用 mDNS(AirPlay) / 乐联(lelink) / 当贝cast 等私有协议发现设备。
 * 这里同时监听这些协议的常见端口，把收到的包特征写进诊断日志，
 * 便于定位对方到底在发什么协议（仅记录，不改变投屏行为）。
 */
class DiscoveryProbe(@Suppress("unused") private val context: Context) {

    private val running = AtomicBoolean(false)
    private val sockets = ArrayList<DatagramSocket>()
    private val threads = ArrayList<Thread>()
    private val lastLogAt = HashMap<String, Long>()

    /** 常见投屏发现端口：乐播/乐联、当贝、AirPlay、Chromecast、DIAL 等 */
    private val plainPorts = intArrayOf(
        9978,   // 乐播 / 乐联 lelink
        9999,   // 乐联备用
        1901,   // 部分国产投屏
        48000,  // 当贝cast
        56789,  // 私有
        8009,   // Chromecast
        5000,   // 部分投屏 App
        7000    // AirPlay
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            startMdns()
        } catch (t: Throwable) {
            Trace.log("PROBE", "mdns start failed: ${t.javaClass.simpleName}")
        }
        for (port in plainPorts) startPort(port)
        Trace.log("PROBE", "started, ports=${plainPorts.joinToString(",")} + mdns5353")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        for (s in sockets) {
            try { s.close() } catch (_: Throwable) {}
        }
        sockets.clear()
        threads.clear()
        Trace.log("PROBE", "stopped")
    }

    private fun startPort(port: Int) {
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(port))
            sockets.add(sock)
            spawn(sock, "udp$port")
        } catch (t: Throwable) {
            // 端口被当贝/乐播等占用了，记录一下（不影响 App 运行）
            Trace.log("PROBE", "bind $port failed: ${t.javaClass.simpleName}")
        }
    }

    private fun startMdns() {
        val sock = MulticastSocket(null as SocketAddress?)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(5353))
        val group = InetAddress.getByName("224.0.0.251")
        var joined = 0
        val nis = NetworkInterface.getNetworkInterfaces()
        while (nis != null && nis.hasMoreElements()) {
            val ni = nis.nextElement()
            try {
                if (!ni.isUp || ni.isLoopback) continue
                sock.joinGroup(InetSocketAddress(group, 5353), ni)
                joined++
            } catch (_: Throwable) {
            }
        }
        if (joined == 0) sock.joinGroup(group)
        sockets.add(sock)
        spawn(sock, "mdns")
    }

    private fun spawn(sock: DatagramSocket, tag: String) {
        val t = Thread {
            val buf = ByteArray(2048)
            while (running.get() && !sock.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    handle(tag, p)
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        t.isDaemon = true
        t.name = "probe-$tag"
        try { t.start(); threads.add(t) } catch (_: Throwable) {}
    }

    private fun handle(tag: String, p: DatagramPacket) {
        val src = "${p.address?.hostAddress}:${p.port}"
        val len = p.length
        val detail = if (tag == "mdns") describeDns(p.data, p.offset, len) else describe(p.data, p.offset, len)
        logThrottled("$tag-$src-$detail", "[$tag] recv $src len=$len $detail")
    }

    /** mDNS 包里把查询的域名抠出来（如 _airplay._tcp.local） */
    private fun describeDns(data: ByteArray, off: Int, len: Int): String {
        return try {
            if (len < 13) return "dns(short)"
            var i = off + 12
            val sb = StringBuilder("q=")
            var labels = 0
            while (i < off + len && labels < 6) {
                val l = data[i].toInt() and 0xFF
                if (l == 0) { i++; break }
                if (l > 63) break
                if (i + 1 + l > off + len) break
                sb.append(String(data, i + 1, l, Charsets.US_ASCII)).append('.')
                i += 1 + l
                labels++
            }
            sb.toString().trimEnd('.')
        } catch (_: Throwable) {
            "dns(parse-fail)"
        }
    }

    /** 普通包：能读成可打印文本就记文本，否则记十六进制前缀 */
    private fun describe(data: ByteArray, off: Int, len: Int): String {
        val n = minOf(len, 64)
        var printable = 0
        for (i in 0 until n) {
            val c = data[off + i].toInt() and 0xFF
            if (c in 32..126 || c == 9 || c == 10 || c == 13) printable++
        }
        return if (n > 0 && printable * 100 / n > 85) {
            String(data, off, n, Charsets.UTF_8).replace('\n', ' ').replace('\r', ' ').trim()
        } else {
            val sb = StringBuilder("hex=")
            for (i in 0 until minOf(len, 32)) sb.append(String.format("%02x", data[off + i]))
            sb.toString()
        }
    }

    private fun logThrottled(key: String, msg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastLogAt) {
            if (now - (lastLogAt[key] ?: 0L) < 3000L) return
            lastLogAt[key] = now
        }
        Trace.log("PROBE", msg)
    }
}
