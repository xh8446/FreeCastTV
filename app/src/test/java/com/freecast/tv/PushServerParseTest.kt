package com.freecast.tv

import com.freecast.tv.push.PushServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PushServer 的 multipart 请求体解析测试（纯 JVM，不依赖 Android 框架）。 */
class PushServerParseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildBody(boundary: String, filename: String, content: ByteArray): ByteArray {
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        return head + content + tail
    }

    @Test
    fun extractSmallFile() {
        val boundary = "----WebKitFormBoundaryABC123"
        val body = buildBody(boundary, "video.mp4", "hello world".toByteArray())
        val bodyFile = tmp.newFile("body.bin").apply { writeBytes(body) }
        val outDir = tmp.newFolder("out")

        val name = PushServer.extractUpload(bodyFile, boundary, outDir)
        assertEquals("video.mp4", name)
        val saved = File(outDir, "video.mp4")
        assertTrue(saved.exists())
        assertEquals("hello world", saved.readText())
    }

    @Test
    fun extractLargeBinaryAcrossBufferEdges() {
        val boundary = "----WebKitFormBoundaryXYZ789"
        // 3MB 随机内容，超过 1MB 扫描缓冲，覆盖跨块边界匹配
        val content = ByteArray(3 * 1024 * 1024) { (it * 31 + it / 7919).toByte() }
        val body = buildBody(boundary, "movie.mkv", content)
        val bodyFile = tmp.newFile("body2.bin").apply { writeBytes(body) }
        val outDir = tmp.newFolder("out2")

        val name = PushServer.extractUpload(bodyFile, boundary, outDir)
        assertEquals("movie.mkv", name)
        val saved = File(outDir, "movie.mkv")
        assertTrue(saved.exists())
        assertEquals(content.size.toLong(), saved.length())
        assertTrue(content.contentEquals(saved.readBytes()))
    }

    @Test
    fun duplicateNameGetsSuffix() {
        val boundary = "----WebKitFormBoundaryDUP"
        val outDir = tmp.newFolder("out3")
        val body = buildBody(boundary, "a.txt", "first".toByteArray())
        val bodyFile = tmp.newFile("body3.bin").apply { writeBytes(body) }
        assertNotNull(PushServer.extractUpload(bodyFile, boundary, outDir))
        val second = PushServer.extractUpload(bodyFile, boundary, outDir)
        assertEquals("a(1).txt", second)
    }

    @Test
    fun invalidBodyReturnsNull() {
        val boundary = "----WebKitFormBoundaryBAD"
        val outDir = tmp.newFolder("out4")
        val bodyFile = tmp.newFile("body4.bin").apply { writeBytes("not multipart".toByteArray()) }
        assertEquals(null, PushServer.extractUpload(bodyFile, boundary, outDir))
    }
}
