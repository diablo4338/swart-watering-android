package com.smartwatering.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.smartwatering.app.BuildConfig
import com.smartwatering.app.data.AppRelease
import com.smartwatering.app.data.Device
import com.smartwatering.app.data.DeviceType
import com.smartwatering.app.data.LatestStatusResponse
import com.smartwatering.app.data.OperationEvent
import com.smartwatering.app.data.OperationResponse
import com.smartwatering.app.data.OperationType
import com.smartwatering.app.data.PlannedWatering
import com.smartwatering.app.data.RawDeviceStatus
import com.smartwatering.app.data.WateringStatus
import com.smartwatering.app.data.WateringParameters
import com.smartwatering.app.data.WaterConsumptionDay
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration.Companion.seconds
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val googleClientConfigured = BuildConfig.SMART_WATERING_GOOGLE_WEB_CLIENT_ID.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        VersionInfoButton(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text("Smart Watering", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            if (BuildConfig.DEBUG) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.login(username, password) },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login")
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        try {
                            viewModel.loginWithGoogle(requestGoogleIdToken(context))
                        } catch (_: GetCredentialCancellationException) {
                            viewModel.showLoginError("Google login cancelled")
                        } catch (_: NoCredentialException) {
                            viewModel.showLoginError(
                                "Google login failed: no Google account is available"
                            )
                        } catch (e: GetCredentialException) {
                            viewModel.showLoginError("Google login failed: ${credentialErrorMessage(e)}")
                        } catch (_: GoogleIdTokenParsingException) {
                            viewModel.showLoginError("Google login failed: invalid ID token")
                        } catch (e: Exception) {
                            viewModel.showLoginError("Google login failed: ${e.message ?: e::class.java.simpleName}")
                        }
                    }
                },
                enabled = googleClientConfigured,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in with Google")
            }
            if (!googleClientConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Google sign-in is not configured",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        }
    }
}

