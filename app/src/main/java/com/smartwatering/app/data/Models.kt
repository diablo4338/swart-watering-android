package com.smartwatering.app.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class DeviceType(val apiValue: String) {
    PLANT("plant"),
    TANK("tank")
}

enum class OperationType(val apiValue: String) {
    DEVICE_CONFIG("device_config"),
    SLEEP_ENABLE("sleep_enable"),
    SLEEP_DISABLE("sleep_disable"),
    SLEEP_INTERVAL("sleep_interval"),
    ZERO_CAPTURE("zero_capture"),
    SCALE_CALIBRATION("scale_calibration")
}

data class LoginRequest(
    val username: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class GoogleLoginRequest(
    @param:Json(name = "id_token") val idToken: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    @param:Json(name = "expires_at") val expiresAt: Double
)

@JsonClass(generateAdapter = true)
data class LogoutResponse(
    val status: String = "logged_out"
)

@JsonClass(generateAdapter = true)
data class Device(
    val name: String,
    val type: String,
    @param:Json(name = "has_pending_operations") val hasPendingOperations: Boolean = false
)

@JsonClass(generateAdapter = true)
data class DeviceListResponse(
    val devices: List<Device>
)

@JsonClass(generateAdapter = true)
data class WaterConsumptionDay(
    val date: String,
    val day: Double?,
    val night: Double?,
    @param:Json(name = "day_below_weekly_median")
    val dayBelowWeeklyMedian: Boolean = false,
    @param:Json(name = "night_below_weekly_median")
    val nightBelowWeeklyMedian: Boolean = false
)

@JsonClass(generateAdapter = true)
data class WaterConsumptionResponse(
    val device: String,
    val days: List<WaterConsumptionDay>
)

@JsonClass(generateAdapter = true)
data class DetectedWatering(
    val id: Int,
    @param:Json(name = "occurred_at") val occurredAt: Double,
    @param:Json(name = "weight_before_g") val weightBeforeG: Double,
    @param:Json(name = "weight_after_g") val weightAfterG: Double,
    @param:Json(name = "amount_g") val amountG: Double,
    val source: String
)

@JsonClass(generateAdapter = true)
data class DetectedWateringListResponse(
    val device: String,
    val waterings: List<DetectedWatering>,
    @param:Json(name = "next_offset") val nextOffset: Int?
)

@JsonClass(generateAdapter = true)
data class InvalidateDetectedWateringResponse(
    val id: Int,
    val invalid: Boolean
)

@JsonClass(generateAdapter = true)
data class DeviceTypesResponse(val types: List<String>)

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    val name: String?,
    val type: String?
)

@JsonClass(generateAdapter = true)
data class RawDeviceStatus(
    val device: DeviceInfo?,
    val watering: WateringInfo?,
    val config: DeviceConfig?,
    val weight: DeviceWeight?
)

@JsonClass(generateAdapter = true)
data class WateringInfo(
    val active: Boolean,
    val state: String?,
    @param:Json(name = "last_operation_type") val lastOperationType: String?,
    @param:Json(name = "last_operation_status") val lastOperationStatus: String?
)

@JsonClass(generateAdapter = true)
data class DeviceConfig(
    @param:Json(name = "target_g") val targetG: Double?,
    @param:Json(name = "dry_weight_g") val dryWeightG: Double?,
    @param:Json(name = "tare_weight_g") val tareWeightG: Double?,
    @param:Json(name = "sleep_disabled") val sleepDisabled: Boolean? = null,
    @param:Json(name = "sleep_interval_min") val sleepIntervalMin: Int? = null
)

@JsonClass(generateAdapter = true)
data class SleepIntervalRequest(val minutes: Int)

@JsonClass(generateAdapter = true)
data class CalibrationRequest(@param:Json(name = "weight_g") val weightG: Double)

