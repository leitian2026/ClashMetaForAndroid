package com.github.kr328.clash.custom

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// CUSTOM (CUSTOM.md #32): the few WebDAV requests the cloud backup needs (MKCOL, PUT, GET, PROPFIND,
// DELETE) against Jianguoyun, over one HTTPS connection per request ("Connection: close").
//
// Why not HttpURLConnection: it refuses the methods MKCOL and PROPFIND. Why not OkHttp: the app has no
// such dependency and a new one would be one more thing to merge. The files are small (a config backup),
// so everything is kept in memory.
//
// Only used when the user taps a cloud backup entry; nothing runs in the background.
class WebDavClient(account: String, password: String) {
    class Response(val code: Int, val body: ByteArray)

    class DavException(val code: Int, message: String) : IOException(message)

    private val authorization = "Basic " + Base64.encodeToString(
        "$account:$password".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )

    /** Creates a folder; a folder that is already there is fine. [path] is relative to the WebDAV root, no leading slash. */
    fun mkcol(path: String) {
        val r = request("MKCOL", path)
        if (r.code !in 200..299 && r.code != 405) fail(r.code, "MKCOL $path")
    }

    fun put(path: String, data: ByteArray) {
        if (data.size.toLong() > MAX_GET_BODY) {
            throw IOException("备份太大（超过 32 MB），上传前已拦下")
        }
        val r = request("PUT", path, data, mapOf("Content-Type" to "application/octet-stream"))
        if (r.code !in 200..299) fail(r.code, "PUT $path")
    }

    /** The file, or null when it is not there. */
    fun get(path: String): ByteArray? {
        val r = request("GET", path)
        if (r.code == 404) return null
        if (r.code !in 200..299) fail(r.code, "GET $path")
        return r.body
    }

    fun delete(path: String) {
        val r = request("DELETE", path)
        if (r.code !in 200..299 && r.code != 404) fail(r.code, "DELETE $path")
    }

    /** The names of the files directly inside the folder [path]; empty when the folder is not there. */
    fun list(path: String): List<String> {
        val r = request(
            "PROPFIND",
            path.trimEnd('/') + "/",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>"
                .toByteArray(Charsets.UTF_8),
            mapOf("Depth" to "1", "Content-Type" to "application/xml"),
        )
        if (r.code == 404) return emptyList()
        if (r.code != 207 && r.code !in 200..299) fail(r.code, "PROPFIND $path")
        val xml = String(r.body, Charsets.UTF_8)
        return HREF.findAll(xml)
            .map { android.net.Uri.decode(it.groupValues[1].trim()).trimEnd('/').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun fail(code: Int, what: String): Nothing {
        val text = when (code) {
            401 -> "401 账号或第三方应用密码不对"
            403 -> "403 没有权限（检查应用密码是不是第三方应用密码）"
            429 -> "429 请求太频繁，坚果云限流了，稍后再试"
            503 -> "503 坚果云暂时不可用或限流，稍后再试"
            else -> "HTTP $code"
        }
        throw DavException(code, "$text（$what）")
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(HOST, 443), TIMEOUT)
        } catch (e: IOException) {
            plain.close()
            throw e
        }
        val socket = factory.createSocket(plain, HOST, 443, true) as SSLSocket
        socket.use {
            it.soTimeout = READ_TIMEOUT
            checkDeadline(deadline)
            it.startHandshake()
            checkDeadline(deadline)

            // an SSLSocket does not check the host name by itself
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, it.session)) {
                throw SSLPeerUnverifiedException("certificate is not for $HOST")
            }

            val head = StringBuilder()
                .append(method).append(" ").append(ROOT).append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(HOST).append("\r\n")
                .append("Authorization: ").append(authorization).append("\r\n")
                .append("User-Agent: CMFA-cloud-backup\r\n")
                .append("Accept: */*\r\n")
                .append("Connection: close\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")

