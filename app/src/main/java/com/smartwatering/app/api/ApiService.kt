package com.smartwatering.app.api

import com.smartwatering.app.data.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {
    @GET("api/v3/app/latest")
    suspend fun getLatestAppRelease(): AppRelease

    @POST("api/v3/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/v3/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleLoginRequest): LoginResponse

    @POST("api/v3/auth/logout")
    suspend fun logout(@Body body: Map<String, String> = emptyMap()): LogoutResponse

    @GET("api/v3/devices")
    suspend fun getDevices(): DeviceListResponse

    @GET
    suspend fun getDeviceCard(@Url href: String): DeviceCard

    @GET
    suspend fun getCardBlock(@Url href: String): CardBlockResponse

    @POST
    suspend fun performCardAction(
        @Url href: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): CardActionResponse
}
