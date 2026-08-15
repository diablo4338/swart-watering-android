package com.smartwatering.app.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class RetryInterceptor(
    private val retryDelaysMs: List<Long> = listOf(500L, 1_000L, 3_000L),
    private val onBackendAvailabilityChanged: (Boolean) -> Unit = {},
) : Interceptor {
    init {
        require(retryDelaysMs.all { it >= 0 }) { "retry delays must not be negative" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestId = requestSequence.incrementAndGet()

        for (attempt in 0..retryDelaysMs.size) {
            try {
                val response = chain.proceed(request)
                if (response.code !in SERVER_ERROR_STATUS_CODES) {
                    reportAvailability(requestId, true)
                    return response
                }
                if (attempt == retryDelaysMs.size) {
                    reportAvailability(requestId, false)
                    return response
                }
                val delayMs = retryDelaysMs[attempt]
                logRetry(request.url.redact(), "HTTP ${response.code}", attempt + 2, delayMs)
                response.close()
                sleepBeforeRetry(delayMs)
            } catch (error: IOException) {
                if (attempt == retryDelaysMs.size) {
                    reportAvailability(requestId, false)
                    throw error
                }
                val delayMs = retryDelaysMs[attempt]
                logRetry(request.url.redact(), error.javaClass.simpleName, attempt + 2, delayMs)
                sleepBeforeRetry(delayMs)
            }
        }
        error("retry loop completed unexpectedly")
    }

    private fun reportAvailability(requestId: Long, available: Boolean) {
        synchronized(availabilityLock) {
            if (requestId <= lastReportedRequestId) return
            lastReportedRequestId = requestId
            onBackendAvailabilityChanged(available)
        }
    }

    private fun logRetry(url: String, reason: String, nextAttempt: Int, delayMs: Long) {
        Log.w(
            TAG,
            "Retrying $url after $reason; attempt=$nextAttempt/${retryDelaysMs.size + 1} delay_ms=$delayMs",
        )
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
}
