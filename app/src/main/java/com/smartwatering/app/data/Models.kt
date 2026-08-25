package com.smartwatering.app.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppRelease(
    @param:Json(name = "version_name") val versionName: String,
    @param:Json(name = "version_code") val versionCode: Int,
    @param:Json(name = "download_url") val downloadUrl: String,
)

@JsonClass(generateAdapter = true)
data class LoginRequest(val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class GoogleLoginRequest(@param:Json(name = "id_token") val idToken: String)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    @param:Json(name = "expires_at") val expiresAt: Double,
)

@JsonClass(generateAdapter = true)
data class LogoutResponse(val status: String = "logged_out")

@JsonClass(generateAdapter = true)
data class Device(
    val id: String,
    val name: String,
    @param:Json(name = "device_type") val type: String,
    @param:Json(name = "card_profile") val cardProfile: String,
    @param:Json(name = "card_href") val cardHref: String,
)

@JsonClass(generateAdapter = true)
data class DeviceListResponse(val devices: List<Device>)

@JsonClass(generateAdapter = true)
data class CardRefreshPolicy(
    val mode: String,
    @param:Json(name = "interval_ms") val intervalMs: Long? = null,
    val href: String? = null,
    val etag: String? = null,
)

@JsonClass(generateAdapter = true)
data class CardRequestBodyBinding(
    val binding: String,
    val property: String? = null,
    val fields: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
    val value: Map<String, Any?>? = null,
    val literal: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = true)
data class CardRequest(
    val method: String,
    val href: String,
    val body: CardRequestBodyBinding,
)

@JsonClass(generateAdapter = true)
data class CardCommit(val mode: String, val label: String, val request: CardRequest)

@JsonClass(generateAdapter = true)
data class CardOption(val value: String, val label: String)

@JsonClass(generateAdapter = true)
data class CardControl(
    val kind: String,
    val id: String,
    val label: String,
    @param:Json(name = "control_type") val controlType: String,
    @param:Json(name = "value_type") val valueType: String? = null,
    val default: Any? = null,
    val unit: String? = null,
    val enabled: Boolean = true,
    val style: String? = null,
    val preset: String? = null,
    val options: List<CardOption> = emptyList(),
    val constraints: Map<String, Any?> = emptyMap(),
    val request: CardRequest? = null,
    val commit: CardCommit? = null,
    val value: Any? = null,
)

@JsonClass(generateAdapter = true)
data class CardBlockSchema(val controls: List<CardControl> = emptyList())

@JsonClass(generateAdapter = true)
data class CardBlock(
    val id: String,
    val kind: String,
    val slot: String,
    val title: String? = null,
    val required: Boolean = false,
    val schema: CardBlockSchema? = null,
    val data: Map<String, Any?> = emptyMap(),
    val actions: List<CardControl> = emptyList(),
    val refresh: CardRefreshPolicy,
)

@JsonClass(generateAdapter = true)
data class DeviceCard(
    @param:Json(name = "device_id") val deviceId: String,
    val profile: String,
    @param:Json(name = "schema_version") val schemaVersion: Int,
    val blocks: List<CardBlock>,
)

@JsonClass(generateAdapter = true)
data class CardBlockResponse(
    @param:Json(name = "device_id") val deviceId: String,
    @param:Json(name = "block_revision") val blockRevision: Long,
    val block: CardBlock,
)

@JsonClass(generateAdapter = true)
data class CardActionResponse(val accepted: Boolean, val card: DeviceCard)
