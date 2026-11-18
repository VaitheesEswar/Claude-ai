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
        val things = parseThings(fetchThingsRaw(session))
        return things.map { t ->
            var state = AcState.fromReported(t.reported, t.connected ?: true, previous[t.id] ?: AcState())
            // The AC's own cloud record (AWS IoT shadow) is the most direct source of its state.
            val shadowReported = runCatching { shadowReported(getShadowRaw(session, t.id)) }.getOrNull()
            if (!shadowReported.isNullOrEmpty()) {
                state = AcState.fromReported(shadowReported, state.connected, state)
            }
            AcDevice(t.id, t.name, state)
        }
    }

    /** Raw body of GET /things (for parsing and the Diagnostics screen). */
    fun fetchThingsRaw(session: Session): String =
        request("GET", "$API_BASE/things", null, appHeaders(session.token))

    /** Raw AWS IoT shadow document of one AC. */
    fun getShadowRaw(session: Session, thingId: String): String {
        val iot = session.iot
        val url = "https://${iot.endpoint}/things/${SigV4.uriEncode(thingId)}/shadow"
        val headers = SigV4.sign(
            method = "GET",
            uri = URI(url),
            body = ByteArray(0),
            region = iot.region,
            service = "iotdata",
            accessKey = iot.accessKey,
            secretKey = iot.secretKey,
        )
        return request("GET", url, null, headers)
    }

    private class ThingInfo(val id: String, val name: String, val reported: Map<String, Any?>, val connected: Boolean?)

    /** Tolerates the few shapes the /things response has been seen in. */
    private fun parseThings(raw: String): List<ThingInfo> {
        val res = JSONObject(raw)
        val things = res.optJSONArray("things") ?: res.optJSONArray("data") ?: JSONArray()
        val statesObj = res.optJSONObject("states")
        val statesArr = res.optJSONArray("states")
        return (0 until things.length()).mapNotNull { i ->
            val thing = things.optJSONObject(i) ?: return@mapNotNull null
            val id = firstString(thing, "thing_id", "thingId", "udid", "id") ?: return@mapNotNull null
            val st: JSONObject = statesObj?.optJSONObject(id)
                ?: statesArr?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .firstOrNull { firstString(it, "thing_id", "thingId", "udid", "id") == id }
                }
                ?: thing.optJSONObject("state")
                ?: JSONObject()
            val reportedObj = st.optJSONObject("reported")
                ?: st.optJSONObject("state")?.optJSONObject("reported")
                ?: thing.optJSONObject("reported")
                ?: st.takeIf { it.has("pow") || it.has("stemp") }
            val connected = asBool(st.opt("connected"))
                ?: asBool(st.optJSONObject("state")?.opt("connected"))
                ?: asBool(thing.opt("connected"))
                ?: asBool(thing.opt("online"))
            val name = firstString(thing, "name", "nickname", "nick_name", "device_name", "thing_name", "alias", "room")
                ?: "AC ${id.takeLast(4)}"
            ThingInfo(id, name, reportedObj?.toMap() ?: emptyMap(), connected)
        }
    }

    private fun shadowReported(raw: String): Map<String, Any?> =
        JSONObject(raw).optJSONObject("state")?.optJSONObject("reported")?.toMap() ?: emptyMap()

    private fun firstString(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.ifBlank { null } }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.lowercase()) { "true", "1", "online", "connected" -> true; "false", "0", "offline", "disconnected" -> false; else -> null }
        else -> null
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

internal fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> opt(key).unwrap() }

private fun Any?.unwrap(): Any? = when (this) {
    JSONObject.NULL -> null
    is JSONObject -> toMap()
    is JSONArray -> (0 until length()).map { opt(it).unwrap() }
    else -> this
}
