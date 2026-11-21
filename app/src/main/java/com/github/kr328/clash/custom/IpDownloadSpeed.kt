package com.github.kr328.clash.custom

import com.github.kr328.clash.core.model.IpTestReport
import org.json.JSONObject

/**
 * CUSTOM #158: shared request builder for the existing conftest download mode.
 * The caller supplies the existing Clash connection; this does not implement downloading.
 */
internal object IpDownloadSpeed {
    const val DEFAULT_URL_DIRECT = "https://speed.cloudflare.com/__down?bytes=31457280"
    const val DEFAULT_URL_NODE = "https://proof.ovh.net/files/100Mb.dat"

    suspend fun run(
        ip: String,
        node: String,
        url: String,
        query: suspend (String) -> String,
    ): IpTestReport {
        val request = JSONObject()
            .put("kind", "ip")
            .put("mode", "download")
            .put("node", node)
            .put("ips", ip)
            .put("url", url)
            .toString()
        return IpTestReport.parse(query(request))
    }
}
