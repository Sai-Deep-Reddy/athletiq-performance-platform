package com.athletiq.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val statusCode: Int) : ApiResult<T>
    data class AuthRejected(val statusCode: Int, val detail: String) : ApiResult<Nothing>
    data class ValidationError(val statusCode: Int, val detail: String) : ApiResult<Nothing>
    data class RateLimited(val retryAfterSeconds: Long) : ApiResult<Nothing>
    data class ServerError(val statusCode: Int, val detail: String) : ApiResult<Nothing>
    data class NetworkUnavailable(val message: String) : ApiResult<Nothing>
}

@Serializable
data class LoginPayload(val email: String, val password: String)

@Serializable
data class LoginUserDto(
    val id: String,
    val institution_id: String,
    val email: String,
    val full_name: String,
    val role: String,
)

@Serializable
data class LoginResponseDto(
    val access_token: String,
    val token_type: String,
    val expires_at: String,
    val user: LoginUserDto,
)

@Serializable
data class BootstrapInstitutionPayload(
    val institution_name: String,
    val institution_code: String,
    val admin_email: String,
    val admin_full_name: String,
    val admin_password: String,
)

@Serializable
data class BootstrapResponseDto(
    val institution: InstitutionDto,
    val admin_user: LoginUserDto,
)

@Serializable
data class InstitutionDto(
    val id: String,
    val name: String,
    val code: String,
)

/**
 * Verified FastAPI REST client handling explicit HTTP failure modes (401/403, 422, 429, 5xx)
 * and network timeouts without logging credentials, bearer tokens, or PII.
 */
class AthletiqApiClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun bootstrapInstitution(
        baseUrl: String,
        payload: BootstrapInstitutionPayload,
    ): ApiResult<BootstrapResponseDto> {
        val body = json.encodeToString(BootstrapInstitutionPayload.serializer(), payload)
        return when (val raw = executeJsonRequest(baseUrl, "/api/v1/auth/bootstrap-institution", "POST", body, null)) {
            is ApiResult.Success -> ApiResult.Success(
                json.decodeFromString(BootstrapResponseDto.serializer(), raw.data),
                raw.statusCode,
            )
            is ApiResult.AuthRejected -> raw
            is ApiResult.ValidationError -> raw
            is ApiResult.RateLimited -> raw
            is ApiResult.ServerError -> raw
            is ApiResult.NetworkUnavailable -> raw
        }
    }

    suspend fun getJson(
        credentials: AuthSessionCredentials,
        endpointPath: String,
    ): ApiResult<String> = executeJsonRequest(
        baseUrl = credentials.baseUrl,
        endpointPath = endpointPath,
        method = "GET",
        jsonBody = null,
        bearerToken = credentials.accessToken,
    )

    suspend fun login(baseUrl: String, email: String, password: String): ApiResult<LoginResponseDto> {
        val body = json.encodeToString(LoginPayload.serializer(), LoginPayload(email, password))
        return when (val raw = executeJsonRequest(baseUrl, "/api/v1/auth/login", "POST", body, null)) {
            is ApiResult.Success -> ApiResult.Success(
                json.decodeFromString(LoginResponseDto.serializer(), raw.data),
                raw.statusCode,
            )
            is ApiResult.AuthRejected -> raw
            is ApiResult.ValidationError -> raw
            is ApiResult.RateLimited -> raw
            is ApiResult.ServerError -> raw
            is ApiResult.NetworkUnavailable -> raw
        }
    }

    suspend fun postOutboxJson(
        credentials: AuthSessionCredentials,
        endpointPath: String,
        payloadJson: String,
    ): ApiResult<String> = executeJsonRequest(
        baseUrl = credentials.baseUrl,
        endpointPath = endpointPath,
        method = "POST",
        jsonBody = payloadJson,
        bearerToken = credentials.accessToken,
    )

    private suspend fun executeJsonRequest(
        baseUrl: String,
        endpointPath: String,
        method: String,
        jsonBody: String?,
        bearerToken: String?,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$normalizedBase$normalizedPath").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (bearerToken != null) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            when (code) {
                in 200..299 -> ApiResult.Success(bodyText, code)
                401, 403 -> ApiResult.AuthRejected(code, "Authentication or authorization rejected")
                404, 409, 422 -> ApiResult.ValidationError(code, bodyText.take(300))
                429 -> {
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 30L
                    ApiResult.RateLimited(retryAfter)
                }
                else -> ApiResult.ServerError(code, "Server returned HTTP $code")
            }
        } catch (e: IOException) {
            ApiResult.NetworkUnavailable(e.message ?: "Network unreachable")
        } finally {
            conn?.disconnect()
        }
    }
}
