package com.studyontv.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 网络读取工具。
 *
 * 以前几个下载 / 接口调用都是先 readBytes() 再检查大小，只要对方（或被劫持的线路）返回超大响应，
 * 就会先把内存吃满再报错。这里统一改成边读边累计、超限立即抛异常并断开。
 */
object Net {
    class TooLarge(val limit: Int) : Exception("响应超过 ${limit / 1024} KB")

    /** URLConnection.getContentLengthLong() 需要 API 24，minSdk 21 上只能读响应头 */
    fun declaredLength(connection: java.net.URLConnection): Long =
        connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

    fun readLimited(stream: InputStream?, maxBytes: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                out.write(chunk, 0, n)
                if (out.size() > maxBytes) throw TooLarge(maxBytes)
            }
            out.toByteArray()
        }
    }

    fun text(stream: InputStream?, maxBytes: Int): String = String(readLimited(stream, maxBytes), Charsets.UTF_8)
}
