package com.web3auth.session_manager_android.api

import com.web3auth.session_manager_android.models.AuthorizeSessionRequest
import com.web3auth.session_manager_android.models.SessionRequestBody
import com.web3auth.session_manager_android.models.StoreApiResponse
import org.json.JSONObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.PUT

interface Web3AuthApi {

    @POST("v2/store/set")
    suspend fun createSession(
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body sessionRequestBody: SessionRequestBody
    ): Response<JSONObject>

    @PUT("v2/store/update")
    suspend fun updateSession(
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body sessionRequestBody: SessionRequestBody
    ): Response<JSONObject>

    @POST("v2/store/get")
    suspend fun authorizeSession(
        @Header("origin") origin: String? = null,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body authorizeSessionRequest: AuthorizeSessionRequest
    ): Response<StoreApiResponse>

    @POST("v2/store/set")
    suspend fun invalidateSession(
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Body sessionRequestBody: SessionRequestBody
    ): Response<JSONObject>
}
