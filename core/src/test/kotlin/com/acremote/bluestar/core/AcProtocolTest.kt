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

class RedactTest {
    @Test
    fun masksSecretsKeepsState() {
        val out = Redact.tree(
            mapOf(
                "session" to "abc", "mi" to "xyz", "user" to mapOf("email" to "a@b.c", "name" to "V"),
                "things" to listOf(mapOf("thing_id" to "T1", "reported" to mapOf("pow" to 1))),
            )
        )
        assertEquals(
            mapOf(
                "session" to "***", "mi" to "***", "user" to mapOf("email" to "***", "name" to "V"),
                "things" to listOf(mapOf("thing_id" to "T1", "reported" to mapOf("pow" to 1))),
            ),
            out,
        )
    }
}

class ExtraFeaturesTest {
    @Test
    fun parsesExtraFields() {
        val s = AcState.fromReported(
            mapOf("hswing" to 0, "sleep" to 1, "turbo" to 3, "eco" to mapOf("value" to 2), "display" to 0, "s_clean" to 1, "m_buz" to 0),
            connected = true,
        )
        assertTrue(s.hSwing); assertTrue(s.sleep); assertTrue(s.turbo)
        assertEquals(EcoLevel.P80, s.eco)
        assertFalse(s.display); assertTrue(s.selfClean); assertFalse(s.buzzer)
    }

    @Test
    fun extraDefaultsWhenMissing() {
        val s = AcState.fromReported(mapOf("hswing" to 6, "eco" to 0), connected = true)
        assertFalse(s.hSwing); assertFalse(s.sleep); assertEquals(EcoLevel.OFF, s.eco)
        assertTrue(s.display); assertTrue(s.buzzer)
    }

    @Test
    fun extraCommands() {
        val st = AcState(fan = FanSpeed.MEDIUM)
        assertEquals(mapOf("hswing" to 0, "lcmd" to "hswing"), AcCommands.setHSwing(st, true).desired)
        assertEquals(mapOf("hswing" to 6, "lcmd" to "hswing"), AcCommands.setHSwing(st, false).desired)
        assertEquals(mapOf("sleep" to 1, "lcmd" to "sleep"), AcCommands.setSleep(st, true).desired)
        assertEquals(mapOf("turbo" to 3, "lcmd" to "turbo"), AcCommands.setTurbo(st, true).desired)
        assertEquals(mapOf("eco" to mapOf("value" to 5, "fspd" to 3), "lcmd" to "eco"), AcCommands.setEco(st, EcoLevel.P110).desired)
        assertEquals(mapOf("display" to 0, "lcmd" to "display"), AcCommands.setDisplay(st, false).desired)
        assertEquals(mapOf("s_clean" to 1, "lcmd" to "s_clean"), AcCommands.setSelfClean(st, true).desired)
        assertEquals(mapOf("m_buz" to 0, "lcmd" to "m_buz"), AcCommands.setBuzzer(st, false).desired)
    }
}
