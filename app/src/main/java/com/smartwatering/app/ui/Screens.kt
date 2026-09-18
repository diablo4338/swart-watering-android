package com.smartwatering.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.smartwatering.app.BuildConfig
import com.smartwatering.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_BLOCK_POLL_INTERVAL_MS = 5000L
private const val STALE_SNAPSHOT_AGE_SECONDS = 24 * 60 * 60L

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleConfigured = BuildConfig.SMART_WATERING_GOOGLE_WEB_CLIENT_ID.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        VersionInfoButton(viewModel, Modifier.align(Alignment.TopEnd).padding(12.dp))
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Smart Watering", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            if (isLoading) CircularProgressIndicator() else {
                if (BuildConfig.DEBUG) {
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.login(username, password) },
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Login") }
                    Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try { viewModel.loginWithGoogle(requestGoogleIdToken(context)) }
                            catch (_: GetCredentialCancellationException) { viewModel.showLoginError("Google login cancelled") }
                            catch (_: NoCredentialException) { viewModel.showLoginError("No Google account is available") }
                            catch (e: GetCredentialException) { viewModel.showLoginError("Google login failed: ${e.message}") }
                            catch (_: GoogleIdTokenParsingException) { viewModel.showLoginError("Invalid Google ID token") }
                            catch (e: Exception) { viewModel.showLoginError("Google login failed: ${e.message}") }
                        }
                    },
                    enabled = googleConfigured,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in with Google") }
            }
            error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private suspend fun requestGoogleIdToken(context: Context): String {
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.SMART_WATERING_GOOGLE_WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    error("Unsupported Google credential")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(viewModel: MainViewModel, showBackendUnavailable: Boolean = false) {
    val devices by viewModel.devices.collectAsState()
    val selected by viewModel.selectedDeviceId.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val pendingActions by viewModel.pendingActions.collectAsState()
    val loadingBlocks by viewModel.loadingBlocks.collectAsState()
    val loading by viewModel.isDevicesLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    if (showBackendUnavailable) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                actions = {
                    val refreshActionId = selected?.let { "$it:refresh_card" }
                    val refreshAvailable = selected?.let { deviceId ->
                        cards[deviceId]?.card?.blocks
                            ?.firstOrNull { it.kind == "device_overview" }
                            ?.actions?.any { it.id == "refresh_card" && it.enabled }
                    } == true
                    IconButton(
                        onClick = viewModel::refreshActiveCard,
                        enabled = refreshAvailable && refreshActionId !in pendingActions,
                    ) {
                        if (refreshActionId in pendingActions) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh card")
                        }
                    }
                    VersionInfoButton(viewModel)
                    IconButton(onClick = viewModel::logout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                    }
                },
            )
        },
    ) { padding ->
        when {
            devices.isEmpty() && loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            devices.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(error ?: "No devices") }
            else -> {
                val initial = devices.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = initial, pageCount = { devices.size })
                LaunchedEffect(pager.currentPage, devices) {
                    viewModel.setActiveDevice(devices.getOrNull(pager.currentPage))
                }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    error?.let { ErrorBanner(it) }
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.weight(1f),
                        key = { devices[it].id },
                    ) { page ->
                        val device = devices[page]
                        DeviceCardPage(
                            device = device,
                            state = cards[device.id] ?: CardUiState(),
                            pendingActions = pendingActions,
                            loadingBlocks = loadingBlocks,
                            onRefresh = { viewModel.refreshCard(device) },
                            onOpenBlock = { blockId -> viewModel.setOpenBlock(device.id, blockId) },
                            isActive = device.id == selected,
                            onLoadStatistics = { block -> viewModel.loadStatisticsBlock(device.id, block) },
                            onAction = viewModel::performAction,
                        )
                    }
                    Text(
                        "${pager.currentPage + 1} / ${devices.size}",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(message) { focusRequester.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.focusRequester(focusRequester).focusable(),
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DeviceCardPage(
    device: Device,
    state: CardUiState,
    pendingActions: Set<String>,
    loadingBlocks: Set<String>,
    onRefresh: () -> Unit,
    onOpenBlock: (String?) -> Unit,
    isActive: Boolean,
    onLoadStatistics: suspend (CardBlock) -> CardBlock,
    onAction: CardActionHandler,
) {
    Card(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        when {
            state.card == null && state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.card == null -> Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error ?: "Card is unavailable", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onRefresh) { Text("Retry") }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val blocks = state.card.blocks
                val overview = blocks.firstOrNull { it.kind == "device_overview" }
                val operationQueue = blocks.firstOrNull { it.kind == "operation_queue" }
                val analysis = blocks.firstOrNull { it.kind == "consumption_analysis" }
                val menuBlocks = blocks.filter { it.slot in setOf("control", "watering_parameters", "history") }
                val inlineBlocks = blocks.filterNot { it == overview || it == operationQueue || it == analysis || it in menuBlocks }
                var openBlockId by remember(device.id) { mutableStateOf<String?>(null) }

                state.error?.let { ErrorBanner(it) }
                overview?.let { OverviewHeader(it) }
                overview?.let { OverviewValue(it) }
                menuBlocks.forEach { block ->
                    OutlinedButton(
                        onClick = {
                            openBlockId = if (openBlockId == block.id) null else block.id
                            onOpenBlock(openBlockId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (openBlockId == block.id) {
                            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text(block.title ?: block.id) }
                    block.actions.forEach { action ->
                        key(device.id, block.id, action.id) {
                            val actionKey = "${device.id}:${block.id}:${action.id}"
                            ActionControl(
                                control = action,
                                currentValue = action.value,
                                pending = actionKey in pendingActions,
                                onInvoke = { value, onComplete ->
                                    action.request?.let { request ->
                                        onAction(actionKey, request, emptyMap(), value, onComplete)
                                    } ?: onComplete?.invoke(
                                        ActionSubmissionResult(false, "Action request is missing")
                                    )
                                },
                            )
                        }
                    }
                }
                menuBlocks.firstOrNull { it.id == openBlockId }?.let { block ->
                    key(block.id) {
                        if ("${device.id}:${block.id}" in loadingBlocks) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            CardBlockRenderer(device.id, block, pendingActions, onAction)
                        }
                    }
                }
                operationQueue?.let { block ->
                    key(block.id) { CardBlockRenderer(device.id, block, pendingActions, onAction) }
                }
                if (overview != null && analysis != null) {
                    StatisticsSwitcher(device.id, overview, analysis, isActive, onLoadStatistics)
                } else {
                    overview?.let { OverviewStatistics(it) }
                }
                inlineBlocks.forEach { block ->
                    key(block.id) { CardBlockRenderer(device.id, block, pendingActions, onAction) }
                }
            }
        }
    }
}

@Composable
private fun StatisticsSwitcher(
    deviceId: String,
    overview: CardBlock,
    analysis: CardBlock,
    isActive: Boolean,
    onLoad: suspend (CardBlock) -> CardBlock,
) {
    var diagnostic by rememberSaveable(deviceId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !diagnostic, onClick = { diagnostic = false }, label = { Text("short") })
            FilterChip(selected = diagnostic, onClick = { diagnostic = true }, label = { Text("diagnostic") })
        }
        // Recreate content and cancel the previous request on every mode change.
        key(deviceId, diagnostic) {
            StatisticsContent(if (diagnostic) analysis else overview, isActive, onLoad)
        }
    }
}