            val out = it.outputStream
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (body != null) out.write(body)
            out.flush()
            checkDeadline(deadline)
            return readResponse(BufferedInputStream(it.inputStream), method, deadline)
        }
    }

    private fun readResponse(input: InputStream, method: String, deadline: Long): Response {
        val headerBytes = longArrayOf(0L)
        val status = readLine(input, deadline, headerBytes) ?: throw IOException("no response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response: $status")
        var length = -1L
        var chunked = false

        while (true) {
            val line = readLine(input, deadline, headerBytes) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "content-length") length = value.toLongOrNull() ?: -1L
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }

        if (method == "HEAD" || code == 204 || code == 304 || code in 100..199) return Response(code, ByteArray(0))

        val successful = code in 200..299 || code == 207
        val limit = when {
            !successful -> MAX_ERROR_BODY
            method == "GET" -> MAX_GET_BODY
            else -> MAX_BODY
        }
        if (successful && length > limit) throw tooLarge(limit)

        val body = ByteArrayOutputStream()
        when {
            chunked -> {
                var total = 0L
                while (true) {
                    checkDeadline(deadline)
                    val size = readLine(input, deadline)?.substringBefore(';')?.trim()?.toLongOrNull(16)
                        ?: throw IOException("bad chunk")
                    if (size < 0L) throw IOException("bad chunk")
                    if (size == 0L) break
                    val remaining = limit - total
                    if (size > remaining) {
                        if (successful) throw tooLarge(limit)
                        copy(input, body, remaining, deadline)
                        return Response(code, body.toByteArray())
                    }
                    copy(input, body, size, deadline)
                    total += size
                    readLine(input, deadline) // the CRLF after the chunk
                }
            }
            length >= 0L -> copy(input, body, minOf(length, limit), deadline)
            else -> copyUntilEnd(input, body, limit, deadline, successful)
        }
        return Response(code, body.toByteArray())
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Long, deadline: Long) {
        val buffer = ByteArray(8192)
        var left = count
        while (left > 0L) {
            checkDeadline(deadline)
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("connection closed early")
            out.write(buffer, 0, n)
            left -= n.toLong()
        }
    }

    private fun copyUntilEnd(
        input: InputStream,
        out: ByteArrayOutputStream,
        limit: Long,
        deadline: Long,
        rejectOverflow: Boolean,
    ) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            checkDeadline(deadline)
            val remaining = limit - total
            if (remaining == 0L) {
                if (!rejectOverflow) return
                val extra = input.read()
                checkDeadline(deadline)
                if (extra < 0) return
                throw tooLarge(limit)
            }
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) return
            out.write(buffer, 0, n)
            total += n.toLong()
        }
    }

    // A header / chunk line (ASCII), without its line ending. [headerBytes] counts the complete response header.
    private fun readLine(input: InputStream, deadline: Long, headerBytes: LongArray? = null): String? {
        val line = StringBuilder()
        var lineBytes = 0
        while (true) {
            checkDeadline(deadline)
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            lineBytes++
            if (lineBytes > MAX_LINE) throw IOException("响应头过长")
            if (headerBytes != null) {
                headerBytes[0]++
                if (headerBytes[0] > MAX_HEADER_BYTES) throw IOException("响应头过长")
            }
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() >= deadline) throw IOException("请求超时（超过 120 秒）")
    }

    private fun tooLarge(limit: Long) =
        IOException("响应太大（超过 ${limit / (1024L * 1024L)} MB）")

    companion object {
        const val HOST = "dav.jianguoyun.com"
        private const val ROOT = "/dav/"
        private const val TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024L
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_GET_BODY = 32L * 1024 * 1024
        private const val MAX_ERROR_BODY = 64L * 1024
        private const val DEADLINE_MS = 120_000L

        private val HREF = Regex("<(?:[A-Za-z0-9]+:)?href>([^<]*)</(?:[A-Za-z0-9]+:)?href>", RegexOption.IGNORE_CASE)
    }
}
