@file:Suppress("DEPRECATION")

package com.smartwatering.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartwatering.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class DeviceUIState(
    val latestStatus: LatestStatusResponse? = null,
    val wateringStatus: WateringStatus? = null,
    val activeOperation: OperationResponse? = null,
    val activeOperationEvents: List<OperationEvent> = emptyList(),
    val lastFinishedOperation: OperationResponse? = null,
    val lastFinishedOperationEvents: List<OperationEvent> = emptyList(),
    val lastFinishedNeedsAck: Boolean = false,
    val plannedWatering: PlannedWatering? = null,
    val isOnline: Boolean = false,
    val isActionLoading: Boolean = false,
    val isStatusRefreshing: Boolean = false,
    val isWateringTask: Boolean = false,
    val hasPendingControlOperations: Boolean = false,
    val waterConsumption: List<WaterConsumptionDay> = emptyList()
)

data class WateringHistoryUiState(
    val operations: List<OperationResponse> = emptyList(),
    val successfulOnly: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class DeviceControlUiState(
    val pendingOperations: List<OperationResponse> = emptyList(),
    val recentOperations: List<OperationResponse> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class DetectedWateringHistoryUiState(
    val waterings: List<DetectedWatering> = emptyList(),
    val nextOffset: Int? = null,
    val isLoading: Boolean = false,
    val deletingId: Int? = null,
    val fertilizingId: Int? = null,
    val error: String? = null
)

sealed class Screen {
    object Login : Screen()
    object Devices : Screen()
    data class DeviceControl(val device: Device) : Screen()
    data class DetectedWateringHistory(val device: Device) : Screen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "SmartWateringVM"
        const val LOGIN_TIMEOUT_MS = 15000L
        const val DEVICES_TIMEOUT_MS = 15000L
        const val STATUS_TIMEOUT_MS = 20000L
        const val DEVICE_POLL_INTERVAL_MS = 6000L
        const val LIVE_STATUS_POLL_INTERVAL_MS = 3000L
        const val SNAPSHOT_STATUS_POLL_INTERVAL_MS = 10000L
        const val DEVICE_LIST_POLL_INTERVAL_MS = 10000L
        const val WATERING_HISTORY_POLL_INTERVAL_MS = 30000L
        const val WATER_CONSUMPTION_POLL_INTERVAL_MS = 300000L
        const val BACKEND_RECOVERY_POLL_INTERVAL_MS = 15000L
        const val DEVICES_LOAD_ERROR_PREFIX = "Failed to load devices:"
        const val AUTH_TOKEN_PRIMARY = "auth_token_primary"
        const val AUTH_EXPIRES_AT_PRIMARY = "auth_expires_at_primary"
        const val AUTH_TOKEN_FALLBACK = "auth_token_fallback"
        const val AUTH_EXPIRES_AT_FALLBACK = "auth_expires_at_fallback"
    }

    private val prefs = createSecurePrefs(application)

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Login)
    val currentScreen: StateFlow<Screen> = _currentScreen

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    val selectedDeviceName: StateFlow<String?> = _selectedDeviceName

    private val _deviceTypes = MutableStateFlow(DeviceType.entries.map { it.apiValue })
    val deviceTypes: StateFlow<List<String>> = _deviceTypes

    private val _deviceStates = MutableStateFlow<Map<String, DeviceUIState>>(emptyMap())
    val deviceStates: StateFlow<Map<String, DeviceUIState>> = _deviceStates

    private val _wateringParameters = MutableStateFlow<Map<String, WateringParameters>>(emptyMap())
    val wateringParameters: StateFlow<Map<String, WateringParameters>> = _wateringParameters

    private val _wateringHistory = MutableStateFlow(WateringHistoryUiState())
    val wateringHistory: StateFlow<WateringHistoryUiState> = _wateringHistory

    private val _deviceControl = MutableStateFlow(DeviceControlUiState())
    val deviceControl: StateFlow<DeviceControlUiState> = _deviceControl

    private val _detectedWateringHistory =
        MutableStateFlow(DetectedWateringHistoryUiState())
    val detectedWateringHistory: StateFlow<DetectedWateringHistoryUiState> =
        _detectedWateringHistory

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isDevicesLoading = MutableStateFlow(false)
    val isDevicesLoading: StateFlow<Boolean> = _isDevicesLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _latestAppRelease = MutableStateFlow<AppRelease?>(null)
    val latestAppRelease: StateFlow<AppRelease?> = _latestAppRelease

    private val _isAppReleaseLoading = MutableStateFlow(false)
    val isAppReleaseLoading: StateFlow<Boolean> = _isAppReleaseLoading

    private val _appReleaseError = MutableStateFlow<String?>(null)
    val appReleaseError: StateFlow<String?> = _appReleaseError

    val backendAvailability: StateFlow<BackendAvailability> = Repository.backendAvailability

    private val operationJobs = mutableMapOf<String, Job>()
    private val statusRequestMutexes = mutableMapOf<String, Mutex>()
    private val statusRefreshJobs = mutableMapOf<String, Job>()
    private val snapshotStatusJobs = mutableMapOf<String, Job>()
    private val wateringStatusJobs = mutableMapOf<String, Job>()
    private val waterConsumptionJobs = mutableMapOf<String, Job>()
    private val wateringHistoryJobs = mutableMapOf<Boolean, Job>()
    private val wateringHistoryCache = mutableMapOf<Boolean, List<OperationResponse>>()
    private var deviceListRefreshJob: Job? = null
    private var wateringHistoryRefreshJob: Job? = null
    private var deviceTypesLoaded = false
    private var deviceControlRefreshJob: Job? = null
    private var activePollingDeviceName: String? = null
    private val controlOperationJobs = mutableMapOf<String, Job>()
    private val suppressedOperationIds = mutableSetOf<String>()
    private val controlOperationTypes = setOf(
        OperationType.DEVICE_CONFIG.apiValue,
        OperationType.SLEEP_ENABLE.apiValue,
        OperationType.SLEEP_DISABLE.apiValue,
        OperationType.SLEEP_INTERVAL.apiValue,
        OperationType.ZERO_CAPTURE.apiValue,
        OperationType.SCALE_CALIBRATION.apiValue
    )

    private fun stableQueueOrder(operations: List<OperationResponse>): List<OperationResponse> =
        operations.sortedWith(compareBy<OperationResponse> { it.createdAt }.thenBy { it.operationId })

    private fun stableRecentOrder(operations: List<OperationResponse>): List<OperationResponse> =
        operations.sortedWith(compareByDescending<OperationResponse> { it.createdAt }.thenBy { it.operationId })

    private fun updatePendingControlFlag(deviceName: String, operations: List<OperationResponse>) {
        val hasPending = operations.any {
            it.type in controlOperationTypes && !isFinalStatus(it.status)
        }
        _deviceStates.update { current ->
            val old = current[deviceName] ?: DeviceUIState()
            current + (deviceName to old.copy(hasPendingControlOperations = hasPending))
        }
    }

    init {
        clearLegacyPlaintextPrefs(application)
        checkAutoLogin()
        refreshAppRelease()
        monitorBackendRecovery()
    }

    private fun monitorBackendRecovery() {
        viewModelScope.launch {
            while (true) {
                delay(BACKEND_RECOVERY_POLL_INTERVAL_MS.milliseconds)
                if (Repository.usingFallback.value) {
                    if (Repository.probePrimaryBackend()) {
                        if (Repository.hasPrimaryToken()) {
                            probeBackend()
                        } else {
                            // Independent backends require a separate login. Keep the fallback
                            // session stored, but stop authenticated work until primary login.
                            clearSession(preserveBackendTokens = true)
                            _error.value = null
                        }
                    }
                } else if (Repository.backendAvailability.value == BackendAvailability.UNAVAILABLE) {
                    probeBackend()
                }
            }
        }
    }

    fun retryBackendConnection() {
        viewModelScope.launch { probeBackend() }
    }

    private suspend fun probeBackend() {
        runCatching { Repository.api.getLatestAppRelease() }
        if (Repository.backendAvailability.value == BackendAvailability.AVAILABLE &&
            _currentScreen.value !is Screen.Login
        ) {
            runCatching { refreshDevicesOnce() }
        }
    }

    fun refreshAppRelease() {
        viewModelScope.launch {
            _isAppReleaseLoading.value = true
            _appReleaseError.value = null
            try {
                _latestAppRelease.value = Repository.api.getLatestAppRelease()
            } catch (e: Exception) {
                _appReleaseError.value = if (e is HttpException && e.code() == 404) {
                    "На сервере пока нет опубликованных версий"
                } else {
                    readableError(e)
                }
            } finally {
                _isAppReleaseLoading.value = false
            }
        }
    }

    private fun checkAutoLogin() {
        val now = System.currentTimeMillis() / 1000L
        // Treat the old unscoped session as primary during the one-time migration.
        val primaryToken = prefs.getString(AUTH_TOKEN_PRIMARY, null)
            ?: prefs.getString("auth_token", null)
        val primaryExpiresAt = prefs.getLong(
            AUTH_EXPIRES_AT_PRIMARY,
            prefs.getLong("auth_expires_at", 0L),
        )
        val fallbackToken = prefs.getString(AUTH_TOKEN_FALLBACK, null)
        val fallbackExpiresAt = prefs.getLong(AUTH_EXPIRES_AT_FALLBACK, 0L)
        val validPrimaryToken = primaryToken?.takeIf { primaryExpiresAt > now }
        val validFallbackToken = fallbackToken?.takeIf { fallbackExpiresAt > now }
        if (validPrimaryToken != null || validFallbackToken != null) {
            Repository.restoreTokens(validPrimaryToken, validFallbackToken)
            prefs.edit {
                remove("auth_token")
                remove("auth_expires_at")
                if (validPrimaryToken != null) {
                    putString(AUTH_TOKEN_PRIMARY, validPrimaryToken)
                    putLong(AUTH_EXPIRES_AT_PRIMARY, primaryExpiresAt)
                } else {
                    remove(AUTH_TOKEN_PRIMARY)
                    remove(AUTH_EXPIRES_AT_PRIMARY)
                }
                if (validFallbackToken == null) {
                    remove(AUTH_TOKEN_FALLBACK)
                    remove(AUTH_EXPIRES_AT_FALLBACK)
                }
            }
            _currentScreen.value = Screen.Devices
            fetchDevices()
        } else {
            clearSession()
        }
    }

    private fun SharedPreferences.Editor.storeActiveSession(response: LoginResponse) {
        if (Repository.usingFallback.value) {
            putString(AUTH_TOKEN_FALLBACK, response.token)
            putLong(AUTH_EXPIRES_AT_FALLBACK, response.expiresAt.toLong())
        } else {
            putString(AUTH_TOKEN_PRIMARY, response.token)
            putLong(AUTH_EXPIRES_AT_PRIMARY, response.expiresAt.toLong())
        }
        remove("auth_token")
        remove("auth_expires_at")
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = withTimeout(LOGIN_TIMEOUT_MS.milliseconds) {
                    performLogin(username, password)
                }
                prefs.edit().apply {
                    putString("saved_username", username)
                    storeActiveSession(response)
                    apply()
                }
                _currentScreen.value = Screen.Devices
                fetchDevices()
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    _error.value = "Invalid username or password"
                } else {
                    _error.value = "Login failed: ${readableError(e)}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = withTimeout(LOGIN_TIMEOUT_MS.milliseconds) {
                    performGoogleLogin(idToken)
                }
                prefs.edit().apply {
                    storeActiveSession(response)
                    apply()
                }
                _currentScreen.value = Screen.Devices
                fetchDevices()
            } catch (e: Exception) {
                _error.value = "Google login failed: ${readableError(e)}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun performLogin(username: String, password: String): LoginResponse {
        val response = Repository.api.login(LoginRequest(username, password))
        Repository.setToken(response.token)
        return response
    }

    private suspend fun performGoogleLogin(idToken: String): LoginResponse {
        val response = Repository.api.loginWithGoogle(GoogleLoginRequest(idToken))
        Repository.setToken(response.token)
        return response
    }

    fun logout() {
        viewModelScope.launch {
            try {
                Repository.api.logout()
            } catch (e: Exception) {
                Log.d(TAG, "Remote logout failed", e)
            } finally {
                clearActiveSession()
            }
        }
    }

    private fun clearActiveSession() {
        prefs.edit {
            if (Repository.usingFallback.value) {
                remove(AUTH_TOKEN_FALLBACK)
                remove(AUTH_EXPIRES_AT_FALLBACK)
            } else {
                remove(AUTH_TOKEN_PRIMARY)
                remove(AUTH_EXPIRES_AT_PRIMARY)
                remove("auth_token")
                remove("auth_expires_at")
            }
        }
        Repository.clearActiveToken()
        clearSession(preserveBackendTokens = true)
    }

    private fun clearSession(preserveBackendTokens: Boolean = false) {
        if (!preserveBackendTokens) {
            prefs.edit().apply {
                remove("auth_token")
                remove("auth_expires_at")
                remove(AUTH_TOKEN_PRIMARY)
                remove(AUTH_EXPIRES_AT_PRIMARY)
                remove(AUTH_TOKEN_FALLBACK)
                remove(AUTH_EXPIRES_AT_FALLBACK)
                remove("saved_username")
                apply()
            }
            Repository.clearTokens()
        }
        _currentScreen.value = Screen.Login
        _isLoading.value = false
        _isDevicesLoading.value = false
        _deviceStates.value = emptyMap()
        _wateringHistory.value = WateringHistoryUiState()
        _devices.value = emptyList()
        activePollingDeviceName = null
        operationJobs.values.toList().forEach { it.cancel() }
        operationJobs.clear()
        statusRefreshJobs.values.toList().forEach { it.cancel() }
        statusRefreshJobs.clear()
        snapshotStatusJobs.values.toList().forEach { it.cancel() }
        snapshotStatusJobs.clear()
        wateringStatusJobs.values.toList().forEach { it.cancel() }
        wateringStatusJobs.clear()
        waterConsumptionJobs.values.toList().forEach { it.cancel() }
        waterConsumptionJobs.clear()
        wateringHistoryJobs.values.toList().forEach { it.cancel() }
        wateringHistoryJobs.clear()
        wateringHistoryCache.clear()
        deviceListRefreshJob?.cancel()
        deviceListRefreshJob = null
        wateringHistoryRefreshJob?.cancel()
        wateringHistoryRefreshJob = null
        deviceControlRefreshJob?.cancel()
        deviceControlRefreshJob = null
        controlOperationJobs.values.toList().forEach { it.cancel() }
        controlOperationJobs.clear()
        suppressedOperationIds.clear()
    }

    private fun fetchDevices() {
        viewModelScope.launch {
            _isDevicesLoading.value = true
            _error.value = null
            startDeviceListAutoRefresh()
            try {
                refreshDevicesOnce()
            } catch (e: Exception) {
                handleApiError(e)
                if (!isBackendUnavailableError(e) && (e !is HttpException || e.code() != 401)) {
                    _error.value = "$DEVICES_LOAD_ERROR_PREFIX ${readableError(e)}"
                }
            } finally {
                _isDevicesLoading.value = false
            }
        }
    }

    fun openDeviceControl(device: Device) {
        _selectedDeviceName.value = device.name
        _deviceControl.value = DeviceControlUiState()
        _currentScreen.value = Screen.DeviceControl(device)
        loadDeviceTypes()
        refreshDeviceControl(device)
    }

    private fun loadDeviceTypes() {
        if (deviceTypesLoaded) return
        viewModelScope.launch {
            runCatching { Repository.api.getDeviceTypes().types }
                .onSuccess { types ->
                    _deviceTypes.value = types
                    deviceTypesLoaded = true
                }
                .onFailure { error ->
                    Log.d(TAG, "Device types load failed: ${error.message}", error)
                }
        }
    }

    fun closeDeviceControl() {
        deviceControlRefreshJob?.cancel()
        deviceControlRefreshJob = null
        controlOperationJobs.values.toList().forEach { it.cancel() }
        controlOperationJobs.clear()
        _currentScreen.value = Screen.Devices
    }

    fun openDetectedWateringHistory(device: Device) {
        _selectedDeviceName.value = device.name
        _detectedWateringHistory.value = DetectedWateringHistoryUiState()
        _currentScreen.value = Screen.DetectedWateringHistory(device)
        refreshDetectedWateringHistory(device)
    }

    fun closeDetectedWateringHistory() {
        _currentScreen.value = Screen.Devices
    }

    fun refreshDetectedWateringHistory(device: Device) {
        if (_detectedWateringHistory.value.isLoading) return
        viewModelScope.launch {
            _detectedWateringHistory.value =
                _detectedWateringHistory.value.copy(isLoading = true, error = null)
            try {
                val response = Repository.api.getDetectedWaterings(device.name)
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(
                        waterings = response.waterings,
                        nextOffset = response.nextOffset
                    )
            } catch (e: Exception) {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(error = readableError(e))
            } finally {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(isLoading = false)
            }
        }
    }

    fun loadMoreDetectedWaterings(device: Device) {
        val state = _detectedWateringHistory.value
        val offset = state.nextOffset ?: return
        if (state.isLoading) return
        viewModelScope.launch {
            _detectedWateringHistory.value = state.copy(isLoading = true, error = null)
            try {
                val response = Repository.api.getDetectedWaterings(
                    device.name,
                    offset = offset
                )
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(
                        waterings = (
                            _detectedWateringHistory.value.waterings +
                                response.waterings
                            ).distinctBy { it.id },
                        nextOffset = response.nextOffset
                    )
            } catch (e: Exception) {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(error = readableError(e))
            } finally {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(isLoading = false)
            }
        }
    }

    fun deleteDetectedWatering(device: Device, eventId: Int) {
        if (_detectedWateringHistory.value.deletingId != null) return
        viewModelScope.launch {
            _detectedWateringHistory.value =
                _detectedWateringHistory.value.copy(deletingId = eventId, error = null)
            try {
                Repository.api.invalidateDetectedWatering(device.name, eventId)
                _detectedWateringHistory.value = DetectedWateringHistoryUiState()
                refreshDetectedWateringHistory(device)
            } catch (e: Exception) {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(error = readableError(e))
            } finally {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(deletingId = null)
            }
        }
    }

    fun toggleDetectedWateringFertilized(device: Device, eventId: Int) {
        val state = _detectedWateringHistory.value
        if (state.fertilizingId != null) return
        val current = state.waterings.firstOrNull { it.id == eventId } ?: return
        val fertilized = !current.fertilized
        viewModelScope.launch {
            _detectedWateringHistory.value = state.copy(fertilizingId = eventId, error = null)
            try {
                Repository.api.setDetectedWateringFertilized(
                    device.name, eventId, SetFertilizedRequest(fertilized)
                )
                _detectedWateringHistory.value = _detectedWateringHistory.value.copy(
                    waterings = _detectedWateringHistory.value.waterings.map {
                        if (it.id == eventId) it.copy(fertilized = fertilized) else it
                    }
                )
            } catch (e: Exception) {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(error = readableError(e))
            } finally {
                _detectedWateringHistory.value =
                    _detectedWateringHistory.value.copy(fertilizingId = null)
            }
        }
    }

    fun refreshDeviceControl(device: Device, showLoading: Boolean = true) {
        if (showLoading && _deviceControl.value.isLoading) return
        if (deviceControlRefreshJob?.isActive == true) return
        deviceControlRefreshJob = viewModelScope.launch {
            if (showLoading) {
                _deviceControl.value = _deviceControl.value.copy(isLoading = true, error = null)
            }
            try {
                refreshPendingOperations(device)
                fetchDeviceStatus(device)
            } catch (e: Exception) {
                if (showLoading) {
                    _deviceControl.value = _deviceControl.value.copy(error = readableError(e))
                }
            } finally {
                if (showLoading) {
                    _deviceControl.value = _deviceControl.value.copy(isLoading = false)
                }
                deviceControlRefreshJob = null
            }
        }
    }

    private suspend fun refreshPendingOperations(device: Device) {
        val recent = Repository.api.getDeviceOperations(device.name).operations
        val active = Repository.api.getDeviceOperations(
            device.name,
            activeOnly = true
        ).operations
        val visible = active.filter { it.type in controlOperationTypes }
        val recentIds = recent.map { it.operationId }.toSet()
        val rememberedFailures = _deviceControl.value.pendingOperations.filter {
            it.status in listOf("error", "timeout") && it.operationId !in recentIds
        }
        _deviceControl.value = _deviceControl.value.copy(
            pendingOperations = stableQueueOrder(visible + rememberedFailures),
            recentOperations = stableRecentOrder(recent)
        )
        updatePendingControlFlag(device.name, visible)
        visible.forEach { operation ->
            trackControlOperation(device, operation.operationId)
        }
    }

    private fun runControlCommand(
        device: Device,
        successMessage: String,
        command: suspend () -> OperationResponse
    ) {
        if (_deviceControl.value.isLoading) return
        viewModelScope.launch {
            _deviceControl.value = _deviceControl.value.copy(isLoading = true, message = null, error = null)
            try {
                val operation = command()
                _deviceControl.value = _deviceControl.value.copy(
                    pendingOperations = stableQueueOrder(
                        listOf(operation) + _deviceControl.value.pendingOperations.filterNot {
                            it.operationId == operation.operationId
                        }
                    ),
                    recentOperations = stableRecentOrder(
                        listOf(operation) + _deviceControl.value.recentOperations.filterNot {
                            it.operationId == operation.operationId
                        }
                    )
                )
                updatePendingControlFlag(device.name, _deviceControl.value.pendingOperations)
                trackControlOperation(device, operation.operationId)
                _deviceControl.value = _deviceControl.value.copy(message = successMessage)
            } catch (e: Exception) {
                _deviceControl.value = _deviceControl.value.copy(error = readableError(e))
            } finally {
                _deviceControl.value = _deviceControl.value.copy(isLoading = false)
            }
        }
    }

    private fun trackControlOperation(device: Device, operationId: String) {
        if (controlOperationJobs[operationId]?.isActive == true) return
        controlOperationJobs.remove(operationId)?.cancel()
        controlOperationJobs[operationId] = viewModelScope.launch {
            try {
                while (true) {
                    try {
                        val operation = Repository.api.getOperationStatus(operationId)
                        _deviceControl.value = _deviceControl.value.copy(
                            pendingOperations = stableQueueOrder(
                                if (operation.status == "success" || operation.status == "cancelled") {
                                    _deviceControl.value.pendingOperations.filterNot { it.operationId == operationId }
                                } else {
                                    listOf(operation) + _deviceControl.value.pendingOperations.filterNot {
                                        it.operationId == operationId
                                    }
                                }
                            ),
                            recentOperations = stableRecentOrder(
                                listOf(operation) + _deviceControl.value.recentOperations.filterNot {
                                    it.operationId == operationId
                                }
                            )
                        )
                        updatePendingControlFlag(device.name, _deviceControl.value.pendingOperations)
                        if (operation.status in listOf("success", "error", "timeout", "cancelled")) {
                            if (operation.status == "success") {
                                fetchDeviceStatus(device)
                                if (operation.dryWeightG != null ||
                                    operation.wetWeightG != null ||
                                    operation.wateringLossThresholdPercent != null
                                ) {
                                    loadWateringParameters(device)
                                }
                            }
                            break
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Control operation poll retry operation_id=$operationId", e)
                    }
                    delay(1.seconds)
                }
            } finally {
                controlOperationJobs.remove(operationId)
            }
        }
    }

    fun setSleep(device: Device, enabled: Boolean) = runControlCommand(
        device,
        if (enabled) "Sleep enable command queued" else "Sleep disable command queued"
    ) {
        if (enabled) Repository.api.enableSleep(device.name) else Repository.api.disableSleep(device.name)
    }

    fun setSleepInterval(device: Device, minutes: Int) =
        runControlCommand(device, "Sleep interval command queued") {
            Repository.api.setSleepInterval(device.name, SleepIntervalRequest(minutes))
        }

    fun captureZero(device: Device) = runControlCommand(device, "Zero command queued") {
        Repository.api.captureZero(device.name)
    }

    fun calibrate(device: Device, weightG: Double) = runControlCommand(device, "Calibration command queued") {
        Repository.api.calibrate(device.name, CalibrationRequest(weightG))
    }

    fun loadWateringParameters(device: Device) {
        viewModelScope.launch {
            runCatching { Repository.api.getWateringParameters(device.name) }
                .onSuccess { parameters ->
                    _wateringParameters.update { it + (device.name to parameters) }
                }
                .onFailure {
                    val error = it as? Exception
                    if (error == null || !isBackendUnavailableError(error)) {
                        val detail = error?.let(::readableError) ?: it.message.orEmpty()
                        _error.value = "Failed to load watering parameters: $detail"
                    }
                }
        }
    }

    fun saveWateringParameters(device: Device, dry: Int?, wet: Int?, threshold: Int?) {
        viewModelScope.launch {
            try {
                val parameters = Repository.api.updateWateringParameters(
                    device.name, WateringParametersRequest(dry, wet, threshold)
                )
                _wateringParameters.update { it + (device.name to parameters) }
                parameters.operationId?.let { trackControlOperation(device, it) }
            } catch (e: Exception) {
                if (!isBackendUnavailableError(e)) {
                    _error.value = "Failed to save watering parameters: ${readableError(e)}"
                }
            }
        }
    }

    fun updateDeviceConfig(
        device: Device,
        deviceType: String,
        name: String,
        tareWeightG: Int?
    ) = runControlCommand(device, "Configuration command queued") {
        Repository.api.updateConfig(
            device.name,
            DeviceConfigRequest(deviceType, name, tareWeightG = tareWeightG)
        )
    }

    fun clearDeviceQueue(device: Device) {
        if (_deviceControl.value.isLoading) return
        viewModelScope.launch {
            _deviceControl.value = _deviceControl.value.copy(isLoading = true, message = null, error = null)
            try {
                val result = Repository.api.clearQueue(device.name)
                refreshPendingOperations(device)
                _deviceControl.value = _deviceControl.value.copy(message = "Cleared commands: ${result.cleared}")
            } catch (e: Exception) {
                _deviceControl.value = _deviceControl.value.copy(error = readableError(e))
            } finally {
                _deviceControl.value = _deviceControl.value.copy(isLoading = false)
            }
        }
    }

    fun showLoginError(message: String) {
        _error.value = message
    }

    private suspend fun refreshDevicesOnce() {
        val response = withTimeout(DEVICES_TIMEOUT_MS.milliseconds) {
            Repository.api.getDevices()
        }
        val oldDeviceNames = _devices.value.map { it.name }.toSet()
        val newDeviceNames = response.devices.map { it.name }.toSet()
        val removedDeviceNames = oldDeviceNames - newDeviceNames

        val devices = mergeDevicesPreservingOrder(_devices.value, response.devices)
        _devices.value = devices
        _deviceStates.update { current ->
            devices.associate { device ->
                val old = current[device.name] ?: DeviceUIState()
                device.name to old.copy(
                    hasPendingControlOperations = device.hasPendingOperations
                )
            }
        }
        if (_error.value?.startsWith(DEVICES_LOAD_ERROR_PREFIX) == true) {
            _error.value = null
        }

        removedDeviceNames.forEach { deviceName ->
            statusRefreshJobs.remove(deviceName)?.cancel()
            snapshotStatusJobs.remove(deviceName)?.cancel()
            wateringStatusJobs.remove(deviceName)?.cancel()
            waterConsumptionJobs.remove(deviceName)?.cancel()
            operationJobs.remove(deviceName)?.cancel()
        }

        val openDeviceName = when (val screen = _currentScreen.value) {
            is Screen.DeviceControl -> screen.device.name
            is Screen.DetectedWateringHistory -> screen.device.name
            else -> null
        }
        if (openDeviceName != null && openDeviceName in removedDeviceNames) {
            deviceControlRefreshJob?.cancel()
            deviceControlRefreshJob = null
            controlOperationJobs.values.toList().forEach { it.cancel() }
            controlOperationJobs.clear()
            _deviceControl.value = DeviceControlUiState()
            _detectedWateringHistory.value = DetectedWateringHistoryUiState()
            _currentScreen.value = Screen.Devices
        }

        if (_selectedDeviceName.value?.let(removedDeviceNames::contains) == true) {
            _selectedDeviceName.value = devices.firstOrNull()?.name
        }

        if (activePollingDeviceName != null) {
            val activeDevice = devices.firstOrNull {
                it.name == activePollingDeviceName
            } ?: devices.firstOrNull()
            setActiveDevice(activeDevice)
        } else if (_selectedDeviceName.value == null) {
            setActiveDevice(devices.firstOrNull())
        }
        if (!wateringHistoryCache.containsKey(false)) {
            fetchWateringHistory(successfulOnly = false)
        }
        restoreWateringOperations(devices)
    }

    private fun configureDevicePolling(device: Device) {
        if (device.type == DeviceType.TANK.apiValue) {
            statusRefreshJobs.remove(device.name)?.cancel()
            snapshotStatusJobs.remove(device.name)?.cancel()
            startWateringStatusAutoRefresh(device)
            viewModelScope.launch {
                fetchWateringStatus(device)
            }
            waterConsumptionJobs.remove(device.name)?.cancel()
        } else {
            wateringStatusJobs.remove(device.name)?.cancel()
            startWaterConsumptionAutoRefresh(device)
            startStatusAutoRefresh(device)
            viewModelScope.launch {
                fetchWaterConsumption(device)
            }
        }
    }

    fun setActiveDevice(device: Device?) {
        val expectedPollingIsActive = when (device?.type) {
            DeviceType.TANK.apiValue -> wateringStatusJobs[device.name]?.isActive == true
            DeviceType.PLANT.apiValue ->
                statusRefreshJobs[device.name]?.isActive == true &&
                    waterConsumptionJobs[device.name]?.isActive == true
            else -> false
        }
        if (activePollingDeviceName == device?.name && expectedPollingIsActive) return

        statusRefreshJobs.values.toList().forEach { it.cancel() }
        statusRefreshJobs.clear()
        snapshotStatusJobs.values.toList().forEach { it.cancel() }
        snapshotStatusJobs.clear()
        wateringStatusJobs.values.toList().forEach { it.cancel() }
        wateringStatusJobs.clear()
        waterConsumptionJobs.values.toList().forEach { it.cancel() }
        waterConsumptionJobs.clear()

        activePollingDeviceName = device?.name
        if (device == null) return

        _selectedDeviceName.value = device.name
        configureDevicePolling(device)
    }

    private fun mergeDevicesPreservingOrder(current: List<Device>, incoming: List<Device>): List<Device> {
        if (current.isEmpty()) return sortDevicesForDisplay(incoming)

        val incomingByName = incoming.associateBy { it.name }
        val currentNames = current.map { it.name }.toSet()
        val existingDevices = current.mapNotNull { oldDevice ->
            incomingByName[oldDevice.name]
        }
        val addedDevices = incoming.filter { it.name !in currentNames }
        return sortDevicesForDisplay(existingDevices + addedDevices)
    }

    private fun sortDevicesForDisplay(devices: List<Device>): List<Device> {
        return devices.sortedBy { if (it.type == DeviceType.TANK.apiValue) 1 else 0 }
    }

    private fun startDeviceListAutoRefresh() {
        if (deviceListRefreshJob?.isActive == true) return
        deviceListRefreshJob = viewModelScope.launch {
            while (true) {
                delay(DEVICE_LIST_POLL_INTERVAL_MS.milliseconds)
                try {
                    refreshDevicesOnce()
                } catch (e: Exception) {
                    if (e is HttpException && e.code() == 401) {
                        handleApiError(e)
                    } else {
                        Log.d(TAG, "Device list refresh failed: ${readableError(e)}", e)
                    }
                }
            }
        }
    }

    private fun startWateringHistoryAutoRefresh() {
        if (wateringHistoryRefreshJob?.isActive == true) return
        wateringHistoryRefreshJob = viewModelScope.launch {
            while (true) {
                delay(WATERING_HISTORY_POLL_INTERVAL_MS.milliseconds)
                fetchWateringHistory(successfulOnly = _wateringHistory.value.successfulOnly)
            }
        }
    }

    fun setWateringHistoryVisible(visible: Boolean) {
        if (visible) {
            fetchWateringHistory(successfulOnly = _wateringHistory.value.successfulOnly)
            startWateringHistoryAutoRefresh()
        } else {
            wateringHistoryRefreshJob?.cancel()
            wateringHistoryRefreshJob = null
        }
    }

    private fun handleApiError(e: Exception) {
        viewModelScope.launch {
            if (e is HttpException && e.code() == 401) {
                _error.value = "Session expired. Please log in again."
                clearActiveSession()
            }
        }
    }

    private fun readableError(e: Exception): String {
        if (e is TimeoutCancellationException) {
            return "request timed out"
        }
        if (e is HttpException) {
            return "HTTP ${e.code()} ${e.response()?.errorBody()?.string().orEmpty()}".trim()
        }
        if (isIncompleteJsonError(e)) {
            return "server returned an incomplete response"
        }
        return e.message ?: e::class.java.simpleName
    }

    private fun isIncompleteJsonError(e: Exception): Boolean {
        val message = e.message?.lowercase().orEmpty()
        return "unexpected end" in message || "end of input" in message || "end of string" in message
    }

    private fun startStatusAutoRefresh(device: Device) {
        if (statusRefreshJobs[device.name]?.isActive == true) return
        statusRefreshJobs.remove(device.name)?.cancel()
        startSnapshotStatusAutoRefresh(device)
        statusRefreshJobs[device.name] = viewModelScope.launch {
            while (true) {
                val startedAt = System.currentTimeMillis()
                val liveAvailable = fetchLiveStatusForPolling(device)
                if (liveAvailable) {
                    snapshotStatusJobs.remove(device.name)?.cancel()
                } else {
                    startSnapshotStatusAutoRefresh(device)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                delay((LIVE_STATUS_POLL_INTERVAL_MS - elapsed).coerceAtLeast(0L).milliseconds)
            }
        }
    }

    private fun startSnapshotStatusAutoRefresh(device: Device) {
        if (snapshotStatusJobs[device.name]?.isActive == true) return
        snapshotStatusJobs.remove(device.name)?.cancel()
        snapshotStatusJobs[device.name] = viewModelScope.launch {
            while (true) {
                val startedAt = System.currentTimeMillis()
                if (!operationJobs.containsKey(device.name)) {
                    fetchSnapshotStatusForPolling(device)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                delay((SNAPSHOT_STATUS_POLL_INTERVAL_MS - elapsed).coerceAtLeast(0L).milliseconds)
            }
        }
    }

    private suspend fun fetchLiveStatusForPolling(device: Device): Boolean {
        return try {
            val live = withTimeout(STATUS_TIMEOUT_MS.milliseconds) {
                Repository.api.getLiveStatus(device.name)
            }
            val available = live.status == "online" && live.available
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                current + (device.name to old.copy(
                    latestStatus = if (available) live else old.latestStatus,
                    isOnline = available
                ))
            }
            available
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) {
                handleApiError(e)
            } else {
                _deviceStates.update { current ->
                    val old = current[device.name] ?: DeviceUIState()
                    current + (device.name to old.copy(isOnline = false))
                }
                Log.d(TAG, "Live status poll failed for ${device.name}: ${readableError(e)}", e)
            }
            false
        }
    }

    private suspend fun fetchSnapshotStatusForPolling(device: Device) {
        try {
            val latest = fetchLatestStatus(device)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                if (old.isOnline == true) {
                    current
                } else {
                    current + (device.name to old.copy(latestStatus = latest))
                }
            }
            if (latest.pendingOperationId != null) {
                trackOperation(device, latest.pendingOperationId, isWatering = false)
            } else if (!latest.available) {
                val operation = Repository.api.queueStatusRefresh(device.name)
                trackOperation(device, operation.operationId, isWatering = false)
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) {
                handleApiError(e)
            } else {
                Log.d(TAG, "Snapshot status poll failed for ${device.name}: ${readableError(e)}", e)
            }
        }
    }

    private suspend fun restoreWateringOperations(devices: List<Device>) {
        try {
            devices.forEach { device ->
                val deviceStarts = Repository.api.getDeviceOperations(
                    device.name,
                    activeOnly = true
                ).operations.filter {
                    it.type == "watering_start"
                }
                val active = deviceStarts
                    .filter { !isFinalStatus(it.status) && it.operationId !in suppressedOperationIds }
                    .maxByOrNull { it.updatedAt }
                active?.let {
                    if (it.status !in listOf("queued", "sending")) {
                        val activeEvents = fetchOperationEventsOrEmpty(it.operationId)
                        _deviceStates.update { current ->
                            val old = current[device.name] ?: DeviceUIState()
                            current + (device.name to old.copy(
                                activeOperation = it,
                                activeOperationEvents = activeEvents,
                                isWateringTask = true,
                                isStatusRefreshing = false
                            ))
                        }
                    } else {
                        setPlannedWatering(device.name, it)
                    }
                    trackOperation(device, it.operationId, isWatering = true)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Watering operation restore failed: ${readableError(e)}", e)
        }
    }

    private fun startWateringStatusAutoRefresh(device: Device) {
        if (wateringStatusJobs.containsKey(device.name)) return
        wateringStatusJobs[device.name] = viewModelScope.launch {
            while (true) {
                delay(DEVICE_POLL_INTERVAL_MS.milliseconds)
                fetchWateringStatus(device)
            }
        }
    }

    private fun startWaterConsumptionAutoRefresh(device: Device) {
        if (waterConsumptionJobs.containsKey(device.name)) return
        waterConsumptionJobs[device.name] = viewModelScope.launch {
            while (true) {
                delay(WATER_CONSUMPTION_POLL_INTERVAL_MS.milliseconds)
                fetchWaterConsumption(device)
            }
        }
    }

    private suspend fun fetchWaterConsumption(device: Device) {
        try {
            val response = Repository.api.getWaterConsumption(device.name)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                current + (device.name to old.copy(waterConsumption = response.days))
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) {
                handleApiError(e)
            } else {
                Log.d(TAG, "Water consumption refresh failed for ${device.name}: ${readableError(e)}", e)
            }
        }
    }

    private suspend fun fetchWateringStatus(device: Device) {
        try {
            val status = Repository.api.getWateringStatus(device.name)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                val latestStatus = status.result?.let {
                    LatestStatusResponse(
                        device = device.name,
                        status = "online",
                        source = status.source,
                        available = status.available,
                        result = it,
                        resultReceivedAt = status.resultReceivedAt,
                        operationId = status.operationId,
                        pendingOperationId = status.pendingOperationId,
                        pendingOperationStatus = status.pendingOperationStatus,
                        error = null
                    )
                } ?: old.latestStatus
                current + (device.name to old.copy(
                    latestStatus = latestStatus,
                    wateringStatus = status,
                    plannedWatering = status.plannedWatering,
                    isOnline = status.source == "live" && status.available
                ))
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) {
                handleApiError(e)
            } else {
                _deviceStates.update { current ->
                    val old = current[device.name] ?: DeviceUIState()
                    current + (device.name to old.copy(isOnline = false))
                }
                Log.d(TAG, "Watering status refresh failed for ${device.name}: ${readableError(e)}", e)
            }
        }
    }

    fun setWateringHistorySuccessfulOnly(successfulOnly: Boolean) {
        if (_wateringHistory.value.successfulOnly == successfulOnly) return
        val cachedOperations = wateringHistoryCache[successfulOnly].orEmpty()
        _wateringHistory.value = _wateringHistory.value.copy(
            operations = cachedOperations,
            successfulOnly = successfulOnly,
            isLoading = cachedOperations.isEmpty(),
            error = null
        )
        fetchWateringHistory(successfulOnly = successfulOnly)
    }

    private fun refreshWateringHistoryAfterOperation() {
        fetchWateringHistory(successfulOnly = false, force = true)
        if (_wateringHistory.value.successfulOnly || wateringHistoryCache.containsKey(true)) {
            fetchWateringHistory(successfulOnly = true, force = true)
        }
    }

    private fun fetchWateringHistory(successfulOnly: Boolean, force: Boolean = false) {
        if (!force && wateringHistoryJobs[successfulOnly]?.isActive == true) return
        wateringHistoryJobs[successfulOnly] = viewModelScope.launch {
            val isVisibleFilter = _wateringHistory.value.successfulOnly == successfulOnly
            if (isVisibleFilter) {
                _wateringHistory.value = _wateringHistory.value.copy(
                    isLoading = _wateringHistory.value.operations.isEmpty(),
                    error = null
                )
            }
            try {
                val response = Repository.api.getWateringHistory(successfulOnly)
                wateringHistoryCache[successfulOnly] = response.operations
                if (_wateringHistory.value.successfulOnly == successfulOnly) {
                    _wateringHistory.value = _wateringHistory.value.copy(
                        operations = response.operations,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    handleApiError(e)
                } else {
                    if (_wateringHistory.value.successfulOnly == successfulOnly) {
                        val keepQuiet = isIncompleteJsonError(e) && _wateringHistory.value.operations.isNotEmpty()
                        _wateringHistory.value = _wateringHistory.value.copy(
                            isLoading = false,
                            error = if (keepQuiet) null else "History refresh failed: ${readableError(e)}"
                        )
                    }
                    Log.d(TAG, "Watering history refresh failed: ${readableError(e)}", e)
                }
            } finally {
                wateringHistoryJobs.remove(successfulOnly)
            }
        }
    }

    suspend fun fetchDeviceStatus(device: Device) {
        val mutex = statusRequestMutexes.getOrPut(device.name) { Mutex() }
        mutex.withLock {
            fetchDeviceStatusLocked(device)
        }
    }

    private suspend fun fetchDeviceStatusLocked(device: Device) {
        try {
            val wasOnline = _deviceStates.value[device.name]?.isOnline
            val shouldRequestLive = wasOnline == true
            val latest = fetchStatusForConnectivity(device, shouldRequestLive)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                current + (device.name to old.copy(
                    latestStatus = latest,
                    isOnline = latest.status == "online"
                ))
            }
            if (latest.pendingOperationId != null) {
                trackOperation(device, latest.pendingOperationId, isWatering = false)
            } else if (!latest.available) {
                val operation = Repository.api.queueStatusRefresh(device.name)
                trackOperation(device, operation.operationId, isWatering = false)
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) {
                handleApiError(e)
            } else {
                _deviceStates.update { current ->
                    val old = current[device.name] ?: DeviceUIState()
                    current + (device.name to old.copy(isOnline = false))
                }
                Log.d(TAG, "Status refresh failed for ${device.name}: ${readableError(e)}", e)
            }
        }
    }

    fun startWatering(device: Device, grams: Double) {
        if (_deviceStates.value[device.name]?.isActionLoading == true) return
        viewModelScope.launch {
            setActionLoading(device.name, true)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                current + (device.name to old.copy(
                    lastFinishedNeedsAck = false
                ))
            }
            try {
                val resp = Repository.api.startWatering(device.name, WateringStartRequest(grams))
                setPlannedWatering(device.name, resp, grams)
                trackOperation(device, resp.operationId, isWatering = true)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    handleApiError(e)
                } else if (!isBackendUnavailableError(e)) {
                    _error.value = "Start failed: ${readableError(e)}"
                }
            } finally {
                setActionLoading(device.name, false)
            }
        }
    }

    fun stopWatering(device: Device) {
        viewModelScope.launch {
            val activeOperationId = _deviceStates.value[device.name]?.activeOperation?.operationId
            activeOperationId?.let { suppressedOperationIds.add(it) }
            operationJobs[device.name]?.cancel()
            operationJobs.remove(device.name)
            _deviceStates.update { current ->
                val old = current[device.name] ?: DeviceUIState()
                current + (device.name to old.copy(
                    activeOperation = null,
                    activeOperationEvents = emptyList(),
                    plannedWatering = null,
                    lastFinishedNeedsAck = false,
                    isStatusRefreshing = false,
                    isWateringTask = false
                ))
            }
            try {
                Repository.api.stopWatering(device.name)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    handleApiError(e)
                } else {
                    Log.d(TAG, "Silent stop failed for ${device.name}: ${readableError(e)}", e)
                }
            }
        }
    }

    private fun trackOperation(device: Device, operationId: String, isWatering: Boolean) {
        if (operationId in suppressedOperationIds) return
        if (operationJobs[device.name]?.isActive == true) return
        operationJobs[device.name] = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (true) {
                if (operationId in suppressedOperationIds) break
                try {
                    val opStatus = Repository.api.getOperationStatus(operationId)
                    if (operationId in suppressedOperationIds) break
                    if (isWatering && opStatus.status in listOf("queued", "sending")) {
                        setPlannedWatering(device.name, opStatus)
                        delay(7.seconds)
                        continue
                    }
                    val events = fetchOperationEventsOrEmpty(operationId)
                    _deviceStates.update { current ->
                        val old = current[device.name] ?: DeviceUIState()
                        current + (device.name to old.copy(
                                activeOperation = opStatus,
                                activeOperationEvents = events,
                                plannedWatering = if (isWatering) null else old.plannedWatering,
                                isWateringTask = isWatering,
                                isStatusRefreshing = !isWatering
                            ))
                    }
                    if (isFinalStatus(opStatus.status)) {
                        _deviceStates.update { current ->
                            val old = current[device.name] ?: DeviceUIState()
                            current + (device.name to old.copy(
                                activeOperation = null, 
                                activeOperationEvents = emptyList(),
                                plannedWatering = if (isWatering) null else old.plannedWatering,
                                lastFinishedOperation = if (isWatering) opStatus else old.lastFinishedOperation,
                                lastFinishedOperationEvents = if (isWatering) events else old.lastFinishedOperationEvents,
                                lastFinishedNeedsAck = if (isWatering) true else old.lastFinishedNeedsAck,
                                isStatusRefreshing = false,
                                isWateringTask = false
                            ))
                        }
                        if (device.type == DeviceType.TANK.apiValue) {
                            fetchWateringStatus(device)
                            refreshWateringHistoryAfterOperation()
                        } else {
                            fetchDeviceStatus(device)
                        }
                        break
                    }
                } catch (e: Exception) {
                    if (e is HttpException && e.code() == 401) {
                        _error.value = "Session expired. Please log in again."
                        clearActiveSession()
                        break
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                delay(if (elapsed < 10000) 2.seconds else 7.seconds)
            }
            operationJobs.remove(device.name)
        }
    }

    private suspend fun fetchStatusForConnectivity(
        device: Device,
        requestLive: Boolean
    ): LatestStatusResponse {
        if (requestLive) {
            return fetchLiveOrLatest(device)
        }

        val latest = fetchLatestStatus(device)
        return if (latest.status == "online") {
            fetchLiveOrLatest(device)
        } else {
            latest
        }
    }

    private suspend fun fetchLiveOrLatest(device: Device): LatestStatusResponse {
        return try {
            val live = withTimeout(STATUS_TIMEOUT_MS.milliseconds) {
                Repository.api.getLiveStatus(device.name)
            }
            if (live.status == "online" && live.available) {
                live
            } else {
                Log.d(TAG, "Live status unavailable for ${device.name}; falling back to latest snapshot")
                fetchLatestStatus(device)
            }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 401) throw e
            if (isTimeoutError(e)) {
                Log.d(TAG, "Live status timed out for ${device.name}; falling back to latest snapshot")
            } else {
                Log.d(TAG, "Live status failed for ${device.name}; falling back to latest snapshot", e)
            }
            fetchLatestStatus(device)
        }
    }

    private suspend fun fetchLatestStatus(device: Device): LatestStatusResponse {
        return withTimeout(STATUS_TIMEOUT_MS.milliseconds) {
            Repository.api.getLatestStatus(device.name)
        }
    }

    private fun isTimeoutError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is TimeoutCancellationException || current is SocketTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isBackendUnavailableError(error: Throwable): Boolean {
        if (error is HttpException && error.code() in 500..599) return true
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException || current is TimeoutCancellationException) return true
            current = current.cause
        }
        return false
    }

    private fun setPlannedWatering(deviceName: String, operation: OperationResponse, fallbackTargetG: Double? = null) {
        val targetG = operation.targetG ?: fallbackTargetG ?: return
        _deviceStates.update { current ->
            val old = current[deviceName] ?: DeviceUIState()
            current + (deviceName to old.copy(
                plannedWatering = PlannedWatering(
                    operationId = operation.operationId,
                    targetG = targetG,
                    status = operation.status
                )
            ))
        }
    }

    private fun isFinalStatus(status: String): Boolean = status in listOf("success", "error", "timeout", "cancelled")

    private fun setActionLoading(deviceName: String, loading: Boolean) {
        _deviceStates.update { current ->
            val old = current[deviceName] ?: DeviceUIState()
            current + (deviceName to old.copy(isActionLoading = loading))
        }
    }

    private suspend fun fetchOperationEventsOrEmpty(operationId: String): List<OperationEvent> {
        return try {
            Repository.api.getOperationEvents(operationId).events
        } catch (e: Exception) {
            Log.d(TAG, "Operation events fetch failed operation_id=$operationId: ${readableError(e)}", e)
            emptyList()
        }
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "smart_watering_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearLegacyPlaintextPrefs(context: Context) {
        context.getSharedPreferences("smart_watering_prefs", Context.MODE_PRIVATE)
            .edit { clear() }
    }
}
