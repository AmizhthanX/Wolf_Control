package com.wolfpc.remote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("wolfpc")

/**
 * Everything the app remembers between launches.
 *
 * Tokens live here rather than in the Keystore deliberately: they are
 * revocable server-side, they expire on their own, and the thing that actually
 * authorises a power command is the Keystore key, which this file could never
 * leak. Nothing here is sufficient to control the PC on its own.
 */
class SessionStore(private val context: Context) {

    private object Keys {
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val anonKey = stringPreferencesKey("anon_key")
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
        val expiresAt = longPreferencesKey("expires_at")
        val userEmail = stringPreferencesKey("user_email")
        val pcId = stringPreferencesKey("pc_id")
        val pcName = stringPreferencesKey("pc_name")
        val phoneDeviceId = stringPreferencesKey("phone_device_id")
        val lanControllerHost = stringPreferencesKey("lan_controller_host")
        val lanAgentHost = stringPreferencesKey("lan_agent_host")
        val lanAgentFingerprint = stringPreferencesKey("lan_agent_fingerprint")
        val preferLan = booleanPreferencesKey("prefer_lan")
        val biometricOnOpen = booleanPreferencesKey("biometric_on_open")
    }

    val supabaseUrl: Flow<String> = context.dataStore.data.map { it[Keys.supabaseUrl] ?: "" }
    val anonKey: Flow<String> = context.dataStore.data.map { it[Keys.anonKey] ?: "" }
    val accessToken: Flow<String> = context.dataStore.data.map { it[Keys.accessToken] ?: "" }
    val pcId: Flow<String> = context.dataStore.data.map { it[Keys.pcId] ?: "" }
    val pcName: Flow<String> = context.dataStore.data.map { it[Keys.pcName] ?: "" }
    val userEmail: Flow<String> = context.dataStore.data.map { it[Keys.userEmail] ?: "" }
    val preferLan: Flow<Boolean> = context.dataStore.data.map { it[Keys.preferLan] ?: true }
    val biometricOnOpen: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.biometricOnOpen] ?: true }

    val isConfigured: Flow<Boolean> = context.dataStore.data.map {
        !(it[Keys.supabaseUrl]).isNullOrBlank() && !(it[Keys.anonKey]).isNullOrBlank()
    }

    val isSignedIn: Flow<Boolean> = context.dataStore.data.map {
        !(it[Keys.accessToken]).isNullOrBlank()
    }

    val isPaired: Flow<Boolean> = context.dataStore.data.map {
        !(it[Keys.pcId]).isNullOrBlank() && !(it[Keys.phoneDeviceId]).isNullOrBlank()
    }

    suspend fun snapshot(): Snapshot {
        val prefs = context.dataStore.data.first()
        return Snapshot(
            supabaseUrl = prefs[Keys.supabaseUrl] ?: "",
            anonKey = prefs[Keys.anonKey] ?: "",
            accessToken = prefs[Keys.accessToken] ?: "",
            refreshToken = prefs[Keys.refreshToken] ?: "",
            expiresAtEpochSeconds = prefs[Keys.expiresAt] ?: 0L,
            pcId = prefs[Keys.pcId] ?: "",
            pcName = prefs[Keys.pcName] ?: "",
            phoneDeviceId = prefs[Keys.phoneDeviceId] ?: "",
            lanControllerHost = prefs[Keys.lanControllerHost] ?: "wolfpc.local",
            lanAgentHost = prefs[Keys.lanAgentHost] ?: "",
            lanAgentFingerprint = prefs[Keys.lanAgentFingerprint] ?: "",
            preferLan = prefs[Keys.preferLan] ?: true,
        )
    }

    data class Snapshot(
        val supabaseUrl: String,
        val anonKey: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val pcId: String,
        val pcName: String,
        val phoneDeviceId: String,
        val lanControllerHost: String,
        val lanAgentHost: String,
        val lanAgentFingerprint: String,
        val preferLan: Boolean,
    ) {
        val tokenExpiringSoon: Boolean
            get() = expiresAtEpochSeconds - System.currentTimeMillis() / 1000 < 120
    }

    suspend fun setProject(url: String, anon: String) = context.dataStore.edit {
        it[Keys.supabaseUrl] = url.trimEnd('/')
        it[Keys.anonKey] = anon.trim()
    }

    suspend fun setSession(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        email: String,
    ) = context.dataStore.edit {
        it[Keys.accessToken] = accessToken
        it[Keys.refreshToken] = refreshToken
        it[Keys.expiresAt] = System.currentTimeMillis() / 1000 + expiresInSeconds
        it[Keys.userEmail] = email
    }

    suspend fun setPairing(pcId: String, pcName: String, phoneDeviceId: String) =
        context.dataStore.edit {
            it[Keys.pcId] = pcId
            it[Keys.pcName] = pcName
            it[Keys.phoneDeviceId] = phoneDeviceId
        }

    suspend fun setLan(controllerHost: String, agentHost: String, agentFingerprint: String) =
        context.dataStore.edit {
            it[Keys.lanControllerHost] = controllerHost
            it[Keys.lanAgentHost] = agentHost
            it[Keys.lanAgentFingerprint] = agentFingerprint
        }

    suspend fun setPreferLan(value: Boolean) = context.dataStore.edit { it[Keys.preferLan] = value }

    suspend fun setBiometricOnOpen(value: Boolean) =
        context.dataStore.edit { it[Keys.biometricOnOpen] = value }

    /** Sign out. Keeps the project config so the user does not retype it. */
    suspend fun clearSession() = context.dataStore.edit {
        it.remove(Keys.accessToken)
        it.remove(Keys.refreshToken)
        it.remove(Keys.expiresAt)
    }

    /** Full reset, including the pairing. The Keystore key is deleted separately. */
    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
