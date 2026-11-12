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

        // <d:href>/dav/CMFA-backup/history/20261112-111900_Pixel-a1b2.enc</d:href>; the prefix of the tag varies
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
            it.startHandshake()

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

            return readResponse(BufferedInputStream(it.inputStream), method)
        }
    }

    private fun readResponse(input: InputStream, method: String): Response {
        val status = readLine(input) ?: throw IOException("no response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response: $status")

        var length = -1L
        var chunked = false

        while (true) {
            val line = readLine(input) ?: break

            if (line.isEmpty()) break

            val colon = line.indexOf(':')

            if (colon < 0) continue

            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            if (name == "content-length") length = value.toLongOrNull() ?: -1L
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }

        if (method == "HEAD" || code == 204 || code == 304 || code in 100..199) return Response(code, ByteArray(0))

        val body = ByteArrayOutputStream()

        when {
            chunked -> {
                while (true) {
                    val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16)
                        ?: throw IOException("bad chunk")

                    if (size == 0) break

                    copy(input, body, size.toLong())
                    readLine(input) // the CRLF after the chunk
                }
            }
            length >= 0 -> copy(input, body, length)
            else -> input.copyTo(body) // until the server closes the connection
        }

        return Response(code, body.toByteArray())
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Long) {
        val buffer = ByteArray(8192)
        var left = count

        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())

            if (n < 0) throw IOException("connection closed early")

            out.write(buffer, 0, n)
            left -= n
        }
    }

    // a header line (ASCII), without the line end; null at the end of the stream
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()

        while (true) {
            val b = input.read()

            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')

            line.append(b.toChar())
        }
    }

    companion object {
        const val HOST = "dav.jianguoyun.com"
        private const val ROOT = "/dav/"
        private const val TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000

        private val HREF = Regex("<(?:[A-Za-z0-9]+:)?href>([^<]*)</(?:[A-Za-z0-9]+:)?href>", RegexOption.IGNORE_CASE)
    }
}
