package com.github.kr328.clash.design.custom

import java.io.File
import java.io.RandomAccessFile

// CUSTOM (CUSTOM.md #6, #16): the geo files that can be downloaded from a user-supplied https URL.
// [fileName] is the name the core and the upstream MainApplication.extractGeoFiles() use inside
// filesDir/clash; the other fields name the entries of the SharedPreferences `custom_geosite`.
enum class GeoFile(
    val fileName: String,
    val displayName: String,
    val defaultUrl: String,
    val urlKey: String,
    val installedKey: String,
) {
    // keys of GeoSite are the ones of the first version, so the saved address survives the update
    GeoSite(
        "geosite.dat", "GeoSite",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
        "url", "installed_at",
    ),
    GeoIp(
        "geoip.metadb", "GeoIP",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb",
        "url_geoip", "installed_at_geoip",
    ),
    Asn(
        "ASN.mmdb", "ASN",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb",
        "url_asn", "installed_at_asn",
    );

    /** True when [file] looks like this kind of file (a cheap check, not a full parse). */
    fun looksValid(file: File): Boolean {
        return when (this) {
            // geosite.dat is a protobuf GeoSiteList: its first byte is the tag of field 1 (0x0A)
            GeoSite -> file.inputStream().use { it.read() } == PROTOBUF_FIRST_BYTE
            // geoip.metadb and ASN.mmdb are MaxMind DB files: the metadata section starts with a marker
            GeoIp, Asn -> hasMaxMindMarker(file)
        }
    }

    companion object {
        private const val PROTOBUF_FIRST_BYTE = 0x0A

        // "\xAB\xCD\xEFMaxMind.com", always inside the last 128 KiB of a MaxMind DB file
        private const val MAXMIND_MARKER = "«ÍïMaxMind.com"
        private const val MAXMIND_TAIL = 128 * 1024

        private fun hasMaxMindMarker(file: File): Boolean {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val length = minOf(size, MAXMIND_TAIL.toLong()).toInt()
                val tail = ByteArray(length)

                raf.seek(size - length)
                raf.readFully(tail)

                return String(tail, Charsets.ISO_8859_1).contains(MAXMIND_MARKER)
            }
        }
    }
}
