package com.acremote.bluestar.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds "desired" shadow updates in the exact shape the Blue Star firmware
 * expects (every command carries `lcmd`, the name of the last changed field).
 */
object AcCommands {
    const val MIN_TEMP = 16.0
    const val MAX_TEMP = 30.0

    /** [desired] goes to the device; [expected] is the optimistic state to show meanwhile. */
    data class Command(val desired: Map<String, Any>, val expected: AcState)

    fun powerOff(s: AcState) = Command(mapOf("pow" to 0, "lcmd" to "pow"), s.copy(power = false))

    /** Turning on re-sends the current mode, which is how the official app does it. */
    fun powerOn(s: AcState) = setMode(s, s.mode)

    fun setMode(s: AcState, mode: AcMode): Command {
        val fan = forcedFan(mode) ?: s.fan
        val desired = mapOf(
            "pow" to 1,
            "mode" to mapOf("value" to mode.code, "stemp" to formatTemp(s.targetTemp), "fspd" to fan.code),
            "lcmd" to "mode",
        )
        return Command(desired, s.copy(power = true, mode = mode, fan = fan))
    }

    fun setTemperature(s: AcState, temp: Double): Command {
        val t = temp.coerceIn(MIN_TEMP, MAX_TEMP).roundToInt().toDouble()
        return Command(mapOf("stemp" to formatTemp(t), "lcmd" to "stemp"), s.copy(targetTemp = t))
    }

    fun setFan(s: AcState, fan: FanSpeed) =
        Command(mapOf("fspd" to fan.code, "lcmd" to "fspd"), s.copy(fan = fan))

    fun setSwing(s: AcState, swing: Swing) =
        Command(mapOf("vswing" to swing.code, "lcmd" to "vswing"), s.copy(swing = swing))

    /** Dry runs at low fan and Auto at auto fan; the firmware rejects anything else. */
    fun forcedFan(mode: AcMode): FanSpeed? = when (mode) {
        AcMode.DRY -> FanSpeed.LOW
        AcMode.AUTO -> FanSpeed.AUTO
        else -> null
    }

    /** Full JSON body for the shadow update endpoint. */
    fun shadowPayload(desired: Map<String, Any>, nowMillis: Long = System.currentTimeMillis()): String =
        MiniJson.encode(mapOf("state" to mapOf("desired" to desired + mapOf("src" to "anmq", "ts" to nowMillis))))

    fun formatTemp(t: Double): String = String.format(Locale.US, "%.1f", t)
}
