package com.acremote.bluestar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    Scaffold(
        topBar = {
            TopAppBar(
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
        val device = state.selected
        if (device == null) {
            NoDevices(Modifier.padding(padding))
        } else {
            Controls(state, device, vm, Modifier.padding(padding))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: UiState.Ready, device: AcDevice, vm: RemoteViewModel, modifier: Modifier) {
    val ac = device.state
    // The cloud's "connected" flag can be wrong, so it never disables the controls.
    val active = ac.power

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.cloudError?.let { Banner(it) }
        if (!ac.connected) {
            Banner("Blue Star's cloud says ${device.name} is offline. You can still try the controls; if nothing happens, check the AC's Wi-Fi.")
        }
        AcErrors.label(ac.errorCode)?.let { Banner("The AC reports fault code $it") }

        StatusLine(ac)
        TemperatureCard(ac, enabled = active, onChange = vm::changeTemperature)

        Button(
            onClick = vm::togglePower,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = if (ac.power) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (ac.power) "Turn off" else "Turn on", fontSize = 18.sp)
        }

        Section("Mode") {
            val modes = AcMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = ac.mode == mode,
                        onClick = { vm.setMode(mode) },
                        enabled = active,
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                        icon = {},
                    ) { Text(mode.label) }
                }
            }
        }

        Section("Fan speed") {
            val fixed = AcCommands.forcedFan(ac.mode) != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FanSpeed.entries.forEach { fan ->
                    FilterChip(
                        selected = ac.fan == fan,
                        onClick = { vm.setFan(fan) },
                        enabled = active && !fixed,
                        label = { Text(fan.label) },
                    )
                }
            }
            if (fixed) {
                Text(
                    "Fan speed is set automatically in ${ac.mode.label} mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Vertical swing") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swing.entries.forEach { swing ->
                    FilterChip(
                        selected = ac.swing == swing,
                        onClick = { vm.setSwing(swing) },
                        enabled = active,
                        label = { Text(if (swing.label.length == 1) "Angle ${swing.label}" else swing.label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusLine(ac: AcState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (ac.connected) "● Online" else "● Cloud says offline",
            color = if (ac.connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
        ac.roomTemp?.let {
            Text("Room ${formatDegrees(it)}", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            if (ac.power) "Running · ${ac.mode.label}" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TemperatureCard(ac: AcState, enabled: Boolean, onChange: (Int) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundButton("−", "Lower temperature", enabled && ac.targetTemp > AcCommands.MIN_TEMP) { onChange(-1) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatDegrees(ac.targetTemp),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Light,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text("Set temperature", style = MaterialTheme.typography.labelMedium)
            }
            RoundButton("+", "Raise temperature", enabled && ac.targetTemp < AcCommands.MAX_TEMP) { onChange(+1) }
        }
    }
}

@Composable
private fun RoundButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(64.dp).semantics { contentDescription = description },
    ) {
        Text(symbol, fontSize = 30.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
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

private fun formatDegrees(t: Double): String =
    if (t == t.roundToInt().toDouble()) "${t.roundToInt()}°" else "${"%.1f".format(t)}°"