@Composable
private fun StatisticsContent(
    requestedBlock: CardBlock,
    isActive: Boolean,
    onLoad: suspend (CardBlock) -> CardBlock,
) {
    var loaded by remember { mutableStateOf<CardBlock?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val currentLoad by rememberUpdatedState(onLoad)
    LaunchedEffect(requestedBlock.id, requestedBlock.refresh.href, isActive, retry) {
        if (!isActive) return@LaunchedEffect
        loading = true
        error = null
        loaded = null
        try {
            loaded = currentLoad(requestedBlock)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Could not load statistics"
        } finally {
            loading = false
        }
    }
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> Column {
            Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("Retry") }
        }
        loaded != null -> {
            if (requestedBlock.kind == "device_overview") {
                // The ordinary overview poll keeps the short view current.
                OverviewStatistics(requestedBlock)
            } else {
                // Keep the full diagnostic response as one local snapshot.
                ConsumptionAnalysis(requireNotNull(loaded).data)
            }
        }
    }
}

@Composable
private fun CardBlockRenderer(
    deviceId: String,
    block: CardBlock,
    pendingActions: Set<String>,
    onAction: CardActionHandler,
) {
    when (block.kind) {
        "device_overview" -> {
            OverviewHeader(block)
            OverviewValue(block)
            OverviewStatistics(block)
        }
        "dynamic_form" -> DynamicFormBlock(deviceId, block, pendingActions, onAction)
        "history" -> HistoryBlock(deviceId, block, pendingActions, onAction)
        "consumption_analysis" -> ConsumptionAnalysis(block.data)
        "operation_queue" -> OperationQueueBlock(deviceId, block, pendingActions, onAction)
        "progress" -> ProgressBlock(deviceId, block, pendingActions, onAction)
        "message" -> Text(block.data["message"].asText())
        else -> if (block.required) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text("App update required: unsupported block ${block.kind}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun OperationQueueBlock(
    deviceId: String,
    block: CardBlock,
    pendingActions: Set<String>,
    onAction: CardActionHandler,
) {
    val items = block.data["items"].asList()
    BlockSurface(block.title) {
        if (items.isEmpty()) {
            Text("No active operations", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Active: ${items.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            items.forEach { raw ->
                val operation = raw.asMap()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(operation["label"].asText(), fontWeight = FontWeight.Medium)
                        operation["payload"].asText().takeIf { it.isNotBlank() }?.let { payload ->
                            Text(
                                payload,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        operation["created_at"].asNumber()?.let {
                            Text(formatTimestamp(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(operation["status"].asText().replace("_", " ").uppercase(), style = MaterialTheme.typography.labelMedium)
                    operation["actions"].asList().firstOrNull()?.asControl()?.let { action ->
                        val actionKey = "$deviceId:${block.id}:${operation["id"].asText()}:${action.id}"
                        TextButton(
                            onClick = { action.request?.let { onAction(actionKey, it, emptyMap(), null, null) } },
                            enabled = action.enabled && actionKey !in pendingActions,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            if (actionKey in pendingActions) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewHeader(block: CardBlock) {
    val status = block.data["status"].asMap()
    val workflow = block.data["workflow"].asMap()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val severity = status["severity"].asText()
            val color = when (severity) {
                "success" -> Color(0xFF2E7D32)
                "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp)); Text(status["label"].asText(), color = color)
        }
        Text(block.data["title"].asText(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(block.data["subtitle"].asText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (workflow["code"].asText() != "idle") {
            Text(workflow["label"].asText(), color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun OverviewValue(block: CardBlock) {
    val primary = block.data["primary_value"].asMap()
    val statusCode = block.data["status"].asMap()["code"].asText()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val value = primary["value"].asNumber()
        val daysToZero = primary["days_to_zero"].asNumber()?.toInt()
        val valueColor = when (primary["tone"].asText()) {
                "good" -> Color(0xFF2E7D32)
                "danger" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value?.let { "${formatNumber(it)} ${primary["unit"].asText()}" } ?: "—",
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                color = valueColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                daysToZero?.let { days -> "($days days)" } ?: "(—)",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (statusCode == "offline") {
            block.data["snapshot_at"].asNumber()?.let {
                Spacer(Modifier.height(10.dp))
                Text("Snapshot: ${formatTimestamp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isSnapshotStale(it)) {
                    Text(
                        "Error: device snapshot is more than 24 hours old",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewStatistics(block: CardBlock) {
    val statistics = block.data["statistics"].asList()
    if (statistics.isEmpty()) return
    statistics.forEach { statistic ->
        if (statistic.asMap()["kind"].asText() == "water_consumption") {
            WaterConsumption(statistic.asMap()["days"].asList())
        }
    }
}

@Composable
private fun WaterConsumption(days: List<Any?>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { raw ->
            val values = raw.asMap()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WaterConsumptionValue(
                    value = values["day"].asNumber(),
                    belowMedian = values["day_below_weekly_median"] as? Boolean ?: false,
                    normalColor = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f),
                )
                WaterConsumptionValue(
                    value = values["night"].asNumber(),
                    belowMedian = values["night_below_weekly_median"] as? Boolean ?: false,
                    normalColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConsumptionAnalysis(data: Map<String, Any?>) {
    val days = data["days"].asList()
    if (days.isEmpty()) {
        Text("No analysis available")
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        data["snapshot_label"].asText().takeIf { it.isNotBlank() }?.let {
            Text("Snapshot: $it", style = MaterialTheme.typography.labelSmall)
        }
        val timezone = days.firstOrNull().asMap()["analysis"].asMap()["timezone"].asText()
        Text("Days: 08:00–08:00 · $timezone", style = MaterialTheme.typography.labelSmall)
        days.forEach { raw ->
            val values = raw.asMap()
            key(values["date"].asText()) { ConsumptionAnalysisDay(values) }
        }
    }
}

@Composable
private fun ConsumptionAnalysisDay(day: Map<String, Any?>) {
    val date = day["date"].asText()
    val analysis = day["analysis"].asMap()
    var expanded by rememberSaveable(date) { mutableStateOf(false) }
    val average = analysis["average_rate_g_per_hour"].asNumber()
    val endpointGrams = analysis["endpoint_consumed_rounded_g"].asNumber()
    val agreement = analysis["agreement_percent"].asNumber()?.takeIf { it.isFinite() }
    val agreementColor = when {
        agreement != null && agreement >= 90 && agreement <= 105 -> Color(0xFF2E7D32)
        agreement != null && agreement >= 0 && agreement < 90 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val agreementLabel = when {
        agreement != null && agreement >= 0 && agreement <= 105 -> "${formatNumber(agreement)}%"
        else -> "ANNOR"
    }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(date, fontWeight = FontWeight.Bold)
            Text("${average?.let { "${formatNumber(it)} g/h" } ?: "—"} (${endpointGrams?.let { "${it.toLong()} g" } ?: "—"})")
        }
        Text(agreementLabel, color = agreementColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse $date" else "Expand $date",
        )
    }
    if (!expanded) return
    if (analysis.isEmpty()) {
        Text("No analysis available")
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(analysis["period_label"].asText(), style = MaterialTheme.typography.labelMedium)
        Text("Counted time: ${consumptionDuration(analysis["counted_seconds"])} / ${consumptionDuration(analysis["total_seconds"])}")
        Text("Calculated consumption: ${consumptionMetric(analysis["consumed_g"], "g")}")
        Text("Between endpoints: ${consumptionMetric(analysis["endpoint_consumed_g"], "g")}")
        Text("Endpoint time: ${consumptionDuration(analysis["sample_span_seconds"])}")
        Text("Endpoint average: ${consumptionMetric(analysis["endpoint_rate_g_per_hour"], "g/h")}")
        Text("Agreement: ${consumptionMetric(analysis["agreement_percent"], "%")}", fontWeight = FontWeight.Bold)
        Text(
            "Endpoint average ÷ calculated average × 100%. 100% means agreement; watering and missing data can change this value. Undefined when the calculated average is zero or data is missing.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf("first_sample" to "First", "last_sample" to "Last").forEach { (field, label) ->
            val sample = analysis[field].asMap()
            Text("$label: ${sample["label"].asText().ifBlank { "—" }} · ${consumptionMetric(sample["weight_g"], "g")}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Smoothed readings: ${analysis["filtered_samples"].asNumber()?.toInt() ?: 0}", style = MaterialTheme.typography.bodySmall)
        listOf("day" to "Day", "night" to "Night").forEach { (field, label) ->
            val period = day["${field}_analysis"].asMap()
            if (period.isNotEmpty()) {
                Text("$label: ${consumptionMetric(period["average_rate_g_per_hour"], "g/h")} (${consumptionMetric(period["endpoint_consumed_rounded_g"], "g")}) · ${consumptionDuration(period["counted_seconds"])}", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Calculation intervals", fontWeight = FontWeight.Bold)
        analysis["intervals"].asList().forEach { raw ->
            val interval = raw.asMap()
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(interval["label"].asText(), style = MaterialTheme.typography.labelMedium)
                Text(
                    interval["reason_label"].asText(),
                    color = if (interval["included"] == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (interval["included"] == true) {
                    Text(consumptionMetric(interval["consumed_g"], "g"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun consumptionMetric(value: Any?, unit: String): String =
    value.asNumber()?.let { "${formatNumber(it)} $unit" } ?: "—"

private fun consumptionDuration(value: Any?): String {
    val seconds = value.asNumber()?.let { kotlin.math.round(it).toLong() } ?: return "—"
    return "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

@Composable
private fun WaterConsumptionValue(
    value: Double?,
    belowMedian: Boolean,
    normalColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (value != null && value < 0) {
                Icons.Default.KeyboardArrowDown
            } else {
                Icons.Default.KeyboardArrowUp
            },
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (belowMedian) MaterialTheme.colorScheme.error else normalColor,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value?.let { "${formatNumber(kotlin.math.abs(it))} g" } ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (value == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun DynamicFormBlock(
    deviceId: String,
    block: CardBlock,
    pendingActions: Set<String>,
    onAction: CardActionHandler,
) {
    val controls = block.schema?.controls.orEmpty()
    val serverValues = block.data["values"].asMap()
    val drafts = remember(deviceId, block.id) { mutableStateMapOf<String, String>() }
    val dirty = remember(deviceId, block.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(block) {
        controls.filter { it.kind == "field" }.forEach { control ->
            if (dirty[control.id] != true) {
                drafts[control.id] = (serverValues[control.id] ?: control.default).editableText()
            }
        }
    }
    BlockSurface(block.title) {
        controls.forEach { control ->
            val actionKey = "$deviceId:${block.id}:${control.id}"
            when (control.kind) {
                "field" -> FieldControl(
                    control = control,
                    value = drafts[control.id].orEmpty(),
                    onValueChange = { drafts[control.id] = it; dirty[control.id] = true },
                    pending = actionKey in pendingActions,
                    onCommit = { request ->
                        val value = parseValue(drafts[control.id], control.valueType)
                        dirty[control.id] = false
                        onAction(actionKey, request, parsedValues(drafts, controls), value, null)
                    },
                )
                "action" -> ActionControl(
                    control = control.copy(enabled = control.enabled && actionFieldsValid(
                        control.request, drafts, controls
                    )),
                    currentValue = serverValues[control.id] ?: control.value,
                    pending = actionKey in pendingActions,
                    lockDurationMs = (block.refresh.intervalMs ?: DEFAULT_BLOCK_POLL_INTERVAL_MS)
                        .coerceIn(2000L, 300000L),
                    onInvoke = { value, onComplete -> control.request?.let {
                        it.body.fields.forEach { field -> dirty[field] = false }
                        onAction(
                            actionKey, it, parsedValues(drafts, controls), value, onComplete
                        )
                    } ?: onComplete?.invoke(
                        ActionSubmissionResult(false, "Action request is missing")
                    ) },
                )
            }
        }
    }
}

@Composable
private fun FieldControl(
    control: CardControl,
    value: String,
    onValueChange: (String) -> Unit,
    pending: Boolean,
    onCommit: (CardRequest) -> Unit,
) {
    when (control.controlType) {
        "readonly.v1" -> OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(control.label) }, suffix = control.unit?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        "select.v1" -> {
            Text(control.label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                control.options.forEach { option ->
                    FilterChip(selected = value == option.value, onClick = { onValueChange(option.value) }, label = { Text(option.label) })
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = control.enabled && !pending,
            label = { Text(control.label) },
            suffix = control.unit?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (control.controlType == "number_input.v1") KeyboardType.Number else KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
    control.commit?.let { commit ->
        OutlinedButton(
            onClick = { onCommit(commit.request) },
            enabled = control.enabled && !pending && controlValueValid(control, value),
            modifier = Modifier.fillMaxWidth(),
        ) { if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(commit.label) }
    }
}

@Composable
private fun ActionControl(
    control: CardControl,
    currentValue: Any?,
    pending: Boolean,
    lockDurationMs: Long = DEFAULT_BLOCK_POLL_INTERVAL_MS,
    onInvoke: (Any?, ((ActionSubmissionResult) -> Unit)?) -> Unit,
) {
    val enabled = control.enabled && !pending
    when (control.controlType) {
        "date_time_range.v1" -> DateTimeRangeAction(control, pending, onInvoke)
        "action_toggle.v1" -> GuardedActionToggle(
            control = control,
            serverValue = currentValue as? Boolean ?: false,
            pending = pending,
            lockDurationMs = lockDurationMs,
            onInvoke = onInvoke,
        )
        "hold_action.v1" -> HoldActionButton(control.label, control.preset, enabled) { onInvoke(null, null) }
        "button.v1" -> Button(
            onClick = { onInvoke(null, null) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            colors = if (control.style == "danger") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        ) { if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(control.label) }
        else -> Text("Unsupported control: ${control.controlType}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GuardedActionToggle(
    control: CardControl,
    serverValue: Boolean,
    pending: Boolean,
    lockDurationMs: Long,
    onInvoke: (Any?, ((ActionSubmissionResult) -> Unit)?) -> Unit,
) {
    var displayedValue by remember(control.id) { mutableStateOf(serverValue) }
    var rollbackValue by remember(control.id) { mutableStateOf(serverValue) }
    var locked by remember(control.id) { mutableStateOf(false) }
    var timerElapsed by remember(control.id) { mutableStateOf(false) }
    var submissionResult by remember(control.id) { mutableStateOf<ActionSubmissionResult?>(null) }
    var localError by remember(control.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Polling may replace the whole block, but it must not overwrite an in-flight toggle.
    LaunchedEffect(serverValue) {
        if (!locked) displayedValue = serverValue
    }
    LaunchedEffect(submissionResult) {
        val result = submissionResult ?: return@LaunchedEffect
        if (locked && !result.successful) {
            displayedValue = rollbackValue
            localError = result.error
        }
    }
    LaunchedEffect(timerElapsed, submissionResult) {
        if (!locked || !timerElapsed || submissionResult == null) return@LaunchedEffect
        locked = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(control.label)
            Switch(
                checked = displayedValue,
                onCheckedChange = { nextValue ->
                    rollbackValue = displayedValue
                    displayedValue = nextValue
                    locked = true
                    timerElapsed = false
                    submissionResult = null
                    localError = null
                    scope.launch {
                        delay(lockDurationMs.milliseconds)
                        timerElapsed = true
                    }
                    onInvoke(nextValue) { result -> submissionResult = result }
                },
                enabled = control.enabled && !pending && !locked,
            )
        }
        localError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HoldActionButton(label: String, preset: String?, enabled: Boolean, onConfirmed: () -> Unit) {
    val duration = when (preset) {
        "zero_capture_hold.v1" -> 2000
        "calibration_hold.v1" -> 3000
        "history_delete_hold.v1" -> 5000
        else -> 2000
    }
    val progress = remember { Animatable(0f) }
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().height(48.dp).pointerInput(enabled, preset) {
            detectTapGestures(onPress = {
                if (!enabled) { tryAwaitRelease(); return@detectTapGestures }
                coroutineScope {
                    var confirmed = false
                    val job = launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(duration))
                        confirmed = true
                        onConfirmed()
                    }
                    tryAwaitRelease()
                    if (!confirmed) { job.cancel(); progress.snapTo(0f) }
                }
            })
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = Color.Transparent,
            )
            Text("Hold: $label", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ProgressBlock(
    deviceId: String,
    block: CardBlock,
    pendingActions: Set<String>,
    onAction: CardActionHandler,
) {
    BlockSurface(block.title) {
        Text(block.data["label"].asText(), fontWeight = FontWeight.Bold)
        block.data["target"].asNumber()?.let { Text("Target: ${formatNumber(it)} ${block.data["unit"].asText()}") }
        LinearProgressIndicator(Modifier.fillMaxWidth())
        block.schema?.controls.orEmpty().forEach { control ->
            val key = "$deviceId:${block.id}:${control.id}"
            ActionControl(control, control.value, key in pendingActions) { value, onComplete ->
                control.request?.let { onAction(key, it, emptyMap(), value, onComplete) }
                    ?: onComplete?.invoke(
                        ActionSubmissionResult(false, "Action request is missing")
                    )
            }
        }
    }
}

@Composable
private fun HistoryBlock(
    deviceId: String,
    block: CardBlock,
    pendingActions: Set<String>,
    onAction: CardActionHandler,
) {
    val items = block.data["items"].asList()
    BlockSurface(block.title) {
        block.schema?.controls.orEmpty().forEach { control ->
            val key = "$deviceId:${block.id}:${control.id}"
            ActionControl(control, control.value, key in pendingActions) { value, onComplete ->
                control.request?.let {
                    onAction(key, it, emptyMap(), value, onComplete)
                } ?: onComplete?.invoke(ActionSubmissionResult(false, "Action request is missing"))
            }
        }
        if (items.isEmpty()) Text("No watering history", color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { raw ->
            val item = raw.asMap()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item["occurred_at"].asNumber()?.let { Text(formatTimestamp(it), fontWeight = FontWeight.Bold) }
                    Text("Added: ${item["amount_g"].asNumber()?.let(::formatNumber) ?: "—"} g")
                    Text("Weight: ${item["weight_before_g"].asNumber()?.let(::formatNumber) ?: "—"} → ${item["weight_after_g"].asNumber()?.let(::formatNumber) ?: "—"} g")
                    item["actions"].asList().forEach { actionRaw ->
                        val action = actionRaw.asControl() ?: return@forEach
                        val key = "$deviceId:${block.id}:${item["id"].asText()}:${action.id}"
                        ActionControl(action, action.value, key in pendingActions) { value, onComplete ->
                            action.request?.let {
                                onAction(key, it, emptyMap(), value, onComplete)
                            } ?: onComplete?.invoke(
                                ActionSubmissionResult(false, "Action request is missing")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockSurface(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}

private fun parsedValues(drafts: SnapshotStateMap<String, String>, controls: List<CardControl>): Map<String, Any?> =
    controls.filter { it.kind == "field" }.associate { control ->
        control.id to parseValue(drafts[control.id], control.valueType)
    }

private fun actionFieldsValid(
    request: CardRequest?,
    drafts: SnapshotStateMap<String, String>,
    controls: List<CardControl>,
): Boolean {
    val fields = request?.body?.fields.orEmpty()
    if (fields.isEmpty()) return true
    return fields.all { field ->
        controls.firstOrNull { it.id == field }?.let { control ->
            controlValueValid(control, drafts[field].orEmpty())
        } == true
    }
}

private fun controlValueValid(control: CardControl, value: String): Boolean {
    if (value.isBlank()) return false
    if (control.valueType !in setOf("integer", "decimal")) return true
    val number = value.toDoubleOrNull() ?: return false
    val min = control.constraints["min"].asNumber()
    val max = control.constraints["max"].asNumber()
    val minExclusive = control.constraints["min_exclusive"].asNumber()
    return (min == null || number >= min) &&
        (max == null || number <= max) &&
        (minExclusive == null || number > minExclusive)
}

private fun parseValue(value: String?, valueType: String?): Any? = when (valueType) {
    "integer" -> value?.toIntOrNull()
    "decimal" -> value?.toDoubleOrNull()
    "boolean" -> value?.toBooleanStrictOrNull()
    else -> value
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
private fun Any?.asText(): String = this?.toString().orEmpty()
private fun Any?.asNumber(): Double? = (this as? Number)?.toDouble()
private fun Any?.editableText(): String = when (this) {
    null -> ""
    is Double -> if (this % 1.0 == 0.0) toLong().toString() else toString()
    else -> toString()
}
private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
private fun formatTimestamp(epochSeconds: Double): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))
private fun isSnapshotStale(epochSeconds: Double, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
    nowEpochSeconds - epochSeconds > STALE_SNAPSHOT_AGE_SECONDS

private fun Any?.asControl(): CardControl? {
    val map = asMap()
    val requestMap = map["request"].asMap()
    val bodyMap = requestMap["body"].asMap()
    val request = if (requestMap.isEmpty()) null else CardRequest(
        method = requestMap["method"].asText(),
        href = requestMap["href"].asText(),
        body = CardRequestBodyBinding(
            binding = bodyMap["binding"].asText(),
            property = bodyMap["property"]?.toString(),
            fields = bodyMap["fields"].asList().map { it.asText() },
            value = bodyMap["value"].asMap(),
            literal = bodyMap["literal"].asMap(),
        ),
    )
    return map["id"]?.toString()?.let {
        CardControl(
            kind = "action", id = it, label = map["label"].asText(),
            controlType = map["control_type"].asText(), preset = map["preset"]?.toString(),
            enabled = map["enabled"] as? Boolean ?: true, request = request, value = map["value"],
        )
    }
}

@Composable
private fun VersionInfoButton(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val release by viewModel.latestAppRelease.collectAsState()
    val loading by viewModel.isAppReleaseLoading.collectAsState()
    val error by viewModel.appReleaseError.collectAsState()
    val context = LocalContext.current
    IconButton(onClick = { open = true; viewModel.refreshAppRelease() }, modifier = modifier) {
        Icon(Icons.Default.Info, contentDescription = "App version")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("App version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current: ${BuildConfig.VERSION_NAME}")
                when {
                    release != null -> Text("Latest: ${release!!.versionName}")
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (release?.versionCode?.let { it > BuildConfig.VERSION_CODE } == true) TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, requireNotNull(release).downloadUrl.toUri()))
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
