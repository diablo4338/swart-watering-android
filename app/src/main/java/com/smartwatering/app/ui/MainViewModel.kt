@file:Suppress("DEPRECATION")

package com.smartwatering.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smartwatering.app.data.*
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import kotlin.time.Duration.Companion.milliseconds

data class CardUiState(
    val card: DeviceCard? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class ActionSubmissionResult(
    val successful: Boolean,
    val error: String? = null,
)

typealias CardActionHandler = (
    String,
    CardRequest,
    Map<String, Any?>,
    Any?,
    ((ActionSubmissionResult) -> Unit)?,
) -> Unit

sealed class Screen {
    object Login : Screen()
    object Devices : Screen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "SmartWateringVM"
        const val LOGIN_TIMEOUT_MS = 15000L
        const val DEVICES_TIMEOUT_MS = 15000L
        const val DEVICE_LIST_POLL_INTERVAL_MS = 5000L
        const val BACKEND_RECOVERY_POLL_INTERVAL_MS = 15000L
        const val ERROR_VISIBILITY_MS = 2000L
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
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId
    private val _cards = MutableStateFlow<Map<String, CardUiState>>(emptyMap())
    val cards: StateFlow<Map<String, CardUiState>> = _cards
    private val _pendingActions = MutableStateFlow<Set<String>>(emptySet())
    val pendingActions: StateFlow<Set<String>> = _pendingActions
    private val _loadingBlocks = MutableStateFlow<Set<String>>(emptySet())
    val loadingBlocks: StateFlow<Set<String>> = _loadingBlocks
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

    private var deviceListRefreshJob: Job? = null
    private var errorDismissJob: Job? = null
    private val cardErrorDismissJobs = mutableMapOf<String, Job>()
    private val blockRefreshJobs = mutableMapOf<String, Job>()
    private val blockRevisions = mutableMapOf<String, Long>()
    private var activeDeviceId: String? = null
    private var openBlockId: String? = null

    init {
        clearLegacyPlaintextPrefs(application)
        checkAutoLogin()
        refreshAppRelease()
        monitorBackendRecovery()
    }

    fun login(username: String, password: String) =
        authenticate { Repository.api.login(LoginRequest(username, password)) }

    fun loginWithGoogle(idToken: String) =
        authenticate { Repository.api.loginWithGoogle(GoogleLoginRequest(idToken)) }

    private fun authenticate(request: suspend () -> LoginResponse) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = withTimeout(LOGIN_TIMEOUT_MS.milliseconds) { request() }
                Repository.setToken(response.token)
                prefs.edit { storeActiveSession(response) }
                _currentScreen.value = Screen.Devices
                fetchDevices()
            } catch (error: Exception) {
                showTransientError(if (error is HttpException && error.code() == 401) {
                    "Invalid credentials"
                } else "Login failed: ${readableError(error)}"
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { Repository.api.logout() }
                .onFailure { Log.d(TAG, "Remote logout failed", it) }
            clearActiveSession()
        }
    }

    fun showLoginError(message: String) { showTransientError(message) }

    fun setActiveDevice(device: Device?) {
        if (device?.id == activeDeviceId) return
        activeDeviceId = device?.id
        openBlockId = null
        _selectedDeviceId.value = device?.id
        cancelBlockRefreshes()
        if (device != null) loadCard(device, force = _cards.value[device.id]?.card == null)
    }

    fun refreshCard(device: Device) = loadCard(device, force = true)

    fun refreshActiveCard() {
        val deviceId = activeDeviceId ?: return
        val action = _cards.value[deviceId]?.card?.blocks
            ?.firstOrNull { it.kind == "device_overview" }
            ?.actions?.firstOrNull { it.id == "refresh_card" && it.enabled }
            ?: return
        action.request?.let { performAction("$deviceId:${action.id}", it) }
    }

    fun setOpenBlock(deviceId: String, blockId: String?) {
        if (deviceId != activeDeviceId) return
        openBlockId = blockId
        val card = _cards.value[deviceId]?.card ?: return
        scheduleBlockRefreshes(card)
        val block = card.blocks.firstOrNull { it.id == blockId } ?: return
        val href = block.refresh.href ?: return
        val key = "$deviceId:${block.id}"
        viewModelScope.launch {
            _loadingBlocks.update { it + key }
            try {
                val response = Repository.api.getCardBlock(href)
                replaceBlock(response)
                _cards.update { states ->
                    states[deviceId]?.let { states + (deviceId to it.copy(error = null)) }
                        ?: states
                }
                _cards.value[deviceId]?.card?.let(::scheduleBlockRefreshes)
            } catch (error: Exception) {
                handleRequestError(error, deviceId)
            } finally {
                _loadingBlocks.update { it - key }
            }
        }
    }

    suspend fun loadStatisticsBlock(deviceId: String, block: CardBlock): CardBlock {
        try {
            val response = Repository.api.getCardBlock(requireNotNull(block.refresh.href))
            require(response.deviceId == deviceId && response.block.id == block.id) {
                "Unexpected statistics response"
            }
            replaceBlock(response)
            return response.block
        } catch (error: HttpException) {
            if (error.code() == 401) clearActiveSession()
            throw error
        }
    }

    private fun loadCard(device: Device, force: Boolean) {
        if (!force && _cards.value[device.id]?.isLoading == true) return
        viewModelScope.launch {
            _cards.update {
                it + (device.id to (it[device.id] ?: CardUiState()).copy(
                    isLoading = true, error = null,
                ))
            }
            try {
                val card = Repository.api.getDeviceCard(device.cardHref)
                putCard(device.id, card)
                if (activeDeviceId == card.deviceId) scheduleBlockRefreshes(card)
            } catch (error: Exception) {
                handleRequestError(error, device.id)
            } finally {
                _cards.update { cards ->
                    cards[device.id]?.let { cards + (device.id to it.copy(isLoading = false)) }
                        ?: cards
                }
            }
        }
    }

    fun performAction(
        actionId: String,
        request: CardRequest,
        values: Map<String, Any?> = emptyMap(),
        controlValue: Any? = null,
        onComplete: ((ActionSubmissionResult) -> Unit)? = null,
    ) {
        if (actionId in _pendingActions.value) {
            onComplete?.invoke(ActionSubmissionResult(false))
            return
        }
        viewModelScope.launch {
            _pendingActions.update { it + actionId }
            try {
                val response = Repository.api.performCardAction(
                    request.href, bindBody(request, values, controlValue)
                )
                if (!response.accepted) {
                    onComplete?.invoke(ActionSubmissionResult(false))
                    return@launch
                }
                val newDeviceId = response.card.deviceId
                // An action can finish after the user has scrolled to another device.
                // Update the originating card without changing the current selection.
                putCard(newDeviceId, response.card)
                if (activeDeviceId == newDeviceId) scheduleBlockRefreshes(response.card)
                onComplete?.invoke(ActionSubmissionResult(true))
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 401) clearActiveSession()
                val protocolMessage = protocolError(error)
                if (protocolMessage != null) showTransientError(protocolMessage)
                else Log.d(TAG, "Action failed: $actionId", error)
                onComplete?.invoke(ActionSubmissionResult(false, protocolMessage))
            } finally {
                _pendingActions.update { it - actionId }
            }
        }
    }

    private fun bindBody(
        request: CardRequest,
        values: Map<String, Any?>,
        controlValue: Any?,
    ): Map<String, Any?> {
        val binding = request.body
        return when (binding.binding) {
            "none" -> emptyMap()
            "control_value" -> mapOf(requireNotNull(binding.property) to controlValue)
            "fields" -> binding.fields.mapNotNull { field ->
                values[field]?.let { (binding.properties[field] ?: field) to it }
            }.toMap()
            "literal" -> binding.value.orEmpty()
            "literal_and_control_value" -> binding.literal.orEmpty() +
                mapOf(requireNotNull(binding.property) to controlValue)
            else -> error("Unsupported request binding: ${binding.binding}")
        }
    }

    private fun scheduleBlockRefreshes(card: DeviceCard) {
        cancelBlockRefreshes()
        card.blocks.filter {
            it.refresh.mode == "poll" && it.refresh.href != null &&
                (it.slot in setOf("primary", "watering", "operations") || it.id == openBlockId)
        }
            .forEach { block ->
                val key = "${card.deviceId}:${block.id}"
                val interval = (block.refresh.intervalMs ?: 5000L).coerceIn(2000L, 300000L)
                blockRefreshJobs[key] = viewModelScope.launch {
                    while (activeDeviceId == card.deviceId) {
                        delay(interval.milliseconds)
                        try {
                            val response = Repository.api.getCardBlock(requireNotNull(block.refresh.href))
                            replaceBlock(response)
                            if (response.block.refresh.mode != "poll") break
                        } catch (error: Exception) {
                            if (error is HttpException && error.code() == 401) {
                                clearActiveSession()
                                break
                            }
                            Log.d(TAG, "Block refresh failed: $key", error)
                        }
                    }
                }
            }
    }

    private fun cancelBlockRefreshes() {
        blockRefreshJobs.values.forEach { it.cancel() }
        blockRefreshJobs.clear()
    }

    private fun replaceBlock(response: CardBlockResponse) {
        val revisionKey = "${response.deviceId}:${response.block.id}"
        val currentRevision = blockRevisions[revisionKey]
        if (currentRevision != null && response.blockRevision < currentRevision) return
        blockRevisions[revisionKey] = response.blockRevision
        _cards.update { states ->
            val state = states[response.deviceId] ?: return@update states
            val current = state.card ?: return@update states
            states + (response.deviceId to state.copy(
                card = current.copy(
                    blocks = current.blocks.map {
                        if (it.id == response.block.id) response.block else it
                    },
                ),
                error = null,
            ))
        }
    }

    private fun putCard(previousId: String, card: DeviceCard) {
        blockRevisions.keys.removeAll { key ->
            key.startsWith("$previousId:") || key.startsWith("${card.deviceId}:")
        }
        _cards.update { states ->
            val next = if (previousId == card.deviceId) states else states - previousId
            next + (card.deviceId to CardUiState(card = card))
        }
    }

    private fun fetchDevices() {
        viewModelScope.launch {
            _isDevicesLoading.value = true
            try {
                refreshDevicesOnce()
                startDeviceListAutoRefresh()
            } catch (error: Exception) {
                handleRequestError(error)
            } finally {
                _isDevicesLoading.value = false
            }
        }
    }

    private suspend fun refreshDevicesOnce() {
        val incoming = withTimeout(DEVICES_TIMEOUT_MS.milliseconds) {
            Repository.api.getDevices().devices
        }
        val previousDevices = _devices.value
        val oldOrder = previousDevices.map { it.id }
        _devices.value = incoming.sortedBy {
            oldOrder.indexOf(it.id).takeIf { index -> index >= 0 }
                ?: Int.MAX_VALUE
        }
        val currentId = activeDeviceId
        if (currentId == null) {
            _devices.value.firstOrNull()?.let(::setActiveDevice)
        } else if (_devices.value.none { it.id == currentId }) {
            _devices.value.firstOrNull()?.let(::setActiveDevice)
        }
    }

    private fun startDeviceListAutoRefresh() {
        if (deviceListRefreshJob?.isActive == true) return
        deviceListRefreshJob = viewModelScope.launch {
            while (true) {
                delay(DEVICE_LIST_POLL_INTERVAL_MS.milliseconds)
                runCatching { refreshDevicesOnce() }
                    .onFailure { Log.d(TAG, "Device list refresh failed", it) }
            }
        }
    }

    fun retryBackendConnection() {
        viewModelScope.launch {
            runCatching { Repository.api.getLatestAppRelease() }
            if (Repository.backendAvailability.value == BackendAvailability.AVAILABLE &&
                _currentScreen.value is Screen.Devices
            ) runCatching { refreshDevicesOnce() }
        }
    }

    fun refreshAppRelease() {
        viewModelScope.launch {
            _isAppReleaseLoading.value = true
            _appReleaseError.value = null
            try { _latestAppRelease.value = Repository.api.getLatestAppRelease() }
            catch (error: Exception) {
                _appReleaseError.value = protocolError(error)
                Log.d(TAG, "App release refresh failed", error)
            }
            finally { _isAppReleaseLoading.value = false }
        }
    }

    private fun monitorBackendRecovery() {
        viewModelScope.launch {
            while (true) {
                delay(BACKEND_RECOVERY_POLL_INTERVAL_MS.milliseconds)
                if (Repository.usingFallback.value) {
                    if (Repository.probePrimaryBackend() && !Repository.hasPrimaryToken()) {
                        clearSession(preserveBackendTokens = true)
                    }
                } else if (Repository.backendAvailability.value == BackendAvailability.UNAVAILABLE) {
                    retryBackendConnection()
                }
            }
        }
    }

    private fun checkAutoLogin() {
        val now = System.currentTimeMillis() / 1000L
        val primaryToken = prefs.getString(AUTH_TOKEN_PRIMARY, null)
            ?: prefs.getString("auth_token", null)
        val primaryExpiresAt = prefs.getLong(
            AUTH_EXPIRES_AT_PRIMARY, prefs.getLong("auth_expires_at", 0L)
        )
        val fallbackToken = prefs.getString(AUTH_TOKEN_FALLBACK, null)
        val fallbackExpiresAt = prefs.getLong(AUTH_EXPIRES_AT_FALLBACK, 0L)
        val validPrimary = primaryToken?.takeIf { primaryExpiresAt > now }
        val validFallback = fallbackToken?.takeIf { fallbackExpiresAt > now }
        if (validPrimary != null || validFallback != null) {
            Repository.restoreTokens(validPrimary, validFallback)
            _currentScreen.value = Screen.Devices
            fetchDevices()
        } else clearSession()
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

    private fun clearActiveSession() {
        prefs.edit {
            if (Repository.usingFallback.value) {
                remove(AUTH_TOKEN_FALLBACK); remove(AUTH_EXPIRES_AT_FALLBACK)
            } else {
                remove(AUTH_TOKEN_PRIMARY); remove(AUTH_EXPIRES_AT_PRIMARY)
            }
        }
        Repository.clearActiveToken()
        clearSession(preserveBackendTokens = true)
    }

    private fun clearSession(preserveBackendTokens: Boolean = false) {
        if (!preserveBackendTokens) {
            prefs.edit { clear() }
            Repository.clearTokens()
        }
        _currentScreen.value = Screen.Login
        _devices.value = emptyList()
        _cards.value = emptyMap()
        blockRevisions.clear()
        _selectedDeviceId.value = null
        activeDeviceId = null
        deviceListRefreshJob?.cancel()
        deviceListRefreshJob = null
        cancelBlockRefreshes()
    }

    private fun handleRequestError(error: Exception, deviceId: String? = null) {
        if (error is HttpException && error.code() == 401) {
            clearActiveSession()
            return
        }
        val protocolMessage = protocolError(error)
        if (protocolMessage == null) {
            Log.d(TAG, "Request failed", error)
        } else if (deviceId == null) {
            showTransientError(protocolMessage)
        } else {
            showTransientCardError(deviceId, protocolMessage)
        }
    }

    private fun readableError(error: Exception): String = when (error) {
        is TimeoutCancellationException -> "Request timed out"
        is HttpException -> Repository.parseApiError(
            error.response()?.errorBody()?.string()
        )?.title ?: "Request failed (HTTP ${error.code()})"
        is IOException -> "Backend is unavailable"
        else -> error.message ?: error::class.java.simpleName
    }

    private fun protocolError(error: Exception): String? = when (error) {
        is JsonDataException -> "Client and backend protocols are incompatible"
        is IllegalStateException -> error.message
            ?.takeIf { it.startsWith("Unsupported request binding:") }
            ?.let { "Client and backend protocols are incompatible: $it" }
        else -> null
    }

    private fun showTransientError(message: String) {
        _error.value = message
        errorDismissJob?.cancel()
        errorDismissJob = viewModelScope.launch {
            delay(ERROR_VISIBILITY_MS.milliseconds)
            if (_error.value == message) _error.value = null
        }
    }

    private fun showTransientCardError(deviceId: String, message: String) {
        _cards.update { states ->
            val state = states[deviceId] ?: CardUiState()
            states + (deviceId to state.copy(error = message, isLoading = false))
        }
        cardErrorDismissJobs.remove(deviceId)?.cancel()
        cardErrorDismissJobs[deviceId] = viewModelScope.launch {
            delay(ERROR_VISIBILITY_MS.milliseconds)
            _cards.update { states ->
                val state = states[deviceId] ?: return@update states
                if (state.error == message) states + (deviceId to state.copy(error = null))
                else states
            }
            cardErrorDismissJobs.remove(deviceId)
        }
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "smart_watering_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearLegacyPlaintextPrefs(context: Context) {
        context.getSharedPreferences("smart_watering_prefs", Context.MODE_PRIVATE)
            .edit { clear() }
    }
}
