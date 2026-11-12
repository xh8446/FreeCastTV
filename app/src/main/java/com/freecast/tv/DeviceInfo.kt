package com.freecast.tv

object DeviceInfo {
    @Volatile
    var uuid: String = ""

    @Volatile
    var friendlyName: String = "自由投屏 TV"

    @Volatile
    var ip: String = ""

    @Volatile
    var ips: List<String> = emptyList()

    @Volatile
    var ssdpInterfaces: Int = 0

    @Volatile
    var httpPort: Int = 49152

    fun udn(): String = "uuid:$uuid"

    fun locationUrl(): String = "http://$ip:$httpPort/desc.xml"
}
