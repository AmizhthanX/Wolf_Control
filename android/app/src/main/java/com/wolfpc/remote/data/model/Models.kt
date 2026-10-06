package com.wolfpc.remote.data.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PRD §13. The five commands, and which side of the system performs each. */
enum class WolfCommand(val wire: String, val lifetimeSeconds: Long, val target: Target) {
    POWER_ON("POWER_ON", 30, Target.CONTROLLER),
    HARD_RESET("HARD_RESET", 15, Target.CONTROLLER),
    SHUTDOWN("SHUTDOWN", 60, Target.AGENT),
    RESTART("RESTART", 60, Target.AGENT),
    LOGIN("LOGIN", 60, Target.AGENT),
    LOCK("LOCK", 60, Target.AGENT);

    enum class Target { CONTROLLER, AGENT }
}

/** PRD §28. Controller liveness and Windows state are separate signals. */
enum class WindowsState {
    OFF, BOOTING, LOGIN_SCREEN, LOGGED_IN, SHUTTING_DOWN, RESTARTING, UNKNOWN;

    val label: String
        get() = when (this) {
            OFF -> "Off"
            BOOTING -> "Booting"
            LOGIN_SCREEN -> "Login screen"
            LOGGED_IN -> "Logged in"
            SHUTTING_DOWN -> "Shutting down"
            RESTARTING -> "Restarting"
            UNKNOWN -> "Unknown"
        }
}

enum class SessionState { NONE, LOCKED, ACTIVE }

/**
 * An open Windows login prompt (PRD section 15, Phase 8).
 *
 * The [challenge] is 32 random bytes and is not a secret: it is worthless
 * without a signature from a key that lives in this phone's Keystore and only
 * signs after a fresh biometric. What matters is [expiresAt] - the whole
 * exchange has 60 seconds, and somebody is standing at a login screen for all
 * of it.
 */
@Serializable
data class LoginChallenge(
    val id: String = "",
    @SerialName("pc_id") val pcId: String = "",
    val challenge: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    val result: String = "PENDING",
) {
    val secondsLeft: Long
        get() = runCatching {
            java.time.Duration.between(Instant.now(), Instant.parse(expiresAt)).seconds
        }.getOrDefault(0L).coerceAtLeast(0L)

    val isOpen: Boolean get() = result == "PENDING" && secondsLeft > 0

    /** Must match loginSigningString in login-respond and LoginFlow on the PC. */
    fun signingString(): String = listOf("WOLFLOGIN1", id, pcId, challenge).joinToString("\n")
}

@Serializable
data class PcStatus(
    @SerialName("pc_id") val pcId: String = "",
    @SerialName("controller_online") val controllerOnline: Boolean = false,
    @SerialName("windows_agent_online") val agentOnline: Boolean = false,
    @SerialName("windows_state") val windowsStateRaw: String = "UNKNOWN",
    @SerialName("session_state") val sessionStateRaw: String = "NONE",
    @SerialName("controller_last_seen") val controllerLastSeen: String? = null,
    @SerialName("agent_last_seen") val agentLastSeen: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("phone_login_available") val phoneLoginAvailable: Boolean = false,
    @SerialName("controller_lan_address") val controllerLanAddress: String? = null,
    @SerialName("agent_lan_address") val agentLanAddress: String? = null,
    @SerialName("agent_network_private") val agentNetworkPrivate: Boolean = true,
) {
    val windowsState: WindowsState
        get() = runCatching { WindowsState.valueOf(windowsStateRaw) }.getOrDefault(WindowsState.UNKNOWN)

    val sessionState: SessionState
        get() = runCatching { SessionState.valueOf(sessionStateRaw) }.getOrDefault(SessionState.NONE)

    /**
     * What to actually show for "Windows".
     *
     * Locked and signed-in are the same windows_state - the user is logged in
     * either way - and only session_state tells them apart. Reporting both and
     * then rendering only the first made a locked PC read as "Logged in",
     * which is the one state where it matters most: locked is precisely when
     * phone unlock is the thing you reached for the app to do.
     */
    val displayLabel: String
        get() = if (windowsState == WindowsState.LOGGED_IN && sessionState == SessionState.LOCKED)
            "Locked" else windowsState.label

    /**
     * Whether POWER_ON is the sensible primary action.
     *
     * Note this is driven by the *agent* being absent, not by any positive
     * evidence that the PC is off — nothing in the MVP can observe that. The
     * UI says so rather than pretending otherwise.
     */
    val looksPoweredOff: Boolean
        get() = !agentOnline && windowsState in setOf(WindowsState.OFF, WindowsState.UNKNOWN)
}

@Serializable
data class CommandRecord(
    @SerialName("id") val id: String = "",
    @SerialName("command") val command: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error") val error: String? = null,
)

/**
 * The signed envelope (PROTOCOL §2).
 *
 * `signingString` is built from the fields directly and never from serialised
 * JSON, so no amount of key reordering or whitespace can change what was
 * signed.
 */
@Serializable
data class Envelope(
    @SerialName("command_id") val commandId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("command") val command: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("signature") val signature: String = "",
) {
    fun signingString(): String = listOf(
        "WOLFPC1", commandId, deviceId, command, timestamp, expiresAt, nonce,
    ).joinToString("\n")

    companion object {
        private val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .withZone(ZoneOffset.UTC)

        fun unsigned(pcId: String, command: WolfCommand, now: Instant = Instant.now()): Envelope {
            val issued = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            return Envelope(
                commandId = UUID.randomUUID().toString(),
                deviceId = pcId,
                command = command.wire,
                timestamp = FORMAT.format(issued),
                expiresAt = FORMAT.format(issued.plusSeconds(command.lifetimeSeconds)),
                nonce = randomNonce(),
            )
        }

        /** 16 lowercase hex characters, from 8 bytes of SecureRandom. */
        private fun randomNonce(): String {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