private suspend fun requestGoogleIdToken(context: Context): String {
    val googleIdOption = GetSignInWithGoogleOption.Builder(BuildConfig.SMART_WATERING_GOOGLE_WEB_CLIENT_ID)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    if (
        credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw IllegalStateException("unsupported Google credential")
}

private fun credentialErrorMessage(e: GetCredentialException): String {
    val type = e::class.simpleName ?: "GetCredentialException"
    val detail = e.message?.takeIf { it.isNotBlank() }
    return if (detail == null) type else "$type: $detail"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: MainViewModel,
    showBackendUnavailable: Boolean = false,
) {
    val devices by viewModel.devices.collectAsState()
    val selectedDeviceName by viewModel.selectedDeviceName.collectAsState()
    val deviceStates by viewModel.deviceStates.collectAsState()
    val wateringParameters by viewModel.wateringParameters.collectAsState()
    val wateringHistory by viewModel.wateringHistory.collectAsState()
    val isDevicesLoading by viewModel.isDevicesLoading.collectAsState()
    val globalError by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    if (showBackendUnavailable) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                },
                actions = {
                    VersionInfoButton(viewModel)
                    IconButton(onClick = { viewModel.logout() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isDevicesLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        globalError?.let {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = globalError ?: "No devices registered.",
                            color = if (globalError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            Button(onClick = { viewModel.retryFetchDevices() }) {
                                Text("Retry")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { viewModel.logout() }) {
                                Text("Logout")
                            }
                        }
                    }
                }
            }
        } else {
            val initialPage = devices.indexOfFirst { it.name == selectedDeviceName }.coerceAtLeast(0)
            val pagerState = rememberPagerState(
                initialPage = initialPage,
                pageCount = { devices.size + 1 }
            )
            LaunchedEffect(pagerState.currentPage, devices) {
                viewModel.setActiveDevice(devices.getOrNull(pagerState.currentPage))
            }
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (globalError != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        run {
                            Text(
                                text = globalError!!,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    key = { page -> if (page < devices.size) devices[page].name else "watering-history" }
                ) { page ->
                    if (page < devices.size) {
                        val device = devices[page]
                        val uiState = deviceStates[device.name] ?: DeviceUIState()
                        DevicePage(
                            device = device,
                            uiState = uiState,
                            wateringParameters = wateringParameters[device.name],
                            onLoadWateringParameters = { viewModel.loadWateringParameters(device) },
                            onSaveWateringParameters = { dry, wet, threshold ->
                                viewModel.saveWateringParameters(device, dry, wet, threshold)
                            },
                            onStartWatering = { grams -> viewModel.startWatering(device, grams) },
                            onStopWatering = { viewModel.stopWatering(device) },
                            onOpenControl = { viewModel.openDeviceControl(device) },
                            onOpenDetectedWaterings = {
                                viewModel.openDetectedWateringHistory(device)
                            }
                        )
                    } else {
                        WateringHistoryPage(
                            historyState = wateringHistory,
                            onSuccessfulOnlyChange = { viewModel.setWateringHistorySuccessfulOnly(it) }
                        )
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${devices.size + 1}",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun VersionInfoButton(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val latestRelease by viewModel.latestAppRelease.collectAsState()
    val isLoading by viewModel.isAppReleaseLoading.collectAsState()
    val releaseError by viewModel.appReleaseError.collectAsState()
    val context = LocalContext.current

    IconButton(
        onClick = {
            showDialog = true
            viewModel.refreshAppRelease()
        },
        modifier = modifier,
    ) {
        Icon(Icons.Default.Info, contentDescription = "Информация о версии")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Версия приложения") },
            text = {
                VersionInfoContent(
                    latestRelease = latestRelease,
                    isLoading = isLoading,
                    error = releaseError,
                )
            },
            confirmButton = {
                if (latestRelease?.versionCode?.let { it > BuildConfig.VERSION_CODE } == true) {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    requireNotNull(latestRelease).downloadUrl.toUri(),
                                )
                            )
                        }
                    ) {
                        Text("Скачать")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Закрыть")
                }
            },
        )
    }
}

@Composable
private fun VersionInfoContent(
    latestRelease: AppRelease?,
    isLoading: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Текущая версия: ${BuildConfig.VERSION_NAME}")
        when {
            latestRelease != null && latestRelease.versionCode > BuildConfig.VERSION_CODE -> {
                Text("Последняя: ${latestRelease.versionName}")
                Text(
                    "Доступно обновление",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            latestRelease != null -> {
                Text(
                    "Актуальная версия",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Проверяем доступную версию…")
                }
            }
            error != null -> {
                Text(
                    "Не удалось проверить обновления",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> Text("Доступная версия пока не определена")
        }
    }
}

private fun parameterDate(epochSeconds: Double?): String = epochSeconds?.let {
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date((it * 1000).toLong()))
} ?: "Never updated"

private fun weightAboveWateringThresholdG(
    grossWeightG: Double?,
    dryWeightG: Int?,
    wetWeightG: Int?,
    waterLossPercent: Int?,
): Int? {
    if (
        grossWeightG == null || dryWeightG == null || wetWeightG == null ||
        waterLossPercent == null || wetWeightG <= dryWeightG || waterLossPercent !in 0..100
    ) {
        return null
    }
    return (
        grossWeightG - dryWeightG -
            (wetWeightG - dryWeightG) * waterLossPercent / 100.0
        ).roundToInt()
}

@Composable
private fun HoldToConfirmButton(
    label: String,
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(enabled, onConfirmed) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) {
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        val confirmationJob = coroutineScope.launch {
                            delay(3.seconds)
                            onConfirmed()
                        }
                        tryAwaitRelease()
                        confirmationJob.cancel()
                    }
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("Hold 3 sec: $label", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun WateringParametersDialog(
    grossWeightG: Double?,
    controllerDryWeightG: Double?,
    parameters: WateringParameters?,
    onLoad: () -> Unit,
    onSave: (Int?, Int?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var dry by remember { mutableStateOf("") }
    var wet by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf("") }
    var dryDirty by remember { mutableStateOf(false) }
    var wetDirty by remember { mutableStateOf(false) }
    var thresholdDirty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoad() }
    LaunchedEffect(parameters, controllerDryWeightG) {
        if (!dryDirty) {
            dry = parameters?.dryWeightG?.toString()
                ?: controllerDryWeightG?.roundToInt()?.toString()
                ?: ""
        }
        if (!wetDirty) wet = parameters?.wetWeightG?.toString() ?: ""
        if (!thresholdDirty) threshold = parameters?.wateringLossThresholdPercent?.toString() ?: ""
    }
    val dryValue = dry.toIntOrNull()
    val wetValue = wet.toIntOrNull()
    val thresholdValue = threshold.toIntOrNull()
    val hasChanges = dryDirty || wetDirty || thresholdDirty
    val changedValuesAreValid =
        (!dryDirty || dryValue != null && dryValue >= 0) &&
        (!wetDirty || wetValue != null && wetValue >= 0) &&
        (!thresholdDirty || thresholdValue != null && thresholdValue in 0..100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watering parameters") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = grossWeightG?.roundToInt()?.toString() ?: "No data",
                    onValueChange = {}, readOnly = true, label = { Text("Raw weight (gross), g") },
                )
                OutlinedTextField(
                    value = dry, onValueChange = { dry = it; dryDirty = true }, label = { Text("Dry weight, g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Updated: ${parameterDate(parameters?.dryWeightUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = wet, onValueChange = { wet = it; wetDirty = true }, label = { Text("Wet weight, g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Updated: ${parameterDate(parameters?.wetWeightUpdatedAt)}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = threshold, onValueChange = { threshold = it; thresholdDirty = true },
                    label = { Text("Water loss threshold, %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = hasChanges && changedValuesAreValid,
                onClick = {
                    onSave(
                        dryValue.takeIf { dryDirty },
                        wetValue.takeIf { wetDirty },
                        thresholdValue.takeIf { thresholdDirty },
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DevicePage(
    device: Device,
    uiState: DeviceUIState,
    wateringParameters: WateringParameters?,
    onLoadWateringParameters: () -> Unit,
    onSaveWateringParameters: (Int?, Int?, Int?) -> Unit,
    onStartWatering: (Double) -> Unit,
    onStopWatering: () -> Unit,
    onOpenControl: () -> Unit,
    onOpenDetectedWaterings: () -> Unit
) {
    var showWateringParameters by remember(device.name) { mutableStateOf(false) }
    LaunchedEffect(device.name, device.type) {
        if (device.type == DeviceType.PLANT.apiValue) onLoadWateringParameters()
    }
    if (showWateringParameters) {
        WateringParametersDialog(
            grossWeightG = uiState.latestStatus?.result?.weight?.grossWeightG,
            controllerDryWeightG = uiState.latestStatus?.result?.config?.dryWeightG,
            parameters = wateringParameters,
            onLoad = onLoadWateringParameters,
            onSave = onSaveWateringParameters,
            onDismiss = { showWateringParameters = false },
        )
    }
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                val statusColor = if (uiState.isOnline) Color.Green else Color.Red
                val statusText = if (uiState.isOnline) "Online" else "Offline"
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }

            Text(
                device.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenControl, modifier = Modifier.fillMaxWidth()) {
                Text("Control")
            }
            if (device.type == DeviceType.PLANT.apiValue) {
                OutlinedButton(
                    onClick = { showWateringParameters = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Watering parameters")
                }
                OutlinedButton(
                    onClick = onOpenDetectedWaterings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Watering history")
                }
                val weightAboveThreshold = weightAboveWateringThresholdG(
                    grossWeightG = uiState.latestStatus?.result?.weight?.grossWeightG,
                    dryWeightG = wateringParameters?.dryWeightG
                        ?: uiState.latestStatus?.result?.config?.dryWeightG?.roundToInt(),
                    wetWeightG = wateringParameters?.wetWeightG,
                    waterLossPercent = wateringParameters?.wateringLossThresholdPercent,
                )
                Text(
                    text = weightAboveThreshold?.let { "$it g" } ?: "—",
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 64.sp,
                    color = when {
                        weightAboveThreshold == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        weightAboveThreshold > 50 -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = "Weight above watering threshold",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.hasPendingControlOperations) {
                Text(
                    text = "There are waiting operations",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (device.type == DeviceType.PLANT.apiValue) {
                SnapshotLabel(uiState.latestStatus)
                WaterConsumptionBlock(uiState.waterConsumption)
            } else {
                TankContent(uiState, onStartWatering, onStopWatering)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedWateringHistoryScreen(viewModel: MainViewModel, device: Device) {
    val state by viewModel.detectedWateringHistory.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var holdingDeleteId by remember { mutableStateOf<Int?>(null) }
    var holdSecondsLeft by remember { mutableIntStateOf(5) }
    LaunchedEffect(listState, state.nextOffset) {
        snapshotFlow {
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to listState.layoutInfo.totalItemsCount
        }.distinctUntilChanged().collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 3) {
                viewModel.loadMoreDetectedWaterings(device)
            }
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Waterings: ${device.name}") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeDetectedWateringHistory() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.waterings.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        "No detected waterings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.waterings, key = { it.id }) { watering ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                SimpleDateFormat(
                                    "dd.MM.yyyy HH:mm",
                                    Locale.getDefault()
                                ).format(Date((watering.occurredAt * 1000).toLong())),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                shape = CircleShape,
                                color = if (watering.fertilized) Color(0xFF2E7D32)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(36.dp).pointerInput(
                                    watering.id,
                                    watering.fertilized,
                                    state.fertilizingId
                                ) {
                                    detectTapGestures(
                                        onPress = {
                                            if (state.fertilizingId != null) {
                                                tryAwaitRelease()
                                                return@detectTapGestures
                                            }
                                            val fertilizerJob = coroutineScope.launch {
                                                delay(3.seconds)
                                                viewModel.toggleDetectedWateringFertilized(
                                                    device, watering.id
                                                )
                                            }
                                            tryAwaitRelease()
                                            fertilizerJob.cancel()
                                        }
                                    )
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (state.fertilizingId == watering.id) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = if (watering.fertilized) Color.White
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Eco,
                                            contentDescription = if (watering.fertilized) {
                                                "Fertilizer added; hold to clear"
                                            } else {
                                                "No fertilizer; hold to mark"
                                            },
                                            tint = if (watering.fertilized) Color.White
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Text("Added: ${watering.amountG.roundToInt()} g")
                        Text(
                            "Weight: ${watering.weightBeforeG.roundToInt()} → " +
                                "${watering.weightAfterG.roundToInt()} g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = Color.Transparent,
                            modifier = Modifier.pointerInput(
                                watering.id,
                                state.deletingId
                            ) {
                                detectTapGestures(
                                    onPress = {
                                        if (state.deletingId != null) {
                                            tryAwaitRelease()
                                            return@detectTapGestures
                                        }
                                        holdingDeleteId = watering.id
                                        holdSecondsLeft = 5
                                        val deleteJob = coroutineScope.launch {
                                            for (seconds in 5 downTo 1) {
                                                holdSecondsLeft = seconds
                                                delay(1.seconds)
                                            }
                                            viewModel.deleteDetectedWatering(
                                                device, watering.id
                                            )
                                        }
                                        tryAwaitRelease()
                                        deleteJob.cancel()
                                        holdingDeleteId = null
                                        holdSecondsLeft = 5
                                    }
                                )
                            }
                        ) {
                            Text(
                                text = when {
                                    state.deletingId == watering.id -> "Deleting..."
                                    holdingDeleteId == watering.id ->
                                        "Keep holding: ${holdSecondsLeft}s"
                                    else -> "Hold 5 seconds to delete"
                                },
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 10.dp
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge
                            )
                                }
                    }
                }
            }
            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.refreshDetectedWateringHistory(device) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(viewModel: MainViewModel, device: Device) {
    val control by viewModel.deviceControl.collectAsState()
    val deviceTypes by viewModel.deviceTypes.collectAsState()
    val deviceStates by viewModel.deviceStates.collectAsState()
    val latestStatus = deviceStates[device.name]?.latestStatus
    val raw = latestStatus?.result
    val config = raw?.config
    val snapshotReceivedAt = latestStatus?.resultReceivedAt ?: 0.0
    fun confirmedOperation(type: String): OperationResponse? =
        control.recentOperations.firstOrNull {
            it.type == type &&
                it.status in listOf("accepted", "success") &&
                it.updatedAt > snapshotReceivedAt
        }
    val confirmedConfig = confirmedOperation(OperationType.DEVICE_CONFIG.apiValue)
    val confirmedSleepInterval = confirmedOperation(OperationType.SLEEP_INTERVAL.apiValue)
    val confirmedSleep = control.recentOperations.firstOrNull {
        it.type in listOf(OperationType.SLEEP_ENABLE.apiValue, OperationType.SLEEP_DISABLE.apiValue) &&
            it.status in listOf("accepted", "success") &&
            it.updatedAt > snapshotReceivedAt
    }
    val pendingSleepOperation = control.pendingOperations.firstOrNull {
        it.type == OperationType.SLEEP_ENABLE.apiValue || it.type == OperationType.SLEEP_DISABLE.apiValue
    }
    val pendingConfigOperation = control.pendingOperations.firstOrNull { it.type == OperationType.DEVICE_CONFIG.apiValue }
    val pendingSleepIntervalOperation = control.pendingOperations.firstOrNull { it.type == OperationType.SLEEP_INTERVAL.apiValue }
    val pendingZeroOperation = control.pendingOperations.firstOrNull { it.type == OperationType.ZERO_CAPTURE.apiValue }
    val pendingCalibrationOperation = control.pendingOperations.firstOrNull { it.type == OperationType.SCALE_CALIBRATION.apiValue }
    val actualName = pendingConfigOperation?.name ?: confirmedConfig?.name ?: raw?.device?.name ?: device.name
    val actualType = pendingConfigOperation?.deviceType ?: confirmedConfig?.deviceType ?: raw?.device?.type ?: device.type
    val actualTareWeight = pendingConfigOperation?.tareWeightG ?: confirmedConfig?.tareWeightG ?: config?.tareWeightG
    val actualSleepMinutes = pendingSleepIntervalOperation?.minutes ?: confirmedSleepInterval?.minutes ?: config?.sleepIntervalMin
    val effectiveSleepOperation = pendingSleepOperation ?: confirmedSleep
    val actualSleepDisabled = when (effectiveSleepOperation?.type) {
        OperationType.SLEEP_ENABLE.apiValue -> false
        OperationType.SLEEP_DISABLE.apiValue -> true
        else -> config?.sleepDisabled
    }

    var name by remember(device.name) { mutableStateOf(actualName) }
    var type by remember(device.name) { mutableStateOf(actualType) }
    var tareWeight by remember(device.name) { mutableStateOf(actualTareWeight?.roundToInt()?.toString() ?: "") }
    var sleepMinutes by remember(device.name) { mutableStateOf(actualSleepMinutes?.toString() ?: "") }
    var calibrationWeight by remember(device.name) { mutableStateOf("") }
    var configDirty by remember(device.name) { mutableStateOf(false) }
    var sleepIntervalDirty by remember(device.name) { mutableStateOf(false) }
    var calibrationDirty by remember(device.name) { mutableStateOf(false) }

    LaunchedEffect(raw, confirmedConfig, confirmedSleepInterval, pendingConfigOperation, pendingSleepIntervalOperation) {
        if (!configDirty) {
            name = actualName
            type = actualType
            tareWeight = actualTareWeight?.roundToInt()?.toString() ?: ""
        }
        if (!sleepIntervalDirty) {
            sleepMinutes = actualSleepMinutes?.toString() ?: ""
        }
    }
    LaunchedEffect(pendingCalibrationOperation) {
        if (!calibrationDirty) {
            calibrationWeight = pendingCalibrationOperation?.weightG?.roundToInt()?.toString() ?: ""
        }
    }
    LaunchedEffect(device.name) {
        while (true) {
            delay(3.seconds)
            viewModel.refreshDeviceControl(device, showLoading = false)
        }
    }

    fun pendingValue(selector: (OperationResponse) -> Any?): String? =
        control.pendingOperations.firstNotNullOfOrNull { operation ->
            when (val value = selector(operation)) {
                is Number -> "${operationStatusLabel(operation)}: ${value.toDouble().roundToInt()}"
                null -> null
                else -> "${operationStatusLabel(operation)}: $value"
            }
        }
    val activeQueueCount = control.pendingOperations.count {
        it.status in listOf("queued", "sending", "accepted", "running")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Control: ${device.name}") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeDeviceControl() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Device parameters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ControlField(name, { name = it; configDirty = true }, "Name", pendingValue { it.name })
            DeviceTypeField(
                value = type,
                options = deviceTypes,
                onValueChange = { type = it; configDirty = true },
                pending = pendingValue { it.deviceType }
            )
            if (type != DeviceType.PLANT.apiValue) {
                ControlField(
                    tareWeight,
                    { tareWeight = it; configDirty = true },
                    "Tare weight (g)",
                    pendingValue { it.tareWeightG },
                    true
                )
            }
            Button(
                onClick = {
                    val tare = tareWeight.toIntOrNull()
                    if (type != DeviceType.PLANT.apiValue && tare == null) return@Button
                    configDirty = false
                    viewModel.updateDeviceConfig(device, type, name, tare)
                },
                enabled = name.isNotBlank() && type in deviceTypes &&
                    (type == DeviceType.PLANT.apiValue || tareWeight.toIntOrNull() != null),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save parameters") }
            PendingCommandMarker(pendingConfigOperation, "Parameters")

            HorizontalDivider()
            Text("Sleep", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                pendingSleepOperation?.let {
                    val mode = if (it.type == OperationType.SLEEP_ENABLE.apiValue) "enabled" else "disabled"
                    Text(
                        text = "${operationStatusLabel(it)}: sleep $mode",
                        color = operationStatusColor(it),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Sleep mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (actualSleepDisabled == false) "Enabled" else "Disabled",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = actualSleepDisabled == false,
                    onCheckedChange = { enabled ->
                        if (actualSleepDisabled != null && enabled != !actualSleepDisabled) {
                            viewModel.setSleep(device, enabled)
                        }
                    },
                    enabled = actualSleepDisabled != null &&
                        pendingSleepOperation?.status !in listOf("queued", "sending", "accepted", "running")
                )
            }
            ControlField(
                sleepMinutes,
                { sleepMinutes = it; sleepIntervalDirty = true },
                "Sleep interval (min)",
                pendingValue { it.minutes },
                true
            )
            Button(
                onClick = {
                    sleepMinutes.toIntOrNull()?.let {
                        sleepIntervalDirty = false
                        viewModel.setSleepInterval(device, it)
                    }
                },
                enabled = (sleepMinutes.toIntOrNull() ?: 0) in 1..50,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change sleep interval") }
            PendingCommandMarker(pendingSleepIntervalOperation, "Sleep interval")

            HorizontalDivider()
            Text("Scale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HoldToConfirmButton(
                label = "Set zero",
                enabled = true,
                onConfirmed = { viewModel.captureZero(device) },
            )
            PendingCommandMarker(pendingZeroOperation, "Set zero")
            ControlField(
                calibrationWeight,
                { calibrationWeight = it; calibrationDirty = true },
                "Calibration weight (g)",
                pendingValue { it.weightG },
                true
            )
            HoldToConfirmButton(
                label = "Calibrate",
                onConfirmed = {
                    calibrationWeight.toIntOrNull()?.let {
                        calibrationDirty = false
                        viewModel.calibrate(device, it.toDouble())
                    }
                },
                enabled = (calibrationWeight.toIntOrNull() ?: 0) > 0,
            )
            PendingCommandMarker(pendingCalibrationOperation, "Calibration")

            HorizontalDivider()
            Text("Operations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (control.pendingOperations.isEmpty()) {
                Text(
                    "No active or failed operations",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                control.pendingOperations.forEach { operation ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${operation.type.replace('_', ' ')} - ${operationStatusLabel(operation)}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (operation.status in listOf("error", "timeout")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.clearDeviceQueue(device) },
                enabled = true,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear queue ($activeQueueCount)") }
            OutlinedButton(
                onClick = { viewModel.refreshDeviceControl(device) },
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh") }
        }
    }
}

@Composable
private fun ControlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    pending: String?,
    numeric: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        supportingText = {
            if (pending == null) {
                Text(" ", style = MaterialTheme.typography.labelSmall)
            } else {
                PendingValue(pending)
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTypeField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    pending: String?
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = {
                if (pending == null) Text(" ", style = MaterialTheme.typography.labelSmall)
                else PendingValue(pending)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PendingValue(value: String) {
    Text(value, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun PendingCommandMarker(operation: OperationResponse?, action: String) {
    Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
        operation?.let {
            Text(
                text = "${operationStatusLabel(it)}: $action",
                color = operationStatusColor(it),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun operationStatusLabel(operation: OperationResponse): String = when (operation.status) {
    "queued" -> "Queued"
    "sending" -> "Sending"
    "accepted" -> "Accepted"
    "running" -> "Running"
    "error" -> operation.error?.message?.let { "Error: $it" } ?: "Error"
    "timeout" -> operation.error?.message?.let { "Timeout: $it" } ?: "Timeout"
    else -> operation.status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun operationStatusColor(operation: OperationResponse): Color =
    if (operation.status in listOf("error", "timeout")) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.tertiary

private fun formatSnapshotReceivedAt(epochSeconds: Double): String {
    val timestamp = Date((epochSeconds * 1000).toLong())
    val snapshotDay = Calendar.getInstance().apply { time = timestamp }
    val today = Calendar.getInstance()
    val isToday = snapshotDay.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        && snapshotDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = if (isToday) "HH:mm:ss" else "EEE, dd.MM.yyyy HH:mm:ss"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(timestamp)
}

@Composable
fun TankContent(
    uiState: DeviceUIState,
    onStartWatering: (Double) -> Unit,
    onStopWatering: () -> Unit
) {
    var amountText by remember { mutableStateOf("200") }
    val activeOp = uiState.activeOperation
    val latestStatus = uiState.latestStatus
    val rawStatus = latestStatus?.result
    val isTrackedWatering = activeOp != null && uiState.isWateringTask
    val isControllerWatering = uiState.wateringStatus?.source == "live" && uiState.wateringStatus.active
    val isWateringActive = isTrackedWatering || isControllerWatering

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TankInfoBlock(rawStatus)
        SnapshotLabel(latestStatus)
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        TankOperationBlock(
            amountText = amountText,
            onAmountChange = { amountText = it },
            activeOperation = if (uiState.isWateringTask) activeOp else null,
            activeOperationEvents = uiState.activeOperationEvents,
            isWateringActive = isWateringActive,
            controllerWateringState = uiState.wateringStatus?.state,
            wateringStatus = uiState.wateringStatus,
            plannedWatering = uiState.plannedWatering,
            onStartWatering = onStartWatering,
            onStopWatering = onStopWatering
        )
    }
}

@Composable
private fun SnapshotLabel(latestStatus: LatestStatusResponse?) {
    if (latestStatus?.source == "snapshot") {
        latestStatus.resultReceivedAt?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Snapshot: ${formatSnapshotReceivedAt(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun TankInfoBlock(rawStatus: RawDeviceStatus?) {
    val gross = rawStatus?.weight?.grossWeightG
    val tare = rawStatus?.config?.tareWeightG
    val water = if (gross != null && tare != null) gross - tare else null

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Water Weight", style = MaterialTheme.typography.labelSmall)
        Text(
            text = water?.let { "${it.toInt()} g" } ?: "N/A",
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 50.sp),
            fontWeight = FontWeight.Black,
            color = if (water != null && water < 100.0) Color.Red else Color(0xFF2196F3)
        )
    }
}

@Composable
fun WaterConsumptionBlock(days: List<WaterConsumptionDay>) {
    val rows = (days + List(7) { WaterConsumptionDay("", null, null) }).take(7)

    Spacer(modifier = Modifier.height(14.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { values ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WaterConsumptionCell(
                    values.day,
                    values.dayBelowWeeklyMedian,
                    Color(0xFF4CAF50),
                    Modifier.weight(1f)
                )
                WaterConsumptionCell(
                    values.night,
                    values.nightBelowWeeklyMedian,
                    Color.Gray,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WaterConsumptionCell(
    value: Double?,
    belowWeeklyMedian: Boolean,
    arrowColor: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier.height(46.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (value != null && value < 0) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowUp
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (belowWeeklyMedian) MaterialTheme.colorScheme.error else arrowColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            if (value != null) {
                Text(
                    text = "${formatConsumption(kotlin.math.abs(value))} g",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "-",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatConsumption(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value)

@Composable
fun TankOperationBlock(
    amountText: String,
    onAmountChange: (String) -> Unit,
    activeOperation: OperationResponse?,
    activeOperationEvents: List<OperationEvent>,
    isWateringActive: Boolean,
    controllerWateringState: String?,
    wateringStatus: WateringStatus?,
    plannedWatering: PlannedWatering?,
    onStartWatering: (Double) -> Unit,
    onStopWatering: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Watering", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        val planned = plannedWatering?.takeIf { it.status in listOf("queued", "sending") }

        if (activeOperation != null) {
            WateringOperationStatus(activeOperation, activeOperationEvents, wateringStatus)
            Spacer(modifier = Modifier.height(20.dp))
            StopWateringButton(onStopWatering)
        } else if (planned != null) {
            PlannedWateringStatus(planned)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onStopWatering,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cancel")
            }
        } else {
            if (isWateringActive) {
                ControllerWateringStatus(controllerWateringState)
                Spacer(modifier = Modifier.height(20.dp))
                StopWateringButton(onStopWatering)
            } else {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    label = { Text("Water amount (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { amountText.toDoubleOrNull()?.let { onStartWatering(it) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start")
                }
            }
        }
    }
}

@Composable
fun PlannedWateringStatus(planned: PlannedWatering) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "Planned watering: ${planned.targetG.toInt()} g",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun WateringOperationStatus(
    operation: OperationResponse,
    events: List<OperationEvent>,
    wateringStatus: WateringStatus?
) {
    val showProgress = operation.status == "running"
    val progressPercent = wateringStatus?.percentComplete?.takeIf { showProgress }
    val progressState = wateringStatus?.state?.takeIf { showProgress }
    val targetText = operation.targetG?.let { "${it.toInt()} g" } ?: "N/A"
    val lastMessage = events.lastOrNull()?.message
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val updatedAt = dateFormat.format(Date((operation.updatedAt * 1000).toLong()))

    Text(
        text = when (operation.status) {
            "accepted" -> "Watering command accepted"
            "running" -> "Watering in progress"
            else -> "Watering operation"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text("Status: ${operation.status.uppercase()}", style = MaterialTheme.typography.bodyMedium)
    Text("Target: $targetText", style = MaterialTheme.typography.bodyMedium)
    Text("Updated: $updatedAt", style = MaterialTheme.typography.bodySmall)
    if (!showProgress && operation.status == "accepted") {
        Text("Waiting for controller result", style = MaterialTheme.typography.bodySmall)
    }
    progressPercent?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Progress: ${it.toInt()}%")
        LinearProgressIndicator(
            progress = { it.toFloat() / 100f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        )
    }
    progressState?.let {
        Text("State: $it")
    }
    if (!lastMessage.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Event: $lastMessage", style = MaterialTheme.typography.bodySmall)
    }

    operation.error?.let { err ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(err.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (!err.detail.isNullOrBlank() && err.detail != err.message) {
            Text(err.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ControllerWateringStatus(state: String?) {
    Text("Watering started", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text("State: ${state ?: "active"}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun WateringHistoryPage(
    historyState: WateringHistoryUiState,
    onSuccessfulOnlyChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Watering History",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !historyState.successfulOnly,
                    onClick = { onSuccessfulOnlyChange(false) },
                    label = { Text("Last 10") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = historyState.successfulOnly,
                    onClick = { onSuccessfulOnlyChange(true) },
                    label = { Text("Successful") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            when {
                historyState.isLoading && historyState.operations.isEmpty() -> {
                    CircularProgressIndicator()
                }
                historyState.error != null && historyState.operations.isEmpty() -> {
                    Text(
                        historyState.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                historyState.operations.isEmpty() -> {
                    Text("No watering history", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    historyState.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    historyState.operations.forEachIndexed { index, operation ->
                        WateringHistoryRow(operation)
                        if (index != historyState.operations.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WateringHistoryRow(operation: OperationResponse) {
    val success = operation.status == "success"
    val statusColor = if (success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    val finishedAt = operation.finishedAt ?: operation.updatedAt
    val finishedText = formatSnapshotReceivedAt(finishedAt)
    val targetText = operation.targetG?.let { "${it.toInt()} g" } ?: "N/A"
    val message = operation.error?.message

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                operation.device.ifBlank { "Tank" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                operation.status.uppercase(),
                color = statusColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Target: $targetText", style = MaterialTheme.typography.bodySmall)
        Text("Finished: $finishedText", style = MaterialTheme.typography.bodySmall)
        if (!message.isNullOrBlank()) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun StopWateringButton(onStopWatering: () -> Unit) {
    Button(
        onClick = onStopWatering,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Stop Watering")
    }
}
