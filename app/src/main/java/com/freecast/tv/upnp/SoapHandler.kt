package com.freecast.tv.upnp

import com.freecast.tv.CastEngine
import java.net.URI
import java.util.Locale

/**
 * 处理 DLNA 控制点发来的 SOAP 请求（AVTransport / RenderingControl / ConnectionManager）。
 */
class SoapHandler {

    data class Result(val body: String, val fault: Boolean = false)

    private val sinkProtocols = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/avi:*",
        "http-get:*:video/x-msvideo:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/3gpp:*",
        "http-get:*:video/webm:*",
        "http-get:*:video/*:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/aac:*",
        "http-get:*:audio/flac:*",
        "http-get:*:audio/wav:*",
        "http-get:*:audio/*:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegURL:*",
        "http-get:*:application/dash+xml:*",
        "http-get:*:*:*"
    ).joinToString(",")

    fun handle(
        service: String,
        soapAction: String,
        body: String,
        remoteIp: String? = null
    ): Result {
        val action = soapAction.substringAfter('#', "").ifEmpty { extractAction(body) }
        return when (service) {
            "AVTransport" -> handleAvTransport(action, body, remoteIp)
            "RenderingControl" -> handleRenderingControl(action, body)
            "ConnectionManager" -> handleConnectionManager(action)
            else -> fault(401, "Invalid Action")
        }
    }

    private fun handleAvTransport(action: String, body: String, remoteIp: String?): Result {
        val service = "AVTransport"
        return when (action) {
            "SetAVTransportURI" -> {
                val rawUri = decode(arg(body, "CurrentURI") ?: "")
                val metadata = arg(body, "CurrentURIMetaData")
                val decodedMeta = metadata?.let { decode(it) }
                val resInfo = extractResInfo(decodedMeta)
                val candidates = listOf(rawUri, resInfo.first.orEmpty())
                    .filter { it.isNotBlank() }
                // 优先选非本机回环地址；部分控制点会同时给出 127.0.0.1 和局域网地址
                val chosen = candidates.firstOrNull { !isLoopbackUrl(it) }
                    ?: candidates.firstOrNull()
                val uri = chosen?.let { replaceLoopbackHost(it, remoteIp) } ?: ""
                if (uri.isBlank()) {
                    fault(402, "Invalid Args")
                } else {
                    CastEngine.setAvTransportUri(uri, extractTitle(decodedMeta), resInfo.second)
                    ok(service, action)
                }
            }

            "SetNextAVTransportURI" -> ok(service, action)

            "Play" -> {
                val speed = arg(body, "Speed")?.toFloatOrNull()
                CastEngine.play(speed)
                ok(service, action)
            }

            "Pause" -> {
                CastEngine.pause()
                ok(service, action)
            }

            "Stop" -> {
                CastEngine.stop()
                ok(service, action)
            }

            "Seek" -> {
                val unit = arg(body, "Unit") ?: "REL_TIME"
                val target = arg(body, "Target") ?: ""
                if (unit != "REL_TIME" && unit != "ABS_TIME") {
                    fault(710, "Seek mode not supported")
                } else {
                    val ms = parseTime(target)
                    if (ms < 0) {
                        fault(711, "Illegal seek target")
                    } else {
                        CastEngine.seekTo(ms)
                        ok(service, action)
                    }
                }
            }

            "Next", "Previous" -> ok(service, action)

            "GetTransportInfo" -> {
                val state = CastEngine.getState()
                ok(
                    service, action,
                    "<CurrentTransportState>${state.transportState}</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                        "<CurrentSpeed>${formatSpeed(state.speed)}</CurrentSpeed>"
                )
            }

            "GetMediaInfo" -> {
                val state = CastEngine.getState()
                ok(
                    service, action,
                    "<NrTracks>${if (state.hasMedia) 1 else 0}</NrTracks>" +
                        "<MediaDuration>${formatHms(state.durationMs)}</MediaDuration>" +
                        "<CurrentURI>${escapeXml(state.uri ?: "")}</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>" +
                        "<NextURI></NextURI>" +
                        "<NextURIMetaData></NextURIMetaData>" +
                        "<PlayMedium>NETWORK</PlayMedium>" +
                        "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                        "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>"
                )
            }

            "GetPositionInfo" -> {
                val state = CastEngine.getState()
                val uri = escapeXml(state.uri ?: "")
                val rel = formatHms(state.positionMs)
                val dur = formatHms(state.durationMs)
                ok(
                    service, action,
                    "<Track>1</Track>" +
                        "<TrackDuration>$dur</TrackDuration>" +
                        "<TrackMetaData></TrackMetaData>" +
                        "<TrackURI>$uri</TrackURI>" +
                        "<RelTime>$rel</RelTime>" +
                        "<AbsTime>$rel</AbsTime>" +
                        "<RelCount>2147483647</RelCount>" +
                        "<AbsCount>2147483647</AbsCount>"
                )
            }

            "GetDeviceCapabilities" -> ok(
                service, action,
                "<PlayMedia>NETWORK</PlayMedia>" +
                    "<RecMedia>NOT_IMPLEMENTED</RecMedia>" +
                    "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>"
            )

            "GetTransportSettings" -> ok(
                service, action,
                "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>"
            )

            "GetCurrentTransportActions" -> ok(
                service, action,
                "<Actions>Play,Stop,Pause,Seek</Actions>"
            )

            else -> fault(401, "Invalid Action")
        }
    }

    private fun handleRenderingControl(action: String, body: String): Result {
        val service = "RenderingControl"
        return when (action) {
            "GetVolume" -> {
                val state = CastEngine.getState()
                ok(service, action, "<CurrentVolume>${state.volume}</CurrentVolume>")
            }

            "SetVolume" -> {
                val volume = arg(body, "DesiredVolume")?.toIntOrNull()
                if (volume == null) {
                    fault(402, "Invalid Args")
                } else {
                    CastEngine.setVolume(volume)
                    ok(service, action)
                }
            }

            "GetMute" -> {
                val state = CastEngine.getState()
                ok(service, action, "<CurrentMute>${if (state.muted) 1 else 0}</CurrentMute>")
            }

            "SetMute" -> {
                val value = arg(body, "DesiredMute") ?: "0"
                CastEngine.setMuted(value == "1" || value.equals("true", true) ||
                    value.equals("yes", true) || value.equals("on", true))
                ok(service, action)
            }

            "ListPresets" -> ok(service, action, "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>")

            "SelectPreset" -> ok(service, action)

            else -> fault(401, "Invalid Action")
        }
    }

    private fun handleConnectionManager(action: String): Result {
        val service = "ConnectionManager"
        return when (action) {
            "GetProtocolInfo" -> ok(
                service, action,
                "<Source></Source><Sink>${escapeXml(sinkProtocols)}</Sink>"
            )

            "GetCurrentConnectionIDs" -> ok(service, action, "<ConnectionIDs>0</ConnectionIDs>")

            "GetCurrentConnectionInfo" -> ok(
                service, action,
                "<RcsID>0</RcsID>" +
                    "<AVTransportID>0</AVTransportID>" +
                    "<ProtocolInfo></ProtocolInfo>" +
                    "<PeerConnectionManager></PeerConnectionManager>" +
                    "<PeerConnectionID>-1</PeerConnectionID>" +
                    "<Direction>Input</Direction>" +
                    "<Status>OK</Status>"
            )

            else -> fault(401, "Invalid Action")
        }
    }

    private fun ok(service: String, action: String, inner: String = ""): Result {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">" +
            inner +
            "</u:${action}Response>" +
            "</s:Body>" +
            "</s:Envelope>"
        return Result(body)
    }

    private fun fault(code: Int, description: String): Result {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<s:Fault>" +
            "<faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring>" +
            "<detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode>" +
            "<errorDescription>$description</errorDescription>" +
            "</UPnPError>" +
            "</detail>" +
            "</s:Fault>" +
            "</s:Body>" +
            "</s:Envelope>"
        return Result(body, true)
    }

    private fun extractAction(body: String): String {
        val regex = Regex(
            "<(?:\\w+:)?Body[^>]*>\\s*<(?:\\w+:)?([A-Za-z0-9_]+)",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return regex.find(body)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun arg(body: String, name: String): String? {
        val regex = Regex(
            "<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(body) ?: return null
        return match.groupValues[1].trim()
    }

    private fun decode(value: String): String {
        var result = value
        // 部分控制点会对 DIDL-Lite 做二次转义
        repeat(2) {
            if (result.contains("&lt;") || result.contains("&amp;")) {
                result = unescapeXml(result)
            }
        }
        return result
    }

    private fun extractTitle(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        val regex = Regex(
            "<dc:title[^>]*>(.*?)</dc:title>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(metadata) ?: return null
        return match.groupValues[1]
            .replace("<![CDATA[", "")
            .replace("]]>", "")
            .trim()
            .ifEmpty { null }
    }

    private fun isLoopbackUrl(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase(Locale.US)
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
    }

    /**
     * 控制点偶尔会把本机回环地址发给电视（如 http://127.0.0.1:7000/...）。
     * 电视无法访问手机的 127.0.0.1，这里尝试替换为控制请求的来源 IP。
     */
    private fun replaceLoopbackHost(url: String, remoteIp: String?): String {
        if (remoteIp.isNullOrBlank() || !isLoopbackUrl(url)) return url
        return try {
            val u = URI(url)
            URI(u.scheme, u.userInfo, remoteIp, u.port, u.path, u.query, u.fragment).toString()
        } catch (_: Exception) {
            url
        }
    }

    /**
     * 从 DIDL-Lite 元数据中解析出资源地址和 MIME 类型，
     * MIME 有助于 HLS / DASH 这类无扩展名地址被正确识别。
     */
    private fun extractResInfo(metadata: String?): Pair<String?, String?> {
        if (metadata.isNullOrBlank()) return null to null
        val regex = Regex(
            "<res([^>]*)>(.*?)</res>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(metadata) ?: return null to null
        val attributes = match.groupValues[1]
        val url = unescapeXml(match.groupValues[2].trim())
        val protocolInfo = Regex(
            "protocolInfo\\s*=\\s*\"([^\"]*)\"",
            RegexOption.IGNORE_CASE
        ).find(attributes)?.groupValues?.get(1)
        val mime = protocolInfo
            ?.split(":", limit = 4)
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "*" }
        return url to mime
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun parseTime(target: String): Long {
        val text = target.trim()
        if (text.isEmpty()) return -1
        if (text.contains(":")) {
            var total = 0.0
            for (part in text.split(":")) {
                val value = part.toDoubleOrNull() ?: return -1
                total = total * 60.0 + value
            }
            return (total * 1000).toLong()
        }
        val value = text.toDoubleOrNull() ?: return -1
        return (value * 1000).toLong()
    }

    private fun formatHms(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSpeed(speed: Float): String {
        if (speed == speed.toInt().toFloat()) return speed.toInt().toString()
        return String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
}
