package com.smartwatering.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartwatering.app.data.CardControl
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DateTimeRangeAction(
    control: CardControl,
    pending: Boolean,
    onInvoke: (Any?, ((ActionSubmissionResult) -> Unit)?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true }, enabled = control.enabled && !pending,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(control.label) }
    if (!open) return

    val zone = remember { ZoneId.systemDefault() }
    val now = remember { LocalDateTime.now(zone).withSecond(0).withNano(0) }
    var start by remember { mutableStateOf(now.minusHours(1)) }
    var end by remember { mutableStateOf(now) }
    var response by remember { mutableStateOf<ActionSubmissionResult?>(null) }
    // Reject times skipped by a daylight-saving transition instead of silently shifting them.
    val validTimes = zone.rules.getValidOffsets(start).isNotEmpty() &&
        zone.rules.getValidOffsets(end).isNotEmpty()
    val ordered = start.atZone(zone).toInstant().isBefore(end.atZone(zone).toInstant())
    val inPast = !end.atZone(zone).toInstant().isAfter(java.time.Instant.now())
    AlertDialog(
        onDismissRequest = { if (!pending) open = false },
        title = { Text(control.label) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Time zone: ${zone.id}")
                DateTimeBoundary("From", start, !pending) { start = it; response = null }
                DateTimeBoundary("To", end, !pending) { end = it; response = null }
                if (!validTimes) Text("This time does not exist due to a daylight-saving change")
                else if (!ordered) Text("The end must be after the start")
                else if (!inPast) Text("The end cannot be in the future")
                Text("Uses the nearest measurements to the start and end of the period.")
                if (pending) CircularProgressIndicator(Modifier.size(24.dp))
                response?.let { result ->
                    if (result.successful) {
                        Text(result.result?.get("message") as? String ?: "The response contains no result")
                        listOf("start_sample_at" to "Start measurement", "end_sample_at" to "End measurement").forEach { (field, label) ->
                            (result.result?.get(field) as? Number)?.let { timestamp ->
                                val dateTime = java.time.Instant.ofEpochMilli(
                                    (timestamp.toDouble() * 1000).toLong()
                                ).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss XXX"))
                                Text("$label: $dateTime")
                            }
                        }
                    } else {
                        Text(result.error ?: "Request failed", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = control.enabled && !pending && validTimes && ordered && inPast,
                onClick = {
                    response = null
                    onInvoke(mapOf(
                        "start" to start.atZone(zone).toInstant().toString(),
                        "end" to end.atZone(zone).toInstant().toString(),
                    )) { response = it }
                },
            ) { Text("Request") }
        },
        dismissButton = {
            TextButton(onClick = { open = false }, enabled = !pending) { Text("Close") }
        },
    )
}

@Composable
private fun DateTimeBoundary(
    label: String,
    value: LocalDateTime,
    enabled: Boolean,
    onChange: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    onChange(value.with(java.time.LocalDate.of(year, month + 1, day)))
                }, value.year, value.monthValue - 1, value.dayOfMonth).show()
            },
        ) { Text(value.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
        OutlinedButton(
            enabled = enabled,
            onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    onChange(value.withHour(hour).withMinute(minute))
                }, value.hour, value.minute, true).show()
            },
        ) { Text(value.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    }
}
