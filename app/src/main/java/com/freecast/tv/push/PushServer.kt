package com.freecast.tv.push

import android.content.Context
import android.util.Log
import com.freecast.tv.DeviceInfo
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网文件推送服务：
 * - 手机浏览器打开 http://设备IP:端口/ 上传文件（multipart 表单）
 * - 设备端实时读取上传进度
 * - 文件保存在应用外部私有目录 push/ 下
 */
object PushServer {

    private const val TAG = "PushServer"
    private const val MAX_PORT_SCAN = 16
    private const val PREFERRED_PORT = 8913

    data class Item(
        val name: String,
        val file: File?,
        val sizeBytes: Long,
        val active: Boolean,
        val received: Long,
        val total: Long,
        val elapsedMs: Long = 0
    )

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** 最近一次客户端活动，用于推送页诊断显示 */
    @Volatile
    var lastEvent: String = ""
        private set

    private var dir: File? = null
    private var serverSocket: ServerSocket? = null
    private val acceptThread = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val active = ConcurrentHashMap<String, Progress>()

    private class Progress {
        @Volatile var received: Long = 0
        @Volatile var total: Long = -1
        val startedAt: Long = System.currentTimeMillis()
    }

    fun start(context: Context): Boolean {
        if (running && serverSocket?.isClosed == false) return true
        running = false
        serverSocket?.let { try { it.close() } catch (_: Exception) {} }
        serverSocket = null
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(base, "push").apply { mkdirs() }
        dir = target
        active.clear()

        // 固定端口：优先复用上次成功监听的端口，其次 8913~8928
        val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
        val preferred = prefs.getInt("port", PREFERRED_PORT)
        val candidates = ArrayList<Int>()
        candidates.add(preferred)
        for (p in PREFERRED_PORT until PREFERRED_PORT + MAX_PORT_SCAN) {
            if (p != preferred) candidates.add(p)
        }

        var chosen = 0
        var sock: ServerSocket? = null
        for (p in candidates) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(p))
                sock = s
                chosen = p
                break
            } catch (_: Exception) {
                try { s.close() } catch (_: Exception) {}
            }
        }
        if (sock == null) {
            try {
                val fallback = ServerSocket(0).apply { reuseAddress = true }
                sock = fallback
                chosen = fallback.localPort
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                running = false
                return false
            }
        }
        val server = sock ?: return false
        serverSocket = server
        port = chosen
        prefs.edit().putInt("port", chosen).apply()
        running = true
        Thread({
            acceptLoop(server)
        }, "PushServer-accept").start()
        Log.i(TAG, "push server started on port $chosen")
        return true
    }

    /** 选择稳定的局域网 IP：优先 192.168，尽量复用已展示过的地址 */
    @Volatile
    private var cachedIp: String? = null

    fun localIp(): String {
        val ips = DeviceInfo.ips
        cachedIp?.let { c -> if (ips.contains(c)) return c }
        val ip = ips.firstOrNull { it.startsWith("192.168.") }
            ?: ips.firstOrNull()
            ?: ""
        if (ip.isNotEmpty()) cachedIp = ip
        return ip
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        active.clear()
    }

    fun items(): List<Item> {
        val list = ArrayList<Item>()
        val now = System.currentTimeMillis()
        for ((name, p) in active) {
            list.add(Item(name, null, 0, true, p.received, p.total, (now - p.startedAt).coerceAtLeast(1)))
        }
        val d = dir
        if (d != null) {
            d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
                list.add(Item(f.name, f, f.length(), false, f.length(), f.length()))
            }
        }
        return list
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val s = ss.accept()
                lastEvent = "已连接 ${s.inetAddress?.hostAddress ?: "?"}"
                executor.execute { handleConnection(s) }
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!running || ss.isClosed) break
                // 单次 accept 异常不再终止监听
                Log.w(TAG, "accept error: ${e.message}")
                lastEvent = "监听异常：${e.message}"
            }
        }
    }

    /** 读取请求行与头部，返回 (方法, 路径, 头部小写键映射)。 */
    private fun readHeaders(input: InputStream): Triple<String, String, Map<String, String>> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (prev == '\r'.code && c == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                if (line.isEmpty()) break
                lines.add(line)
                sb.setLength(0)
            } else if (c != '\r'.code) {
                sb.append(c.toChar())
            }
            prev = c
            if (lines.size > 100) break
        }
        val requestLine = lines.firstOrNull() ?: "GET / HTTP/1.1"
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path = parts.getOrElse(1) { "/" }
        val map = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                map[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
        }
        return Triple(method, path, map)
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            socket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val headers = readHeaders(input)
                val method = headers.first
                val path = headers.second
                val headerMap = headers.third
                when {
                    method == "GET" && (path == "/" || path.startsWith("/?")) -> {
                        val body = pageHtml()
                        output.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        output.write(body.toByteArray())
                    }

                    method == "POST" && path.startsWith("/upload") -> handleUpload(input, headerMap, output)

                    method == "GET" && path.startsWith("/ping") -> {
                        val body = "ok"
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: 2\r\nConnection: close\r\n\r\n$body").toByteArray()
                        )
                    }

                    else -> output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                }
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection error: ${e.message}")
        }
    }

    private fun handleUpload(input: InputStream, headerMap: Map<String, String>, output: OutputStream) {
        val d = dir ?: return
        val total = headerMap["content-length"]?.toLongOrNull() ?: -1L
        val contentType = headerMap["content-type"] ?: ""
        val boundary = contentType
            .substringAfter("boundary=", "")
            .trim().trim('"').substringBefore(';').trim()
        if (boundary.isEmpty() || total <= 0) {
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val tmp = File.createTempFile("push_", ".tmp", d)
        val progress = Progress().apply { this.total = total }
        val stamp = System.currentTimeMillis()
        active["upload_$stamp"] = progress
        try {
            drainBody(input, tmp, progress, total)
            val name = extractUpload(tmp, boundary, d) ?: run {
                output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                null
            } ?: return
            Log.i(TAG, "received file $name")
            lastEvent = "收到文件 $name"
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: 2\r\nConnection: close\r\n\r\nok").toByteArray()
            )
        } catch (e: Exception) {
            Log.w(TAG, "upload error: ${e.message}")
            lastEvent = "上传失败：${e.message}"
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {
            }
        } finally {
            active.remove("upload_$stamp")
            tmp.delete()
        }
    }

    private fun drainBody(input: InputStream, target: File, progress: Progress, total: Long) {
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            var received = 0L
            while (received < total) {
                val want = minOf(buf.size.toLong(), total - received).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
                received += n
                progress.received = received
            }
        }
    }

    private fun pageHtml(): String {
        val name = DeviceInfo.friendlyName
        return """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>推送文件到 $name</title>
<style>
*{box-sizing:border-box}
body{margin:0;padding:20px 18px 32px;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
background:linear-gradient(180deg,#0a1a34 0%,#0f2447 100%);color:#fff;min-height:100vh}
h1{font-size:23px;margin:6px 0 6px;font-weight:700}
.sub{font-size:15px;color:#9fb3d1;line-height:1.5;margin:0 0 20px}
.drop{display:block;border:2px dashed #4c9aff;border-radius:18px;padding:34px 18px;text-align:center;
background:rgba(76,154,255,.09);cursor:pointer;transition:.2s}
.drop:active,.drop.over{background:rgba(76,154,255,.22);border-color:#8ec2ff}
.drop .ico{font-size:44px;line-height:1}
.drop .t{font-size:19px;font-weight:600;margin-top:12px}
.drop .s{font-size:14px;color:#9fb3d1;margin-top:8px}
input[type=file]{display:none}
.list{margin-top:22px;font-size:16px}
.row{background:#12294a;border-radius:14px;padding:14px 16px;margin-bottom:12px}
.row .n{display:flex;justify-content:space-between;font-size:16px;margin-bottom:10px;gap:12px}
.row .n span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar{height:10px;background:#0a1a34;border-radius:6px;overflow:hidden}
.bar>i{display:block;height:100%;width:0;background:linear-gradient(90deg,#4c9aff,#7bd0ff);transition:width .2s}
.st{font-size:14px;color:#9fb3d1;margin-top:8px}
.st.ok{color:#7ee08a}
.st.err{color:#ff8a80}
.tip{margin-top:26px;font-size:13px;color:#7d90ab;line-height:1.6}
</style></head>
<body>
<h1>推送文件到 $name</h1>
<p class="sub">选择或拖入文件即可上传，进度会同步显示在设备上。</p>
<label class="drop" id="drop" for="f">
  <div class="ico">📤</div>
  <div class="t">点击选择文件</div>
  <div class="s">支持图片、视频、APK 等，可多选</div>
</label>
<input id="f" type="file" multiple>
<div class="list" id="list"></div>
<p class="tip">上传完成后，文件会出现在设备上的「远程推送」列表里，可用遥控器打开或删除。</p>
<script>
var f=document.getElementById('f'),list=document.getElementById('list'),drop=document.getElementById('drop');
f.onchange=function(){start()};
['dragenter','dragover'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.add('over')})});
['dragleave','drop'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.remove('over')})});
drop.addEventListener('drop',function(ev){if(ev.dataTransfer&&ev.dataTransfer.files.length){f.files=ev.dataTransfer.files;start()}});
function fmt(b){if(b<1024)return b+' B';if(b<1048576)return (b/1024).toFixed(0)+' KB';if(b<1073741824)return (b/1048576).toFixed(1)+' MB';return (b/1073741824).toFixed(2)+' GB'}
function start(){
  var files=Array.prototype.slice.call(f.files);
  if(!files.length)return;
  list.innerHTML='';
  files.forEach(function(file){addRow(file)});
  upload(0,files);
}
function addRow(file){
  var d=document.createElement('div');d.className='row';
  d.innerHTML='<div class="n"><span></span><b>0%</b></div><div class="bar"><i></i></div><div class="st">等待上传</div>';
  d.querySelector('.n span').textContent=file.name+' · '+fmt(file.size);
  list.appendChild(d);return d;
}
function upload(i,files){
  if(i>=files.length){f.value='';return}
  var file=files[i],row=list.children[i],bar=row.querySelector('.bar>i'),pct=row.querySelector('.n b'),st=row.querySelector('.st');
  var xhr=new XMLHttpRequest(),fd=new FormData();
  fd.append('file',file);
  st.textContent='上传中…';
  xhr.open('POST','/upload');
  xhr.upload.onprogress=function(e){
    if(!e.lengthComputable)return;
    var p=Math.round(e.loaded*100/e.total);
    bar.style.width=p+'%';pct.textContent=p+'%';
    st.textContent=fmt(e.loaded)+' / '+fmt(e.total);
  };
  xhr.onload=function(){
    if(xhr.status===200){bar.style.width='100%';pct.textContent='100%';st.className='st ok';st.textContent='已推送到设备';upload(i+1,files)}
    else{st.className='st err';st.textContent='失败（'+xhr.status+'），请重试'}
  };
  xhr.onerror=function(){st.className='st err';st.textContent='网络错误，请确认手机与设备在同一 Wi-Fi'};
  xhr.send(fd);
}
</script></body></html>
""".trimIndent()
    }

    // ---- multipart 解析（基于临时文件，避免大文件占内存；纯 JVM 可测） ----

    /** 从 multipart 请求体临时文件中提取第一个文件段，写入 dir 并返回文件名。 */
    fun extractUpload(body: File, boundary: String, dir: File): String? {
        val pat = "\r\n--$boundary".toByteArray()
        val positions = findAll(body, pat)
        if (positions.isEmpty()) return null

        val startTok = "--$boundary".toByteArray()
        var headersStart = -1L
        var dataEnd = -1L
        if (startsWith(body, startTok, 0L)) {
            headersStart = startTok.size.toLong() + 2 // 跳过首行 boundary 的 \r\n
            dataEnd = positions.firstOrNull { it >= headersStart } ?: return null
        } else {
            val first = positions[0]
            headersStart = first + pat.size + 2
            dataEnd = positions.firstOrNull { it >= headersStart } ?: return null
        }

        val hdrEnd = indexOf(body, "\r\n\r\n".toByteArray(), headersStart) ?: return null
        val headers = String(readRange(body, headersStart, (hdrEnd - headersStart).toInt()), Charsets.ISO_8859_1)
        val filename = Regex("filename\\*=utf-8''([^\\r\\n;]+)|filename=\"?([^\"\\r\\n;]+)")
            .find(headers)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val safe = sanitizeName(decodeFilename(filename))

        var outFile = File(dir, safe)
        if (outFile.exists()) {
            val dot = safe.lastIndexOf('.')
            val base = if (dot > 0) safe.substring(0, dot) else safe
            val ext = if (dot > 0) safe.substring(dot) else ""
            var i = 1
            while (outFile.exists()) {
                outFile = File(dir, "$base($i)$ext")
                i++
            }
        }
        copyRange(body, hdrEnd + 4, dataEnd, outFile)
        return outFile.name
    }

    /**
     * multipart 文件名解码：
     * - filename*=UTF-8''... 为 RFC 5987 百分号编码，直接 URLDecoder
     * - 普通 filename="中文.apk"：浏览器写入的是 UTF-8 原始字节，而头部按 ISO-8859-1 读取，
     *   需要把它还原成字节后再按 UTF-8 解释，否则中文会显示为乱码
     */
    private fun decodeFilename(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains('%') && Regex("%[0-9A-Fa-f]{2}").containsMatchIn(trimmed)) {
            return try {
                java.net.URLDecoder.decode(trimmed, "UTF-8")
            } catch (_: Exception) {
                trimmed
            }
        }
        val decoded = String(trimmed.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (decoded.contains('\uFFFD')) trimmed else decoded
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "file_${System.currentTimeMillis()}" else cleaned.take(120)
    }

    private fun matchAt(data: ByteArray, off: Int, pattern: ByteArray): Boolean {
        if (off + pattern.size > data.size) return false
        for (i in pattern.indices) {
            if (data[off + i] != pattern[i]) return false
        }
        return true
    }

    private fun findAll(file: File, pattern: ByteArray): List<Long> {
        val result = ArrayList<Long>()
        RandomAccessFile(file, "r").use { ra ->
            val bufSize = 1 shl 20
            val buf = ByteArray(bufSize)
            var carry = ByteArray(0)
            var base = 0L // 已读原始字节数（不含 carry）
            while (true) {
                val n = ra.read(buf)
                if (n <= 0) break
                val data = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOf(n)
                val dataStart = base - carry.size // data[0] 的绝对位置
                var i = 0
                while (i + pattern.size <= data.size) {
                    if (matchAt(data, i, pattern)) {
                        result.add(dataStart + i)
                        i += pattern.size
                    } else {
                        i++
                    }
                }
                val keep = minOf(pattern.size - 1, data.size)
                carry = data.copyOfRange(data.size - keep, data.size)
                base += n
            }
        }
        return result
    }

    private fun indexOf(file: File, pattern: ByteArray, from: Long): Long? {
        RandomAccessFile(file, "r").use { ra ->
            ra.seek(from)
            val bufSize = 1 shl 20
            val buf = ByteArray(bufSize)
            var carry = ByteArray(0)
            var base = from // 已读原始字节数（不含 carry）
            while (true) {
                val n = ra.read(buf)
                if (n <= 0) break
                val data = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOf(n)
                val dataStart = base - carry.size
                var i = 0
                while (i + pattern.size <= data.size) {
                    if (matchAt(data, i, pattern)) return dataStart + i
                    i++
                }
                val keep = minOf(pattern.size - 1, data.size)
                carry = data.copyOfRange(data.size - keep, data.size)
                base += n
            }
        }
        return null
    }

    private fun startsWith(file: File, prefix: ByteArray, at: Long): Boolean {
        RandomAccessFile(file, "r").use { ra ->
            ra.seek(at)
            val buf = ByteArray(prefix.size)
            val n = ra.read(buf)
            if (n < prefix.size) return false
            for (i in prefix.indices) {
                if (buf[i] != prefix[i]) return false
            }
        }
        return true
    }

    private fun readRange(file: File, start: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        RandomAccessFile(file, "r").use { ra ->
            ra.seek(start)
            var read = 0
            while (read < length) {
                val n = ra.read(out, read, length - read)
                if (n < 0) break
                read += n
            }
        }
        return out
    }

    private fun copyRange(file: File, start: Long, endExclusive: Long, target: File) {
        if (endExclusive <= start) return
        RandomAccessFile(file, "r").use { ra ->
            FileOutputStream(target).use { out ->
                ra.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = endExclusive - start
                while (remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = ra.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }
}
