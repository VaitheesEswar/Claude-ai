package com.acremote.bluestar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.acremote.bluestar.core.AcCommands
import com.acremote.bluestar.core.AcDevice
import com.acremote.bluestar.core.AcErrors
import com.acremote.bluestar.core.AcMode
import com.acremote.bluestar.core.AcState
import com.acremote.bluestar.core.FanSpeed
import com.acremote.bluestar.core.Swing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val POLL_INTERVAL_MS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(state: UiState.Ready, vm: RemoteViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Poll the AC's status while the app is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh(manual = false)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        DiagnosticsScreen(vm, onBack = { showDiagnostics = false })
        return
    }

    val device = state.selected
    val ac = device?.state
    val accent by animateColorAsState(
        modeColor(ac?.mode ?: AcMode.COOL, ac?.power == true),
        animationSpec = tween(500),
        label = "accent",
    )

    // Soft wash of the mode colour behind everything.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent))),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { DeviceTitle(state, onSelect = vm::selectDevice) },
                    actions = {
                        IconButton(onClick = { vm.refresh(manual = true) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Diagnostics") },
                                    onClick = { menuOpen = false; showDiagnostics = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out (${state.userName})") },
                                    onClick = { menuOpen = false; vm.signOut() },
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (device == null) {
                NoDevices(Modifier.padding(padding))
            } else {
                RemoteBody(state, device, accent, vm, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun DeviceTitle(state: UiState.Ready, onSelect: (String) -> Unit) {
    val name = state.selected?.name ?: "AC Remote"
    if (state.devices.size <= 1) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose AC")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.devices.forEach { d ->
                DropdownMenuItem(text = { Text(d.name) }, onClick = { open = false; onSelect(d.id) })
            }
        }
    }
}

@Composable
private fun NoDevices(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No ACs on this account", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add your AC in the Blue Star Smart app first, then tap refresh.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The handset: an LCD panel on top and a grid of round buttons, like a real AC remote. */
@Composable
private fun RemoteBody(state: UiState.Ready, device: AcDevice, accent: Color, vm: RemoteViewModel, modifier: Modifier) {
    val ac = device.state
    val on = ac.power
    val fanFixed = AcCommands.forcedFan(ac.mode) != null

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
        state.cloudError?.let { Banner(it) }
        if (!ac.connected) {
            Banner("Blue Star's cloud says ${device.name} is offline. You can still try the buttons; if nothing happens, check the AC's Wi-Fi.")
        }
        AcErrors.label(ac.errorCode)?.let { Banner("The AC reports fault code $it") }

        Surface(
            shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp, bottomStart = 40.dp, bottomEnd = 40.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LcdPanel(ac, accent)

                // Row 1: MODE · POWER · FAN
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteKey(AcIcons.forMode(ac.mode), "MODE", accent, enabled = on) {
                        vm.setMode(ac.mode.next())
                    }
                    PowerKey(on) { vm.togglePower() }
                    RemoteKey(AcIcons.Fan, "FAN", accent, enabled = on && !fanFixed) {
                        vm.setFan(ac.fan.next())
                    }
                }

                // Row 2: TEMP ▼ · SWING · TEMP ▲
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteKey(AcIcons.Down, "TEMP −", accent, enabled = on && ac.targetTemp > AcCommands.MIN_TEMP) {
                        vm.changeTemperature(-1)
                    }
                    RemoteKey(AcIcons.Swing, "SWING", accent, enabled = on) {
                        vm.setSwing(ac.swing.next())
                    }
                    RemoteKey(AcIcons.Up, "TEMP +", accent, enabled = on && ac.targetTemp < AcCommands.MAX_TEMP) {
                        vm.changeTemperature(+1)
                    }
                }

                Text(
                    when {
                        !on -> "Press the power button to switch on"
                        fanFixed -> "Fan speed is automatic in ${ac.mode.label} mode"
                        else -> "MODE, FAN and SWING step to the next setting on each press"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Text(
                    "BLUE STAR · SMART",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** LCD-style display: mode, Wi-Fi, big set temperature, fan level, swing and room temperature. */
@Composable
private fun LcdPanel(ac: AcState, accent: Color) {
    val lcdText = Color(0xFFE8F4FF)
    val dim = lcdText.copy(alpha = 0.6f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0D1B2A), accent.copy(alpha = if (ac.power) 0.55f else 0.2f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Top: mode + connection
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AcIcons.forMode(ac.mode), contentDescription = null, tint = lcdText, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(ac.mode.label.uppercase(), color = lcdText, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                if (ac.connected) AcIcons.Wifi else AcIcons.WifiOff,
                contentDescription = if (ac.connected) "Online" else "Offline",
                tint = if (ac.connected) lcdText else Color(0xFFFF8A80),
                modifier = Modifier.size(20.dp),
            )
        }

        // Middle: big temperature, or OFF
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = if (ac.power) ac.targetTemp.roundToInt() else null,
                transitionSpec = {
                    val up = (targetState ?: 0) > (initialState ?: 0)
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                        (slideOutVertically { if (up) -it else it } + fadeOut())
                },
                label = "temp",
            ) { temp ->
                if (temp == null) {
                    Text("OFF", color = dim, fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("$temp", color = lcdText, fontSize = 96.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
                        Text("°C", color = lcdText, fontSize = 24.sp, modifier = Modifier.padding(top = 18.dp))
                    }
                }
            }
        }

        // Bottom: fan level · swing · room temperature
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AcIcons.Fan, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            FanLevel(ac.fan, lcdText)
            Spacer(Modifier.width(16.dp))
            Icon(AcIcons.Swing, contentDescription = null, tint = dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(swingText(ac.swing), color = lcdText, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            ac.roomTemp?.let {
                Text("ROOM ${formatDegrees(it)}", color = dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun FanLevel(fan: FanSpeed, color: Color) {
    if (fan == FanSpeed.AUTO) {
        Text("AUTO", color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        return
    }
    val level = when (fan) {
        FanSpeed.LOW -> 1
        FanSpeed.MEDIUM -> 2
        FanSpeed.HIGH -> 3
        else -> 4
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = "Fan ${fan.label}" },
    ) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) color else color.copy(alpha = 0.2f)),
            )
        }
        if (fan == FanSpeed.TURBO) {
            Text(" TURBO", color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Round remote button with an icon and a printed label under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteKey(
    icon: ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    size: Dp = 68.dp,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = if (enabled) 3.dp else 0.dp,
            modifier = Modifier.size(size).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

/** Big red power button, like on a real remote. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerKey(on: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val color by animateColorAsState(if (on) Color(0xFFE53935) else Color(0xFFC62828), label = "power")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            shape = CircleShape,
            color = color,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(88.dp)
                .semantics { contentDescription = if (on) "Turn off" else "Turn on" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AcIcons.Power, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (on) "ON" else "OFF",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (on) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Banner(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun AcMode.next(): AcMode = AcMode.entries[(ordinal + 1) % AcMode.entries.size]
private fun FanSpeed.next(): FanSpeed = FanSpeed.entries[(ordinal + 1) % FanSpeed.entries.size]
private fun Swing.next(): Swing = Swing.entries[(ordinal + 1) % Swing.entries.size]

private fun swingText(s: Swing): String = when (s) {
    Swing.OFF -> "FIXED"
    Swing.AUTO -> "SWING"
    else -> "POS ${s.label}"
}

private fun formatDegrees(t: Double): String =
    if (t == t.roundToInt().toDouble()) "${t.roundToInt()}°" else "${"%.1f".format(t)}°"
