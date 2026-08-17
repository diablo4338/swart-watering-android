package com.smartwatering.app.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class RetryInterceptor(
    private val primaryBaseUrl: HttpUrl,
    private val fallbackBaseUrl: HttpUrl? = null,
    private val retryDelaysMs: List<Long> = listOf(500L, 1_000L, 3_000L),
    private val onBackendAvailabilityChanged: (Boolean) -> Unit = {},
    private val onFallbackChanged: (Boolean) -> Unit = {},
) : Interceptor {
    init {
        require(retryDelaysMs.all { it >= 0 }) { "retry delays must not be negative" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestId = requestSequence.incrementAndGet()
        val initialBaseUrl = synchronized(availabilityLock) { activeBaseUrl }
        val candidates = if (initialBaseUrl == primaryBaseUrl && fallbackBaseUrl != null) {
            listOf(primaryBaseUrl, fallbackBaseUrl)
        } else {
            listOf(initialBaseUrl)
        }

        for ((candidateIndex, baseUrl) in candidates.withIndex()) {
            val routedRequest = request.newBuilder()
                .url(request.url.newBuilder()
                    .scheme(baseUrl.scheme)
                    .host(baseUrl.host)
                    .port(baseUrl.port)
                    .build())
                .build()
            for (attempt in 0..retryDelaysMs.size) {
                try {
                    val response = chain.proceed(routedRequest)
                    if (response.code !in SERVER_ERROR_STATUS_CODES) {
                        reportAvailable(requestId, baseUrl)
                        return response
                    }
                    if (attempt == retryDelaysMs.size) {
                        if (candidateIndex < candidates.lastIndex) {
                            response.close()
                            logFailover(baseUrl, candidates[candidateIndex + 1])
                            break
                        }
                        reportUnavailable(requestId)
                        return response
                    }
                    val delayMs = retryDelaysMs[attempt]
                    logRetry(routedRequest.url.redact(), "HTTP ${response.code}", attempt + 2, delayMs)
                    response.close()
                    sleepBeforeRetry(delayMs)
                } catch (error: IOException) {
                    if (attempt == retryDelaysMs.size) {
                        if (candidateIndex < candidates.lastIndex) {
                            logFailover(baseUrl, candidates[candidateIndex + 1])
                            break
                        }
                        reportUnavailable(requestId)
                        throw error
                    }
                    val delayMs = retryDelaysMs[attempt]
                    logRetry(routedRequest.url.redact(), error.javaClass.simpleName, attempt + 2, delayMs)
                    sleepBeforeRetry(delayMs)
                }
            }
        }
        error("retry loop completed unexpectedly")
    }

    fun markPrimaryAvailable() {
        synchronized(availabilityLock) {
            activeBaseUrl = primaryBaseUrl
            lastReportedRequestId = requestSequence.get()
            onFallbackChanged(false)
        }
    }

    fun useFallback() {
        val fallback = fallbackBaseUrl ?: return
        synchronized(availabilityLock) {
            activeBaseUrl = fallback
            lastReportedRequestId = requestSequence.get()
            onFallbackChanged(true)
        }
    }

    private fun reportAvailable(requestId: Long, baseUrl: HttpUrl) {
        synchronized(availabilityLock) {
            if (requestId <= lastReportedRequestId) return
            activeBaseUrl = baseUrl
            lastReportedRequestId = requestId
            onFallbackChanged(baseUrl != primaryBaseUrl)
            onBackendAvailabilityChanged(true)
        }
    }

    private fun reportUnavailable(requestId: Long) {
        synchronized(availabilityLock) {
            if (requestId <= lastReportedRequestId) return
            lastReportedRequestId = requestId
            onBackendAvailabilityChanged(false)
        }
    }

    private fun logRetry(url: String, reason: String, nextAttempt: Int, delayMs: Long) {
        Log.w(
            TAG,
            "Retrying $url after $reason; attempt=$nextAttempt/${retryDelaysMs.size + 1} delay_ms=$delayMs",
        )
    }

    private fun logFailover(from: HttpUrl, to: HttpUrl) {
        Log.w(TAG, "Backend ${from.redact()} unavailable after retries; switching to ${to.redact()}")
    }

    private fun sleepBeforeRetry(delayMs: Long) {
        if (delayMs <= 0) return
        try {
            Thread.sleep(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Retry interrupted", error)
        }
    }

    private companion object {
        const val TAG = "WateringAPI"
        val SERVER_ERROR_STATUS_CODES = 500..599
    }

    private val requestSequence = AtomicLong(0)
    private val availabilityLock = Any()
    private var lastReportedRequestId = 0L
    private var activeBaseUrl = primaryBaseUrl
}
