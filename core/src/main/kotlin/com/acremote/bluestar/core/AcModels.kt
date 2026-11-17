package com.acremote.bluestar.core

import java.util.Base64

/** Operating modes, with the codes the Blue Star firmware uses. */
enum class AcMode(val code: Int, val label: String) {
    COOL(2, "Cool"),
    DRY(3, "Dry"),
    FAN(0, "Fan"),
    AUTO(4, "Auto");

    companion object {
        fun fromCode(code: Int?): AcMode? = entries.firstOrNull { it.code == code }
    }
}

enum class FanSpeed(val code: Int, val label: String) {
    AUTO(7, "Auto"),
    LOW(2, "Low"),
    MEDIUM(3, "Medium"),
    HIGH(4, "High"),
    TURBO(6, "Turbo");

    companion object {
        fun fromCode(code: Int?): FanSpeed? = entries.firstOrNull { it.code == code }
    }
}

/** Vertical louver: 6 = fixed/off, 0 = continuous swing, 1..5 = fixed angles. */
enum class Swing(val code: Int, val label: String) {
    OFF(6, "Off"),
    AUTO(0, "Swing"),
    L1(1, "1"),
    L2(2, "2"),
    L3(3, "3"),
    L4(4, "4"),
    L5(5, "5");

    companion object {
        fun fromCode(code: Int?): Swing? = entries.firstOrNull { it.code == code }
    }
}

data class AcState(
    val connected: Boolean = true,
    val power: Boolean = false,
    val mode: AcMode = AcMode.COOL,
    val targetTemp: Double = 24.0,
    val roomTemp: Double? = null,
    val fan: FanSpeed = FanSpeed.AUTO,
    val swing: Swing = Swing.OFF,
    val errorCode: Int = 0,
) {
    companion object {
        /**
         * Builds state from a "reported" shadow document. Missing keys keep the
         * values from [previous], so partial updates merge cleanly.
         */
        fun fromReported(reported: Map<String, Any?>, connected: Boolean, previous: AcState = AcState()): AcState {
            val modeRaw = reported["mode"].let { if (it is Map<*, *>) it["value"] else it }
            return AcState(
                connected = connected,
                power = reported["pow"].asInt()?.let { it != 0 } ?: previous.power,
                mode = AcMode.fromCode(modeRaw.asInt()) ?: previous.mode,
                targetTemp = reported["stemp"].asDouble() ?: previous.targetTemp,
                roomTemp = reported["ctemp"].asDouble() ?: previous.roomTemp,
                fan = FanSpeed.fromCode(reported["fspd"].asInt()) ?: previous.fan,
                swing = Swing.fromCode(reported["vswing"].asInt()) ?: previous.swing,
                errorCode = reported["err"].asInt() ?: previous.errorCode,
            )
        }

        private fun Any?.asDouble(): Double? = when (this) {
            is Number -> toDouble()
            is String -> toDoubleOrNull()
            else -> null
        }

        private fun Any?.asInt(): Int? = asDouble()?.toInt()
    }
}

data class AcDevice(val id: String, val name: String, val state: AcState)

/** AWS IoT credentials handed out by the Blue Star login API. */
data class IotCredentials(val endpoint: String, val accessKey: String, val secretKey: String) {
    /** e.g. "abc-ats.iot.ap-south-1.amazonaws.com" -> "ap-south-1". */
    val region: String
        get() = Regex("\\.iot\\.([a-z0-9-]+)\\.amazonaws\\.com").find(endpoint)?.groupValues?.get(1)
            ?: "ap-south-1"

    companion object {
        /** The login response field `mi` is base64("endpoint::accessKey::secretKey"). */
        fun fromMi(mi: String?): IotCredentials? {
            if (mi.isNullOrBlank()) return null
            val decoded = try {
                String(Base64.getDecoder().decode(mi.trim()), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val parts = decoded.split("::")
            if (parts.size < 3 || parts.take(3).any { it.isBlank() }) return null
            return IotCredentials(parts[0].trim(), parts[1].trim(), parts[2].trim())
        }
    }
}

/** Fault codes as shown on the indoor unit's display (table from the Blue Star app's errors.json). */
object AcErrors {
    private val CODES = mapOf(
        1 to "L1", 2 to "L2", 3 to "DH", 4 to "DL", 5 to "HE", 6 to "E5", 7 to "HP", 8 to "PC",
        9 to "P3", 10 to "IP", 11 to "LO", 12 to "P8", 13 to "L3", 14 to "F5", 15 to "F4", 16 to "F3",
        17 to "E4", 18 to "E1", 19 to "EC", 20 to "H6", 21 to "E6", 22 to "F1", 23 to "F2", 24 to "E2",
        25 to "EE", 26 to "E0", 27 to "LC", 28 to "P7", 29 to "P5", 30 to "PH", 31 to "P4", 32 to "E8",
        33 to "L4", 48 to "U7", 49 to "U8", 50 to "E3", 51 to "E7",
    )

    /** null when there is no fault. */
    fun label(code: Int): String? = if (code == 0) null else CODES[code] ?: "#$code"
}
