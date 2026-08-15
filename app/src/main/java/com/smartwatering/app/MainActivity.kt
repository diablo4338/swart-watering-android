package com.smartwatering.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
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
                var showBackendUnavailable by remember { mutableStateOf(false) }

                LaunchedEffect(backendAvailability) {
                    showBackendUnavailable = false
                    if (backendAvailability == BackendAvailability.UNAVAILABLE) {
                        // Avoid flashing the indicator when a parallel request has already succeeded.
                        delay(500.milliseconds)
                        showBackendUnavailable = true
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
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentScreen) {
                            is Screen.Login -> LoginScreen(viewModel)
                            is Screen.Devices -> DevicesScreen(
                                viewModel = viewModel,
                                showBackendUnavailable = showBackendUnavailable,
                            )
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