@JsonClass(generateAdapter = true)
data class DeviceConfigRequest(
    @param:Json(name = "device_type") val deviceType: String,
    val name: String,
    @param:Json(name = "dry_weight_g") val dryWeightG: Int,
    @param:Json(name = "tare_weight_g") val tareWeightG: Int
)

@JsonClass(generateAdapter = true)
data class QueueClearResponse(val cleared: Int)

@JsonClass(generateAdapter = true)
data class DeviceWeight(
    @param:Json(name = "gross_weight_g") val grossWeightG: Double?,
    @param:Json(name = "useful_weight_g") val usefulWeightG: Double?,
    @param:Json(name = "water_used_g") val waterUsedG: Double?
)

@JsonClass(generateAdapter = true)
data class LatestStatusResponse(
    val device: String,
    val status: String,
    val source: String, // live, snapshot, none
    val available: Boolean,
    val result: RawDeviceStatus?,
    @param:Json(name = "result_received_at") val resultReceivedAt: Double?,
    @param:Json(name = "operation_id") val operationId: String?,
    @param:Json(name = "pending_operation_id") val pendingOperationId: String?,
    @param:Json(name = "pending_operation_status") val pendingOperationStatus: String?,
    val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class WateringStartRequest(
    @param:Json(name = "target_g") val targetG: Double
)

@JsonClass(generateAdapter = true)
data class OperationResponse(
    @param:Json(name = "operation_id") val operationId: String,
    val device: String = "",
    val type: String = "",
    val status: String = "",
    @param:Json(name = "target_g") val targetG: Double? = null,
    val minutes: Int? = null,
    @param:Json(name = "weight_g") val weightG: Double? = null,
    @param:Json(name = "device_type") val deviceType: String? = null,
    val name: String? = null,
    @param:Json(name = "dry_weight_g") val dryWeightG: Double? = null,
    @param:Json(name = "tare_weight_g") val tareWeightG: Double? = null,
    val error: OperationError? = null,
    @param:Json(name = "created_at") val createdAt: Double = 0.0,
    @param:Json(name = "updated_at") val updatedAt: Double = 0.0,
    @param:Json(name = "finished_at") val finishedAt: Double? = null
)

@JsonClass(generateAdapter = true)
data class OperationListResponse(
    val operations: List<OperationResponse>
)

@JsonClass(generateAdapter = true)
data class WateringHistoryResponse(
    val operations: List<OperationResponse>
)

@JsonClass(generateAdapter = true)
data class OperationError(
    val code: String,
    val message: String,
    val detail: String?,
    val retryable: Boolean?
)

@JsonClass(generateAdapter = true)
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean?
)

@JsonClass(generateAdapter = true)
data class WateringStatus(
    val device: DeviceInfo?,
    val active: Boolean,
    val state: String?,
    @param:Json(name = "gap_g") val gapG: Double?,
    @param:Json(name = "percent_complete") val percentComplete: Double?,
    @param:Json(name = "last_operation") val lastOperation: OperationInfo?,
    val source: String,
    val available: Boolean,
    @param:Json(name = "result_received_at") val resultReceivedAt: Double,
    @param:Json(name = "operation_id") val operationId: String?,
    @param:Json(name = "pending_operation_id") val pendingOperationId: String?,
    @param:Json(name = "pending_operation_status") val pendingOperationStatus: String?,
    @param:Json(name = "planned_watering") val plannedWatering: PlannedWatering? = null,
    val result: RawDeviceStatus? = null
)

@JsonClass(generateAdapter = true)
data class PlannedWatering(
    @param:Json(name = "operation_id") val operationId: String = "",
    @param:Json(name = "target_g") val targetG: Double,
    val status: String
)

@JsonClass(generateAdapter = true)
data class OperationInfo(
    val type: String?,
    val status: String?
)

@JsonClass(generateAdapter = true)
data class OperationEventsResponse(
    @param:Json(name = "operation_id") val operationId: String = "",
    val events: List<OperationEvent>
)

@JsonClass(generateAdapter = true)
data class OperationEvent(
    val status: String,
    val message: String
)
