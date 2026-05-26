package com.spop.poverlay.endurain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class EndurainClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns an access token or throws on failure */
    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/v1/token")
            .header("X-Client-Type", "mobile")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Endurain login failed: HTTP ${response.code}")
        }
        JSONObject(response.body!!.string()).getString("access_token")
    }

    /** Uploads a .fit file. Returns true on success. */
    suspend fun uploadActivity(
        token: String,
        fitBytes: ByteArray,
        filename: String = "workout.fit"
    ): Boolean = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", filename,
                fitBytes.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/v1/activities/create/upload")
            .header("Authorization", "Bearer $token")
            .header("X-Client-Type", "mobile")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Timber.e("Endurain upload failed: HTTP ${response.code} – ${response.body?.string()}")
        }
        response.isSuccessful
    }
}
