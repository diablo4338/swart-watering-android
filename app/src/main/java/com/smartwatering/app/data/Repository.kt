package com.smartwatering.app.data

import android.util.Log
import com.smartwatering.app.BuildConfig
import com.smartwatering.app.api.ApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BackendAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

object Repository {
    private var token: String? = null
    val baseUrl: String = normalizeBaseUrl()
    private val fallbackBaseUrl: String? = normalizeBaseUrl(
        BuildConfig.SMART_WATERING_PUBLIC_API_FALLBACK_BASE_URL
    ).takeUnless { it == baseUrl || it == EMPTY_BASE_URL }

    private val _backendAvailability = MutableStateFlow(BackendAvailability.AVAILABLE)
    val backendAvailability: StateFlow<BackendAvailability> = _backendAvailability
    private val _usingFallback = MutableStateFlow(false)
    val usingFallback: StateFlow<Boolean> = _usingFallback

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
        token?.takeIf { it.isNotBlank() }?.let {
            request.addHeader("Authorization", "Bearer $it")
        }
        chain.proceed(request.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("WateringAPI", message)
    }.apply {
        redactHeader("Authorization")
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val retryInterceptor = RetryInterceptor(
        primaryBaseUrl = baseUrl.toHttpUrl(),
        fallbackBaseUrl = fallbackBaseUrl?.toHttpUrl(),
        onBackendAvailabilityChanged = { available ->
            _backendAvailability.value = if (available) BackendAvailability.AVAILABLE
            else BackendAvailability.UNAVAILABLE
        },
        onFallbackChanged = { _usingFallback.value = it },
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(IdempotencyInterceptor())
        .addInterceptor(retryInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val api: ApiService = retrofit.create(ApiService::class.java)

    fun setToken(newToken: String?) {
        token = newToken
    }

    suspend fun probePrimaryBackend(): Boolean = withContext(Dispatchers.IO) {
        if (!_usingFallback.value) return@withContext false
        val request = Request.Builder().url("${baseUrl}healthz").get().build()
        runCatching {
            okHttpClient.newBuilder()
                .apply { interceptors().clear() }
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use false
                    retryInterceptor.markPrimaryAvailable()
                    _backendAvailability.value = BackendAvailability.AVAILABLE
                    true
                }
        }.getOrDefault(false)
    }

    private fun normalizeBaseUrl(
        rawUrl: String = BuildConfig.SMART_WATERING_PUBLIC_API_BASE_URL,
    ): String {
        if (rawUrl.isBlank()) return EMPTY_BASE_URL
        val withoutTrailingSlash = rawUrl.trim().trimEnd('/')
        val serverRoot = withoutTrailingSlash.replace(Regex("/api/v\\d+$", RegexOption.IGNORE_CASE), "")
        return "$serverRoot/"
    }

    private const val EMPTY_BASE_URL = "/"
}
