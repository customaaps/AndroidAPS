/*
 * Libre 2 RU HTTP protocol adapted from JugglucoNG bb5757761a, Libreview.java.
 * Copyright (C) 2021 Jaap Korthals Altes. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.aaps.plugins.source.libre2

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class LibreViewFailure(
    val code: String,
    val retryable: Boolean,
    val reauthenticate: Boolean = false,
    val retryDelayMillis: Long = MIN_RETRY_DELAY,
) : IOException(code) {
    companion object {
        const val MIN_RETRY_DELAY = 15 * 60_000L
        fun retryDelay(seconds: Long?): Long = maxOf(MIN_RETRY_DELAY,
            (seconds ?: 0).coerceIn(0, java.util.concurrent.TimeUnit.DAYS.toSeconds(30)) * 1000)
        fun retryAfter(header: String?, now: Long): Long {
            val seconds = header?.trim()?.toLongOrNull() ?: runCatching {
                (java.time.ZonedDateTime.parse(header?.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - now).coerceAtLeast(0) / 1000
            }.getOrNull()
            return retryDelay(seconds)
        }
    }
}

internal class Libre2LibreViewApi(
    private val transport: (String, String, JSONObject?) -> JSONObject = ::httpsRequest,
) {
    fun authenticate(settings: Libre2ExchangeSettings, beforeRequest: () -> Unit = {}): Libre2ExchangeSettings {
        beforeRequest()
        require(settings.email.isNotBlank() && settings.password.isNotEmpty())
        val config = transport(CONFIG_RU, "GET", null)
        val base = config.getString("newYuUrl").trimEnd('/')
        validateBaseUrl(base)
        for (setDevice in listOf(false, true)) {
            beforeRequest()
            val response = transport("$base/api/nisperson/getauthentication", "POST",
                JSONObject().put("Culture", "ru-RU").put("DeviceId", settings.deviceId)
                    .put("Password", settings.password).put("SetDevice", setDevice).put("UserName", settings.email)
                    .put("Domain", "Libreview").put("GatewayType", Libre2LibreViewPayload.gateway))
            if (response.optInt("status", -1) == 20 && response.optString("reason") == "wrongDeviceForUser" && !setDevice) continue
            checkResponse(response, authenticating = true)
            val result = response.getJSONObject("result")
            val token = result.getString("UserToken")
            val account = result.getString("AccountId")
            if (token.isBlank() || account.isBlank()) throw LibreViewFailure("invalid_response", false)
            return settings.copy(baseUrl = base, token = token, accountId = account, registeredSensor = "", authBlocked = false, retryAfter = 0)
        }
        throw LibreViewFailure("auth", false)
    }

    private fun authenticatedRequest(settings: Libre2ExchangeSettings, path: String, method: String, body: JSONObject): JSONObject {
        validateBaseUrl(settings.baseUrl)
        return try { transport(settings.baseUrl + path, method, body) }
        catch (failure: LibreViewFailure) {
            if (failure.code == "auth") throw LibreViewFailure("token_expired", true, true)
            throw failure
        }
    }

    fun register(settings: Libre2ExchangeSettings, sensorId: String) {
        checkResponse(authenticatedRequest(settings, "/api/nisperson", "PUT",
            Libre2LibreViewPayload.envelope(settings.token)
                .put("DomainData", JSONObject().put("activeSensor", Libre2LibreViewPayload.serial(sensorId)).toString())))
    }

    fun upload(settings: Libre2ExchangeSettings, readings: List<Libre2UploadRecord>, now: Long) {
        checkResponse(authenticatedRequest(settings, "/api/measurements", "POST",
            Libre2LibreViewPayload.measurements(readings, settings.token, settings.deviceId,
                Build.MODEL, Build.MANUFACTURER, Build.VERSION.SDK_INT, now)))
    }

    private fun checkResponse(response: JSONObject, authenticating: Boolean = false) {
        when (val status = response.optInt("status", -1)) {
            0 -> return
            429 -> throw LibreViewFailure("rate_limited", true, retryDelayMillis = LibreViewFailure.retryDelay(
                response.optJSONObject("data")?.optJSONObject("data")?.optLong("lockout")))
            20 -> {
                if (!authenticating && response.optString("reason") == "wrongDeviceInToken")
                    throw LibreViewFailure("token_expired", true, true)
                throw LibreViewFailure("auth", false)
            }
            else -> throw LibreViewFailure(if (authenticating && status < 500) "auth" else "server_$status", !authenticating || status >= 500)
        }
    }

    companion object {
        const val CONFIG_RU = "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_RU_config.json"

        fun validateBaseUrl(value: String) {
            val url = URL(value)
            val host = url.host.lowercase(java.util.Locale.ROOT)
            require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                url.query == null && url.ref == null &&
                listOf("libreview.io", "libreview.ru", "libreview.com", "freestyleserver.com")
                    .any { host == it || host.endsWith(".$it") }) { "Invalid LibreView endpoint" }
        }
    }
}

private fun httpsRequest(address: String, method: String, body: JSONObject?): JSONObject {
    Libre2LibreViewApi.validateBaseUrl(address)
    val connection = URL(address).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept-Language", "ru-RU, ru;q=0.8")
        connection.setRequestProperty("Abbott-ADC-App-Platform", "Android/${Build.VERSION.RELEASE}/FSLL/2.10.1.10406")
        if (body != null) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        when (val code = connection.responseCode) {
            200 -> Unit
            429 -> throw LibreViewFailure("rate_limited", true, retryDelayMillis =
                LibreViewFailure.retryAfter(connection.getHeaderField("Retry-After"), System.currentTimeMillis()))
            401, 403 -> throw LibreViewFailure("auth", false)
            else -> throw LibreViewFailure("http_$code", code >= 500 || code == 408)
        }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024 * 1024) throw LibreViewFailure("invalid_response", false)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    } finally { connection.disconnect() }
}
