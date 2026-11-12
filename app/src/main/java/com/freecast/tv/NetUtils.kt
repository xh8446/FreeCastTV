package com.freecast.tv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /**
     * 返回所有可用的局域网 IPv4 地址，优先 192.168 / 10. / 172.16-31 网段。
     * 车机、热点场景常有多块网卡，全部返回便于 SSDP 在每块网卡上广播。
     */
    fun allIpv4Addresses(): List<String> {
        val result = ArrayList<Pair<String, Int>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in java.util.Collections.list(interfaces)) {
                try {
                    if (!nif.isUp || nif.isLoopback || nif.isPointToPoint) continue
                    for (addr in java.util.Collections.list(nif.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip == "0.0.0.0") continue
                            result.add(ip to score(ip))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result.distinctBy { it.first }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun localIpAddress(): String = allIpv4Addresses().firstOrNull() ?: ""

    private fun score(ip: String): Int = when {
        ip.startsWith("192.168.") -> 3
        ip.startsWith("10.") -> 2
        ip.startsWith("172.") -> 2
        else -> 1
    }
}
