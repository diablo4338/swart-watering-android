package com.smartwatering.app.data

import android.util.Log
import com.smartwatering.app.BuildConfig
import com.smartwatering.app.api.ApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object Repository {
    private var token: String? = null
    val baseUrl: String = normalizeBaseUrl()

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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
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

    private fun normalizeBaseUrl(): String {
        val rawUrl = BuildConfig.SMART_WATERING_PUBLIC_API_BASE_URL
        val withoutTrailingSlash = rawUrl.trim().trimEnd('/')
        val serverRoot = withoutTrailingSlash.replace(Regex("/api/v\\d+$", RegexOption.IGNORE_CASE), "")
        return "$serverRoot/"
    }
}
