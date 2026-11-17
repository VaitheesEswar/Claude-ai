package com.acremote.bluestar.data

import com.acremote.bluestar.core.AcCommands
import com.acremote.bluestar.core.AcDevice
import com.acremote.bluestar.core.AcState
import com.acremote.bluestar.core.IotCredentials
import com.acremote.bluestar.core.SigV4
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** HTTP error from Blue Star / AWS. 401/403 means the session or keys expired. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

data class Session(val token: String, val userName: String, val iot: IotCredentials)

/**
 * Talks to the Blue Star Smart cloud:
 *  - REST API (api-smart.bluestarindia.com) for login and the device list/state;
 *  - AWS IoT device-shadow REST API for sending commands (SigV4-signed).
 *
 * The protocol is unofficial (reverse-engineered from the Blue Star Smart app)
 * and may change without notice. All calls are blocking; run them off the main thread.
 */
class BlueStarClient {

    fun login(authId: String, password: String, isEmail: Boolean): Session {
        val body = JSONObject()
            .put("auth_id", authId.trim())
            .put("auth_type", if (isEmail) 0 else 1)
            .put("password", password)
        val res = JSONObject(request("POST", "$API_BASE/auth/login", body.toString(), appHeaders(null)))
        val user = res.optJSONObject("user") ?: JSONObject()
        val token = res.optString("session").ifBlank { throw ApiException(401, "Login failed: no session returned") }
        val mi = res.optString("mi").ifBlank { user.optString("mi") }
        val iot = IotCredentials.fromMi(mi)
            ?: throw IOException("Login worked but the account returned no device-control credentials")
        return Session(token, user.optString("name", "User"), iot)
    }

    fun fetchDevices(session: Session, previous: Map<String, AcState> = emptyMap()): List<AcDevice> {
        val res = JSONObject(request("GET", "$API_BASE/things", null, appHeaders(session.token)))
        val things = res.optJSONArray("things") ?: JSONArray()
        val states = res.optJSONObject("states") ?: JSONObject()
        return (0 until things.length()).mapNotNull { i ->
            val thing = things.optJSONObject(i) ?: return@mapNotNull null
            val id = thing.optString("thing_id").ifBlank { return@mapNotNull null }
            val st = states.optJSONObject(id) ?: JSONObject()
            val reported = st.optJSONObject("reported")?.toMap() ?: emptyMap()
            val state = AcState.fromReported(reported, st.optBoolean("connected", true), previous[id] ?: AcState())
            val name = thing.optString("name").trim().ifBlank { "AC ${id.takeLast(4)}" }
            AcDevice(id, name, state)
        }
    }

    /** Writes `{"state":{"desired":...}}` to the device shadow; the AC picks it up from there. */
    fun sendCommand(session: Session, thingId: String, desired: Map<String, Any>) {
        val iot = session.iot
        val url = "https://${iot.endpoint}/things/${SigV4.uriEncode(thingId)}/shadow"
        val body = AcCommands.shadowPayload(desired)
        val headers = SigV4.sign(
            method = "POST",
            uri = URI(url),
            body = body.toByteArray(),
            region = iot.region,
            service = "iotdata",
            accessKey = iot.accessKey,
            secretKey = iot.secretKey,
            headers = mapOf("Content-Type" to "application/json"),
        )
        request("POST", url, body, headers)
    }

    private fun appHeaders(token: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        // The cloud API only answers requests that look like the official app.
        put("User-Agent", "com.bluestarindia.bluesmart")
        put("X-APP-VER", "v5.1.2-183")
        put("X-OS-NAME", "Android")
        put("X-OS-VER", "14-34")
        if (token != null) {
            put("Authorization", "Bearer $token")
            put("X-APP-SESSION", token)
        }
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(code, errorMessage(code, text))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, text: String): String {
        val serverMsg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        return when {
            serverMsg.isNotBlank() -> serverMsg
            code == 401 || code == 403 -> "Not authorised – check your login details"
            else -> "Server error ($code)"
        }
    }

    companion object {
        const val API_BASE = "https://api-smart.bluestarindia.com"
    }
}

private fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> opt(key).unwrap() }

private fun Any?.unwrap(): Any? = when (this) {
    JSONObject.NULL -> null
    is JSONObject -> toMap()
    is JSONArray -> (0 until length()).map { opt(it).unwrap() }
    else -> this
}
