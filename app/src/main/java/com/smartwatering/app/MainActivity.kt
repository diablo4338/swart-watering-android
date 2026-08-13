package com.smartwatering.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartwatering.app.ui.DevicesScreen
import com.smartwatering.app.ui.DeviceControlScreen
import com.smartwatering.app.ui.DetectedWateringHistoryScreen
import com.smartwatering.app.ui.LoginScreen
import com.smartwatering.app.ui.MainViewModel
import com.smartwatering.app.ui.Screen
import com.smartwatering.app.ui.theme.MyApplicationTheme
import com.smartwatering.app.data.BackendAvailability

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel()
                val currentScreen by viewModel.currentScreen.collectAsState()
                val latestAppRelease by viewModel.latestAppRelease.collectAsState()
                val backendAvailability by viewModel.backendAvailability.collectAsState()
                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }
                val lifecycleOwner = LocalLifecycleOwner.current
                var backendWasUnavailable by remember { mutableStateOf(false) }
                var showBackendUnavailable by remember { mutableStateOf(false) }

                LaunchedEffect(backendAvailability) {
                    if (backendAvailability == BackendAvailability.UNAVAILABLE) {
                        // The interceptor reports UNAVAILABLE only after all retries. A short
                        // debounce also prevents a completed parallel request from flashing the banner.
                        // Changing the LaunchedEffect key cancels this delay automatically.
                        delay(500.milliseconds)
                        showBackendUnavailable = true
                        backendWasUnavailable = true
                    } else {
                        showBackendUnavailable = false
                        if (backendWasUnavailable) {
                            backendWasUnavailable = false
                            snackbarHostState.showSnackbar(
                                message = "Соединение восстановлено. Данные обновляются.",
                                withDismissAction = true,
                            )
                        }
                    }
                }

                DisposableEffect(lifecycleOwner, backendAvailability) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME &&
                            backendAvailability == BackendAvailability.UNAVAILABLE
                        ) {
                            viewModel.retryBackendConnection()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(latestAppRelease?.versionCode) {
                    val release = latestAppRelease
                    if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                        val result = snackbarHostState.showSnackbar(
                            message = "Доступна новая версия ${release.versionName}",
                            actionLabel = "Скачать",
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, release.downloadUrl.toUri())
                                )
                            }
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        if (showBackendUnavailable) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Сервер не отвечает · данные не обновляются",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                    )
                                    TextButton(onClick = viewModel::retryBackendConnection) {
                                        Text("Проверить", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentScreen) {
                                is Screen.Login -> LoginScreen(viewModel)
                                is Screen.Devices -> DevicesScreen(viewModel)
                                is Screen.DeviceControl -> DeviceControlScreen(
                                    viewModel,
                                    (currentScreen as Screen.DeviceControl).device
                                )
                                is Screen.DetectedWateringHistory ->
                                    DetectedWateringHistoryScreen(
                                        viewModel,
                                        (currentScreen as Screen.DetectedWateringHistory).device
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}
