package com.acremote.bluestar.core

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vectors from the official AWS Signature Version 4 test suite. */
class SigV4Test {
    private val accessKey = "AKIDEXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val now = Instant.parse("2015-08-30T12:36:00Z")

    private fun sign(method: String) = SigV4.sign(
        method = method,
        uri = URI("https://example.amazonaws.com/"),
        body = ByteArray(0),
        region = "us-east-1",
        service = "service",
        accessKey = accessKey,
        secretKey = secretKey,
        now = now,
    )

    @Test
    fun getVanilla() {
        val h = sign("GET")
        assertEquals("20150830T123600Z", h["X-Amz-Date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            h["Authorization"],
        )
    }

    @Test
    fun postVanilla() {
        val h = sign("POST")
        assertTrue(h["Authorization"]!!.endsWith(
            "Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b"))
    }

    @Test
    fun uriEncodeKeepsUnreservedOnly() {
        assertEquals("AbC-_.~09", SigV4.uriEncode("AbC-_.~09"))
        assertEquals("a%20b%2Fc%24", SigV4.uriEncode("a b/c$"))
    }
}
