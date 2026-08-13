package com.smartwatering.app.data

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
private val IDEMPOTENT_HTTP_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")

class IdempotencyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (
            request.method !in IDEMPOTENT_HTTP_METHODS ||
            !request.url.encodedPath.startsWith("/api/v2/devices/") ||
            request.header(IDEMPOTENCY_HEADER) != null
        ) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .build()
        )
    }
}
