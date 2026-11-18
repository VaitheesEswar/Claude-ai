package com.acremote.bluestar.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.acremote.bluestar.core.AcMode

/** Small line icons drawn in code, so the app doesn't need the large extended icon set. */
object AcIcons {
    private fun lineIcon(name: String, path: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val Power = lineIcon("power", "M12,3 L12,11 M7,5.8 A8,8 0 1,0 17,5.8", 2.4f)

    val Snowflake = lineIcon(
        "cool",
        "M12,2 L12,22 M3.3,7 L20.7,17 M3.3,17 L20.7,7 " +
            "M9.5,3.8 L12,6.3 L14.5,3.8 M9.5,20.2 L12,17.7 L14.5,20.2 " +
            "M3.6,10.2 L6.8,9.3 L5.9,6.1 M20.4,13.8 L17.2,14.7 L18.1,17.9 " +
            "M3.6,13.8 L6.8,14.7 L5.9,17.9 M20.4,10.2 L17.2,9.3 L18.1,6.1",
        1.8f,
    )

    val Droplet = lineIcon("dry", "M12,3 C12,3 5.5,10.5 5.5,14.5 A6.5,6.5 0 0,0 18.5,14.5 C18.5,10.5 12,3 12,3 Z M9,15 A3,3 0 0,0 12,18")

    val Fan = lineIcon(
        "fan",
        "M12,10 C10,6 10.5,3 13.5,3 C16.5,3 16,7.5 12,10 Z " +
            "M14,12 C18,10 21,10.5 21,13.5 C21,16.5 16.5,16 14,12 Z " +
            "M12,14 C14,18 13.5,21 10.5,21 C7.5,21 8,16.5 12,14 Z " +
            "M10,12 C6,14 3,13.5 3,10.5 C3,7.5 7.5,8 10,12 Z",
        1.6f,
    )

    val Auto = lineIcon("auto", "M6.5,19 L12,5 L17.5,19 M8.6,14 L15.4,14")

    val Swing = lineIcon(
        "swing",
        "M4,7 C8,3.5 16,3.5 20,7 M17,3.5 L20,7 L16.2,7.8 " +
            "M4,13 L20,13 M4,19 C8,15.5 16,15.5 20,19",
    )

    val Wifi = lineIcon("wifi", "M2.5,9 A14,14 0 0,1 21.5,9 M5.5,12.5 A9.5,9.5 0 0,1 18.5,12.5 M8.7,16 A4.8,4.8 0 0,1 15.3,16 M12,19.6 L12,19.7", 2.2f)

    val WifiOff = lineIcon("wifi_off", "M5.5,12.5 A9.5,9.5 0 0,1 18.5,12.5 M8.7,16 A4.8,4.8 0 0,1 15.3,16 M12,19.6 L12,19.7 M3,3 L21,21", 2.2f)

    val Up = lineIcon("up", "M6,15 L12,9 L18,15", 2.6f)
    val Down = lineIcon("down", "M6,9 L12,15 L18,9", 2.6f)

    fun forMode(mode: AcMode): ImageVector = when (mode) {
        AcMode.COOL -> Snowflake
        AcMode.DRY -> Droplet
        AcMode.FAN -> Fan
        AcMode.AUTO -> Auto
    }
}

/** Accent colour per mode; grey when the AC is off. */
fun modeColor(mode: AcMode, on: Boolean): Color = if (!on) Color(0xFF78909C) else when (mode) {
    AcMode.COOL -> Color(0xFF2196F3)
    AcMode.DRY -> Color(0xFF26A69A)
    AcMode.FAN -> Color(0xFF66BB6A)
    AcMode.AUTO -> Color(0xFFAB47BC)
}
