package com.acremote.bluestar.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcProtocolTest {

    @Test
    fun parsesReportedState() {
        val s = AcState.fromReported(
            mapOf("pow" to 1, "mode" to 3, "stemp" to "23.0", "ctemp" to 28.5, "fspd" to 4, "vswing" to 0, "err" to 18),
            connected = true,
        )
        assertEquals(AcState(true, true, AcMode.DRY, 23.0, 28.5, FanSpeed.HIGH, Swing.AUTO, 18), s)
    }

    @Test
    fun partialReportKeepsPreviousValues() {
        val prev = AcState(power = true, mode = AcMode.COOL, targetTemp = 22.0, fan = FanSpeed.LOW)
        val s = AcState.fromReported(mapOf("stemp" to 25, "mode" to mapOf("value" to 4)), connected = false, previous = prev)
        assertEquals(prev.copy(connected = false, targetTemp = 25.0, mode = AcMode.AUTO), s)
    }

    @Test
    fun unknownCodesFallBack() {
        val s = AcState.fromReported(mapOf("mode" to 99, "fspd" to "x"), connected = true)
        assertEquals(AcMode.COOL, s.mode)
        assertEquals(FanSpeed.AUTO, s.fan)
    }

    @Test
    fun powerOffCommand() {
        val c = AcCommands.powerOff(AcState(power = true))
        assertEquals(mapOf("pow" to 0, "lcmd" to "pow"), c.desired)
        assertFalse(c.expected.power)
    }

    @Test
    fun powerOnResendsCurrentMode() {
        val c = AcCommands.powerOn(AcState(power = false, mode = AcMode.COOL, targetTemp = 24.0, fan = FanSpeed.HIGH))
        assertEquals(
            mapOf("pow" to 1, "mode" to mapOf("value" to 2, "stemp" to "24.0", "fspd" to 4), "lcmd" to "mode"),
            c.desired,
        )
        assertTrue(c.expected.power)
    }

    @Test
    fun dryAndAutoForceFanSpeed() {
        val base = AcState(fan = FanSpeed.HIGH)
        val dry = AcCommands.setMode(base, AcMode.DRY)
        assertEquals(2, (dry.desired["mode"] as Map<*, *>)["fspd"])
        assertEquals(FanSpeed.LOW, dry.expected.fan)
        val auto = AcCommands.setMode(base, AcMode.AUTO)
        assertEquals(7, (auto.desired["mode"] as Map<*, *>)["fspd"])
    }

    @Test
    fun temperatureIsClampedAndRounded() {
        assertEquals("16.0", AcCommands.setTemperature(AcState(), 10.0).desired["stemp"])
        assertEquals("30.0", AcCommands.setTemperature(AcState(), 35.0).desired["stemp"])
        assertEquals(25.0, AcCommands.setTemperature(AcState(), 24.6).expected.targetTemp)
    }

    @Test
    fun shadowPayloadShape() {
        val json = AcCommands.shadowPayload(mapOf("fspd" to 3, "lcmd" to "fspd"), nowMillis = 1700000000000)
        assertEquals("""{"state":{"desired":{"fspd":3,"lcmd":"fspd","src":"anmq","ts":1700000000000}}}""", json)
    }

    @Test
    fun jsonEscaping() {
        assertEquals("""{"a":"x\"y\\z\n","b":[1,true,null]}""", MiniJson.encode(mapOf("a" to "x\"y\\z\n", "b" to listOf(1, true, null))))
    }

    @Test
    fun decodesIotCredentials() {
        val mi = Base64.getEncoder().encodeToString("abc-ats.iot.ap-south-1.amazonaws.com::AKID::SECRET".toByteArray())
        val c = IotCredentials.fromMi(mi)!!
        assertEquals("abc-ats.iot.ap-south-1.amazonaws.com", c.endpoint)
        assertEquals("AKID", c.accessKey)
        assertEquals("SECRET", c.secretKey)
        assertEquals("ap-south-1", c.region)
        assertNull(IotCredentials.fromMi("not base64!"))
        assertNull(IotCredentials.fromMi(null))
    }
}

class AcErrorsTest {
    @Test
    fun labels() {
        assertNull(AcErrors.label(0))
        assertEquals("E1", AcErrors.label(18))
        assertEquals("#99", AcErrors.label(99))
    }
}
