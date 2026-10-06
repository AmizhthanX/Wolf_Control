package com.wolfpc.remote.data.net

import com.wolfpc.remote.data.SessionStore
import com.wolfpc.remote.data.model.CommandRecord
import com.wolfpc.remote.data.model.Envelope
import com.wolfpc.remote.data.model.LoginChallenge
import com.wolfpc.remote.data.model.PcStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A failure the UI can show verbatim. PROTOCOL §9 guarantees these are safe to display. */
class WolfApiException(val code: String, message: String) : Exception(message)

/**
 * Everything the app sends to Supabase: auth, the three phone-facing Edge
 * Functions, and direct PostgREST reads that RLS confines to this user's rows.
 *
 * Commands go through `enqueue-command` rather than a direct insert because
 * only the function can verify the envelope signature first. That is the whole
 * reason RLS denies inserts on `commands`.
 */
class SupabaseClient(private val store: SessionStore) {

    private companion object { const val TAG = "WolfApi" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    // ------------------------------------------------------------- auth ----

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val snapshot = store.snapshot()
        require(snapshot.supabaseUrl.isNotBlank()) { "project url is not configured" }

        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }

        val request = Request.Builder()
            .url("${snapshot.supabaseUrl}/auth/v1/token?grant_type=password")
            .header("apikey", snapshot.anonKey)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val response = execute(request)
        val obj = json.parseToJsonElement(response).jsonObject

