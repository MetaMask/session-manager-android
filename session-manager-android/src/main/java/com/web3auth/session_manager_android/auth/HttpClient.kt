package com.web3auth.session_manager_android.auth

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HttpClient(
    private val authSessionProvider: IHttpSessionAuthProvider,
    client: OkHttpClient? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshDeferred: Deferred<String>? = null
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    fun get(
        url: String,
        options: HttpClientRequestOptions = HttpClientRequestOptions()
    ): CompletableFuture<String> = future { withAuth("GET", url, null, options) }

    fun post(
        url: String,
        data: Any? = null,
        options: HttpClientRequestOptions = HttpClientRequestOptions()
    ): CompletableFuture<String> = future { withAuth("POST", url, data, options) }

    fun put(
        url: String,
        data: Any? = null,
        options: HttpClientRequestOptions = HttpClientRequestOptions()
    ): CompletableFuture<String> = future { withAuth("PUT", url, data, options) }

    fun patch(
        url: String,
        data: Any? = null,
        options: HttpClientRequestOptions = HttpClientRequestOptions()
    ): CompletableFuture<String> = future { withAuth("PATCH", url, data, options) }

    fun delete(
        url: String,
        data: Any? = null,
        options: HttpClientRequestOptions = HttpClientRequestOptions()
    ): CompletableFuture<String> = future { withAuth("DELETE", url, data, options) }

    /**
     * Core wrapper that gates requests behind any in-flight refresh.
     *
     * Flow:
     * 1. If a refresh is in progress, wait for it before sending (prevents stale-token 401s).
     * 2. Send with current token.
     * 3. On 401: enqueue a refresh (deduplicated), retry once with the new token.
     */
    private suspend fun withAuth(
        method: String,
        url: String,
        data: Any?,
        options: HttpClientRequestOptions
    ): String {
        if (!options.authenticated) {
            return execute(method, url, data, buildHeaders(options, token = null), options.timeout)
        }
        refreshDeferred?.let { it.await() }
        val headers = buildHeaders(options, token = authSessionProvider.getAccessToken())
        return try {
            execute(method, url, data, headers, options.timeout)
        } catch (error: HttpStatusException) {
            if (error.code == 401) {
                try {
                    val newToken = enqueueRefresh()
                    val retryHeaders = headers.toMutableMap()
                    retryHeaders["Authorization"] = "Bearer $newToken"
                    execute(method, url, data, retryHeaders, options.timeout)
                } catch (_: Exception) {
                    throw AuthError("Session expired, please re-authenticate", AuthErrorCode.SESSION_EXPIRED)
                }
            } else {
                throw error
            }
        }
    }

    private suspend fun enqueueRefresh(): String {
        var createdByUs = false
        val deferred = refreshMutex.withLock {
            refreshDeferred?.let { return@withLock it }
            val newDeferred = CompletableDeferred<String>()
            refreshDeferred = newDeferred
            createdByUs = true
            newDeferred
        }
        if (createdByUs) {
            try {
                (deferred as CompletableDeferred).complete(authSessionProvider.handleUnauthorized())
            } catch (t: Throwable) {
                (deferred as CompletableDeferred).completeExceptionally(t)
            } finally {
                refreshMutex.withLock { refreshDeferred = null }
            }
        }
        return deferred.await()
    }

    private suspend fun buildHeaders(
        options: HttpClientRequestOptions,
        token: String?
    ): MutableMap<String, String> {
        val headers = options.headers.toMutableMap()
        if (options.authenticated && token != null) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private suspend fun execute(
        method: String,
        url: String,
        data: Any?,
        headers: Map<String, String>,
        timeoutMs: Long?
    ): String = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = when (method) {
            "GET" -> null
            else -> gson.toJson(data ?: emptyMap<String, Any>()).toRequestBody(mediaType)
        }
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        builder.method(method, requestBody)
        val callClient = if (timeoutMs != null) {
            httpClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            httpClient
        }
        callClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) {
                throw HttpStatusException(401, body)
            }
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, body)
            }
            body
        }
    }

    private fun <T> future(block: suspend () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        scope.launch {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result
    }

    private class HttpStatusException(val code: Int, body: String) :
        Exception("HTTP $code: $body")
}
