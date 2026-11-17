package com.acremote.bluestar.core

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature Version 4 signer (Authorization-header style).
 *
 * Used to call the AWS IoT device-shadow REST API that Blue Star ACs sync with,
 * without pulling the full AWS SDK into the app.
 */
object SigV4 {
    private val AMZ_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * Returns the headers to put on the request: the caller's [headers] plus
     * `X-Amz-Date`, optional `X-Amz-Security-Token` and `Authorization`.
     * The `Host` header is signed but not returned (HTTP clients set it).
     */
    fun sign(
        method: String,
        uri: URI,
        body: ByteArray,
        region: String,
        service: String,
        accessKey: String,
        secretKey: String,
        sessionToken: String? = null,
        headers: Map<String, String> = emptyMap(),
        now: Instant = Instant.now(),
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val date = amzDate.substring(0, 8)
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"

        val canonical = sortedMapOf<String, String>()
        headers.forEach { (k, v) -> canonical[k.lowercase()] = v.trim().replace(Regex("\\s+"), " ") }
        canonical["host"] = host
        canonical["x-amz-date"] = amzDate
        if (sessionToken != null) canonical["x-amz-security-token"] = sessionToken

        val signedHeaders = canonical.keys.joinToString(";")
        val canonicalHeaders = canonical.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonicalRequest = listOf(
            method.uppercase(),
            canonicalPath(uri.rawPath),
            canonicalQuery(uri.rawQuery),
            canonicalHeaders,
            signedHeaders,
            sha256Hex(body),
        ).joinToString("\n")

        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        var key = hmac("AWS4$secretKey".toByteArray(), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).toHex()

        val out = LinkedHashMap(headers)
        out["X-Amz-Date"] = amzDate
        if (sessionToken != null) out["X-Amz-Security-Token"] = sessionToken
        out["Authorization"] =
            "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return out
    }

    /** RFC 3986 encoding as required by SigV4 (unreserved characters kept, everything else %XX). */
    fun uriEncode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
        return sb.toString()
    }

    // Non-S3 services encode each (already-encoded) path segment once more.
    private fun canonicalPath(rawPath: String?): String {
        if (rawPath.isNullOrEmpty()) return "/"
        return rawPath.split("/").joinToString("/") { uriEncode(it) }
    }

    private fun canonicalQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery.split("&").filter { it.isNotEmpty() }
            .map {
                val i = it.indexOf('=')
                val k = if (i < 0) it else it.substring(0, i)
                val v = if (i < 0) "" else it.substring(i + 1)
                uriEncode(URLDecoder.decode(k, "UTF-8")) to uriEncode(URLDecoder.decode(v, "UTF-8"))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
