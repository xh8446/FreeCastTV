package com.freecast.tv.upnp

import com.freecast.tv.DeviceInfo

object DeviceDescription {

    fun build(): String {
        val name = escape(DeviceInfo.friendlyName)
        val uuid = DeviceInfo.uuid
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$name</friendlyName>
    <manufacturer>FreeCast</manufacturer>
    <manufacturerURL>https://freecast.local</manufacturerURL>
    <modelDescription>FreeCast TV DLNA Renderer</modelDescription>
    <modelName>FreeCast TV</modelName>
    <modelNumber>1.0</modelNumber>
    <modelURL>https://freecast.local</modelURL>
    <serialNumber>$uuid</serialNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/scpd/ConnectionManager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
