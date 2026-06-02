package com.kmnexus.codexmeter.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String,
)

class ProviderHttpClient(
    okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = okHttpClient
        .newBuilder()
        .callTimeout(okHttpClient.callTimeoutMillis.clampedTimeoutMillis(), TimeUnit.MILLISECONDS)
        .connectTimeout(okHttpClient.connectTimeoutMillis.clampedTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(okHttpClient.readTimeoutMillis.clampedTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(okHttpClient.writeTimeoutMillis.clampedTimeoutMillis(), TimeUnit.MILLISECONDS)
        .build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse =
        withContext(ioDispatcher) {
            val requestBuilder = Request.Builder()
                .url(url.toHttpUrl())
                .get()

            headers.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                ProviderHttpResponse(
                    statusCode = response.code,
                    body = response.body.string(),
                )
            }
        }

    suspend fun postForm(
        url: String,
        formFields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse =
        withContext(ioDispatcher) {
            val formBody = FormBody.Builder().apply {
                formFields.forEach { (name, value) ->
                    add(name, value)
                }
            }.build()
            val requestBuilder = Request.Builder()
                .url(url.toHttpUrl())
                .post(formBody)

            headers.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                ProviderHttpResponse(
                    statusCode = response.code,
                    body = response.body.string(),
                )
            }
        }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse =
        withContext(ioDispatcher) {
            val requestBuilder = Request.Builder()
                .url(url.toHttpUrl())
                .post(jsonBody.toRequestBody(APPLICATION_JSON_MEDIA_TYPE))

            headers.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                ProviderHttpResponse(
                    statusCode = response.code,
                    body = response.body.string(),
                )
            }
        }

    private fun Int.clampedTimeoutMillis(): Long =
        when {
            this == NO_TIMEOUT -> MAX_TIMEOUT_MILLIS
            this > MAX_TIMEOUT_MILLIS -> MAX_TIMEOUT_MILLIS
            else -> toLong()
        }

    private companion object {
        const val NO_TIMEOUT = 0
        const val MAX_TIMEOUT_MILLIS = 30_000L
        val APPLICATION_JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
