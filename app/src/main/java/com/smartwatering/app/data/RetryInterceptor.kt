package com.smartwatering.app.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 500,
    private val maxServerDelayMs: Long = 10_000,
    private val onBackendAvailabilityChanged: (Boolean) -> Unit = {},
) : Interceptor {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMs >= 0) { "baseDelayMs must not be negative" }
        require(maxServerDelayMs >= 0) { "maxServerDelayMs must not be negative" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val canRetry = request.method == "GET" || request.header("Idempotency-Key") != null
        if (!canRetry || isOperationStatusPoll(request.url.encodedPath)) {
            return chain.proceed(request)
        }

        var attempt = 1
        while (true) {
            try {
                val response = chain.proceed(request)
                if (response.code !in RETRYABLE_STATUS_CODES) {
                    onBackendAvailabilityChanged(true)
                    return response
                }
                if (attempt >= maxAttempts) {
                    onBackendAvailabilityChanged(false)
                    return response
                }
                val delayMs = retryDelayMs(response, attempt)
                logRetry(request.url.redact(), "HTTP ${response.code}", attempt, delayMs)
                response.close()
                sleepBeforeRetry(delayMs)
            } catch (error: IOException) {
                if (attempt >= maxAttempts) {
                    onBackendAvailabilityChanged(false)
                    throw error
                }
                val delayMs = defaultRetryDelayMs(attempt)
                logRetry(request.url.redact(), error.javaClass.simpleName, attempt, delayMs)
                sleepBeforeRetry(delayMs)
            }
            attempt += 1
        }
    }

    private fun retryDelayMs(response: Response, attempt: Int): Long {
        parseRetryAfterMs(response.header("Retry-After"))?.let {
            return min(it, maxServerDelayMs)
        }

        return defaultRetryDelayMs(attempt)
    }

    private fun defaultRetryDelayMs(attempt: Int): Long {
        val exponentialDelay = baseDelayMs * (1L shl (attempt - 1))
        val jitterBound = max(1, baseDelayMs / 2 + 1)
        return exponentialDelay + ThreadLocalRandom.current().nextLong(jitterBound)
    }

    private fun logRetry(url: String, reason: String, attempt: Int, delayMs: Long) {
        Log.w(
            TAG,
            "Retrying $url after $reason; attempt=${attempt + 1}/$maxAttempts delay_ms=$delayMs",
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

    private fun parseRetryAfterMs(value: String?): Long? {
        val retryAfter = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        retryAfter.toLongOrNull()?.let { seconds ->
            return if (seconds >= 0) {
                min(seconds, maxServerDelayMs / 1_000) * 1_000
            } else {
                null
            }
        }

        return runCatching {
            val retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            max(0, retryAt - System.currentTimeMillis())
        }.getOrNull()
    }

    private fun isOperationStatusPoll(path: String): Boolean =
        OPERATION_STATUS_PATH.matches(path)

    private companion object {
        const val TAG = "WateringAPI"
        val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)
        val OPERATION_STATUS_PATH = Regex("/api/v2/operations/[^/]+/?")
    }
}
