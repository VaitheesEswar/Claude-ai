package com.acremote.bluestar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.acremote.bluestar.core.AcTimer
import com.acremote.bluestar.core.TimerAction
import com.acremote.bluestar.core.TimerMath
import java.util.Calendar
import java.util.Date

/** Timer options for the selected AC, plus the list of its pending timers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerDialog(
    timers: List<AcTimer>,
    onOffIn: (minutes: Int) -> Unit,
    onAt: (action: TimerAction, hour: Int, minute: Int, daily: Boolean) -> Unit,
    onCancel: (id: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Notifications tell you when a timer ran (or failed); ask once when a timer is set.
    val ensureNotifications = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var pickFor by remember { mutableStateOf<TimerAction?>(null) }

    pickFor?.let { action ->
        TimePickDialog(
            title = if (action == TimerAction.OFF) "Turn off at" else "Turn on at",
            onConfirm = { h, m, daily ->
                ensureNotifications()
                onAt(action, h, m, daily)
                pickFor = null
                onDismiss()
            },
            onDismiss = { pickFor = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Turn off in", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 180, 240, 360, 480).forEach { min ->
                        AssistChip(
                            onClick = { ensureNotifications(); onOffIn(min); onDismiss() },
                            label = { Text(TimerMath.formatRemaining(min * 60_000L)) },
                        )
                    }
                }
                OutlinedButton(onClick = { pickFor = TimerAction.OFF }, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn off at a time…")
                }
                OutlinedButton(onClick = { pickFor = TimerAction.ON }, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on at a time…")
                }

                if (timers.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Active timers", style = MaterialTheme.typography.titleSmall)
                    timers.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(describe(t, context), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onCancel(t.id) }) { Text("Cancel") }
                        }
                    }
                }
                Text(
                    "Your phone sends the command at the set time, even if the app is closed. It needs internet then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(title: String, onConfirm: (Int, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = remember { Calendar.getInstance() }
    val state = rememberTimePickerState(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = now.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context),
    )
    var daily by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(
                    Modifier.fillMaxWidth().clickable { daily = !daily },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = daily, onCheckedChange = { daily = it })
                    Text("Every day")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute, daily) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Off in 1h 20m (06:00)" / "On at 22:30 · every day". */
fun describe(t: AcTimer, context: android.content.Context): String {
    val time = DateFormat.getTimeFormat(context).format(Date(t.triggerAtMillis))
    val verb = if (t.action == TimerAction.OFF) "Off" else "On"
    return if (t.repeatsDaily) {
        "$verb at $time · every day"
    } else {
        val left = TimerMath.formatRemaining(t.triggerAtMillis - System.currentTimeMillis())
        "$verb in $left ($time)"
    }
}