        store.setSession(
            accessToken = obj["access_token"]?.jsonPrimitive?.content.orEmpty(),
            refreshToken = obj["refresh_token"]?.jsonPrimitive?.content.orEmpty(),
            expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600,
            email = obj["user"]?.jsonObject?.get("email")?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /**
     * Refresh if the access token is close to expiry.
     *
     * Called before anything that matters rather than on a timer, so a phone
     * that has been in a pocket for a day still sends a valid token on the
     * first tap instead of failing once and working on the retry.
     */
    suspend fun ensureFreshSession(): Boolean = withContext(Dispatchers.IO) {
        val snapshot = store.snapshot()
        if (snapshot.refreshToken.isBlank()) return@withContext false
        if (!snapshot.tokenExpiringSoon) return@withContext true

        val body = buildJsonObject { put("refresh_token", snapshot.refreshToken) }
        val request = Request.Builder()
            .url("${snapshot.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", snapshot.anonKey)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        try {
            val obj = json.parseToJsonElement(execute(request)).jsonObject
            store.setSession(
                accessToken = obj["access_token"]?.jsonPrimitive?.content.orEmpty(),
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content.orEmpty(),
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600,
                email = obj["user"]?.jsonObject?.get("email")?.jsonPrimitive?.content.orEmpty(),
            )
            true
        } catch (e: WolfApiException) {
            // Only a refusal means the session is really gone. A refresh that
            // failed because there was no network says nothing about whether
            // the token is still good, and clearing it there would turn a
            // thirty second outage into a forced sign-out — precisely when the
            // LAN fallback is supposed to be carrying things.
            if (e.code == "offline") {
                android.util.Log.i(TAG, "token refresh skipped: offline")
            } else {
                android.util.Log.i(TAG, "session rejected (${e.code}); signing out")
                store.clearSession()
            }
            false
        }
    }

    // -------------------------------------------------- edge functions ----

    suspend fun enqueueCommand(envelope: Envelope): String = withContext(Dispatchers.IO) {
        val response = callFunction("enqueue-command", buildJsonObject {
            put("envelope", json.encodeToJsonElement(Envelope.serializer(), envelope))
        })
        response["command_id"]?.jsonPrimitive?.content.orEmpty()
    }

    suspend fun pairDevice(code: String, publicKey: String, deviceName: String): PairResult =
        withContext(Dispatchers.IO) {
            val response = callFunction("pair-device", buildJsonObject {
                put("code", code)
                put("public_key", publicKey)
                put("device_name", deviceName)
            })
            PairResult(
                pcId = response["pc_id"]?.jsonPrimitive?.content.orEmpty(),
                pcName = response["pc_name"]?.jsonPrimitive?.content.orEmpty(),
                deviceId = response["device_id"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

    data class PairResult(val pcId: String, val pcName: String, val deviceId: String)

    suspend fun respondToLogin(challengeId: String, signature: String?, approve: Boolean) =
        withContext(Dispatchers.IO) {
            callFunction("login-respond", buildJsonObject {
                put("challenge_id", challengeId)
                put("decision", if (approve) "APPROVE" else "DENY")
                if (signature != null) put("signature", signature)
            })
            Unit
        }

    private suspend fun callFunction(name: String, body: JsonObject): JsonObject {
        ensureFreshSession()
        val snapshot = store.snapshot()

        val request = Request.Builder()
            .url("${snapshot.supabaseUrl}/functions/v1/$name")
            .header("apikey", snapshot.anonKey)
            .header("authorization", "Bearer ${snapshot.accessToken}")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        return json.parseToJsonElement(execute(request)).jsonObject
    }

    // ----------------------------------------------------- postgrest ----

    /**
     * Revoke this phone server-side: mark its signing keys revoked and the
     * device itself REVOKED.
     *
     * Written directly rather than through an Edge Function because the
     * `device_keys_revoke_own` RLS policy already permits exactly this and
     * nothing else. Revocation is the fail-safe direction, and someone cutting
     * off a phone should not have to wait on a function call to do it.
     *
     * Order matters: keys first. If the second call fails, a revoked key on a
     * still-PAIRED device is harmless — devices filter on `revoked_at`. The
     * reverse would leave a live key on a device nobody is watching.
     */
    suspend fun revokePhone(deviceId: String) = withContext(Dispatchers.IO) {
        ensureFreshSession()
        val snapshot = store.snapshot()
        val now = java.time.Instant.now().toString()

        patch(
            "device_keys?device_id=eq.$deviceId&revoked_at=is.null",
            buildJsonObject { put("revoked_at", now) },
            snapshot,
        )
        patch(
            "devices?id=eq.$deviceId",
            buildJsonObject { put("status", "REVOKED") },
            snapshot,
        )
        Unit
    }

    private fun patch(path: String, body: JsonObject, snapshot: SessionStore.Snapshot) {
        val request = Request.Builder()
            .url("${snapshot.supabaseUrl}/rest/v1/$path")
            .header("apikey", snapshot.anonKey)
            .header("authorization", "Bearer ${snapshot.accessToken}")
            .header("content-type", "application/json")
            .header("prefer", "return=minimal")
            .patch(body.toString().toRequestBody(jsonMedia))
            .build()
        execute(request)
    }

    suspend fun fetchStatus(pcId: String): PcStatus? = withContext(Dispatchers.IO) {
        val rows = get<PcStatus>("pc_status?pc_id=eq.$pcId&select=*", PcStatus.serializer())
        rows.firstOrNull()
    }

    /**
     * The open login challenge for this pc, if there is one.
     *
     * Read straight from the table rather than through a function: the
     * login_challenges_select_own policy already scopes this to pcs the caller
     * owns, and the row carries nothing that needs a server to redact.
     */
    suspend fun fetchPendingChallenge(pcId: String): LoginChallenge? =
        withContext(Dispatchers.IO) {
            val rows = get<LoginChallenge>(
                "login_challenges?pc_id=eq.$pcId&result=eq.PENDING" +
                    "&select=id,pc_id,challenge,expires_at,result" +
                    "&order=created_at.desc&limit=1",
                LoginChallenge.serializer(),
            )
            rows.firstOrNull()?.takeIf { it.isOpen }
        }

    suspend fun fetchHistory(pcId: String, limit: Int = 50): List<CommandRecord> =
        withContext(Dispatchers.IO) {
            get(
                "commands?pc_id=eq.$pcId&select=id,command,status,created_at,completed_at,error" +
                    "&order=created_at.desc&limit=$limit",
                CommandRecord.serializer(),
            )
        }

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        ensureFreshSession()
        val snapshot = store.snapshot()

        val request = Request.Builder()
            .url("${snapshot.supabaseUrl}/rest/v1/$path")
            .header("apikey", snapshot.anonKey)
            .header("authorization", "Bearer ${snapshot.accessToken}")
            .get()
            .build()

        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(serializer),
            execute(request),
        )
    }

    // --------------------------------------------------------- plumbing ----

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw WolfApiException("offline", e.message ?: "network unavailable")
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text

            // Edge Functions answer with {"error":{"code","message"}}; GoTrue
            // and PostgREST use their own shapes. Dig out whichever is present
            // so the user sees something real instead of a status code.
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val error = parsed?.get("error")?.let { e ->
                runCatching { e.jsonObject }.getOrNull()
            }
            val code = error?.get("code")?.jsonPrimitive?.content
                ?: parsed?.get("error_code")?.jsonPrimitive?.content
                ?: "http_${it.code}"
            val message = error?.get("message")?.jsonPrimitive?.content
                ?: parsed?.get("msg")?.jsonPrimitive?.content
                ?: parsed?.get("message")?.jsonPrimitive?.content
                ?: parsed?.get("error_description")?.jsonPrimitive?.content
                ?: "request failed (${it.code})"

            throw WolfApiException(code, message)
        }
    }
}
