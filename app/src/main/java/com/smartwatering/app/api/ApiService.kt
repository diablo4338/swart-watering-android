package com.smartwatering.app.api

import com.smartwatering.app.data.*
import retrofit2.http.*

interface ApiService {
    @GET("api/v2/app/latest")
    suspend fun getLatestAppRelease(): AppRelease

    @POST("api/v2/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/v2/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleLoginRequest): LoginResponse

    @POST("api/v2/auth/logout")
    suspend fun logout(@Body body: Map<String, String> = emptyMap()): LogoutResponse

    @GET("api/v2/devices")
    suspend fun getDevices(): DeviceListResponse

    @GET("api/v2/device-types")
    suspend fun getDeviceTypes(): DeviceTypesResponse

    @GET("api/v2/device-name-availability")
    suspend fun getDeviceNameAvailability(
        @Query("name") name: String,
        @Query("current_name") currentName: String
    ): DeviceNameAvailabilityResponse

    @GET("api/v2/devices/{device}/watering-parameters")
    suspend fun getWateringParameters(@Path("device") deviceName: String): WateringParameters

    @PUT("api/v2/devices/{device}/watering-parameters")
    suspend fun updateWateringParameters(
        @Path("device") deviceName: String,
        @Body request: WateringParametersRequest
    ): WateringParameters

    @POST("api/v2/devices/{device}/status")
    suspend fun queueStatusRefresh(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): OperationResponse

    @GET("api/v2/devices/{device}/watering/status")
    suspend fun getWateringStatus(@Path("device") deviceName: String): WateringStatus

    @GET("api/v2/devices/{device}/water-consumption")
    suspend fun getWaterConsumption(@Path("device") deviceName: String): WaterConsumptionResponse

    @GET("api/v2/devices/{device}/detected-waterings")
    suspend fun getDetectedWaterings(
        @Path("device") deviceName: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): DetectedWateringListResponse

    @DELETE("api/v2/devices/{device}/detected-waterings/{eventId}")
    suspend fun invalidateDetectedWatering(
        @Path("device") deviceName: String,
        @Path("eventId") eventId: Int
    ): InvalidateDetectedWateringResponse

    @PUT("api/v2/devices/{device}/detected-waterings/{eventId}/fertilized")
    suspend fun setDetectedWateringFertilized(
        @Path("device") deviceName: String,
        @Path("eventId") eventId: Int,
        @Body request: SetFertilizedRequest
    ): SetFertilizedResponse

    @GET("api/v2/watering/history")
    suspend fun getWateringHistory(@Query("successful") successfulOnly: Boolean = false): WateringHistoryResponse

    @GET("api/v2/devices/{device}/status/latest")
    suspend fun getLatestStatus(@Path("device") deviceName: String): LatestStatusResponse

    @GET("api/v2/devices/{device}/status/live")
    suspend fun getLiveStatus(@Path("device") deviceName: String): LatestStatusResponse

    @POST("api/v2/devices/{device}/watering/start")
    suspend fun startWatering(@Path("device") deviceName: String, @Body request: WateringStartRequest): OperationResponse

    @POST("api/v2/devices/{device}/watering/stop")
    suspend fun stopWatering(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): OperationResponse

    @POST("api/v2/devices/{device}/sleep/enable")
    suspend fun enableSleep(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): OperationResponse

    @POST("api/v2/devices/{device}/sleep/disable")
    suspend fun disableSleep(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): OperationResponse

    @POST("api/v2/devices/{device}/sleep/interval")
    suspend fun setSleepInterval(@Path("device") deviceName: String, @Body request: SleepIntervalRequest): OperationResponse

    @POST("api/v2/devices/{device}/zero")
    suspend fun captureZero(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): OperationResponse

    @POST("api/v2/devices/{device}/calibration")
    suspend fun calibrate(@Path("device") deviceName: String, @Body request: CalibrationRequest): OperationResponse

    @POST("api/v2/devices/{device}/config")
    suspend fun updateConfig(@Path("device") deviceName: String, @Body request: DeviceConfigRequest): OperationResponse

    @POST("api/v2/devices/{device}/queue/clear")
    suspend fun clearQueue(@Path("device") deviceName: String, @Body body: Map<String, String> = emptyMap()): QueueClearResponse

    @GET("api/v2/devices/{device}/operations")
    suspend fun getDeviceOperations(
        @Path("device") deviceName: String,
        @Query("active") activeOnly: Boolean? = null
    ): OperationListResponse

    @GET("api/v2/operations/{operation_id}")
    suspend fun getOperationStatus(@Path("operation_id") operationId: String): OperationResponse

    @GET("api/v2/operations/{operation_id}/events")
    suspend fun getOperationEvents(@Path("operation_id") operationId: String): OperationEventsResponse
}
