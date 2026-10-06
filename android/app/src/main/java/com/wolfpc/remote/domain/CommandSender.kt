package com.wolfpc.remote.domain

import androidx.fragment.app.FragmentActivity
import com.wolfpc.remote.data.SessionStore
import com.wolfpc.remote.data.model.Envelope
import com.wolfpc.remote.data.model.WolfCommand
import com.wolfpc.remote.data.net.LanClient
import com.wolfpc.remote.data.net.SupabaseClient
import com.wolfpc.remote.data.net.WolfApiException
import com.wolfpc.remote.security.BiometricSigner
import com.wolfpc.remote.security.SignResult

sealed interface SendOutcome {
    data class Sent(val commandId: String, val path: Path) : SendOutcome
    data object Cancelled : SendOutcome
    data class Failed(val message: String) : SendOutcome
    data object ReEnrolNeeded : SendOutcome

    enum class Path { LAN, CLOUD }
}

/**
 * One place where a button press becomes a signed, delivered command.
 *
 * Order of operations matters and is the same for every command:
 *   1. build the envelope, which fixes the command id, nonce and expiry
 *   2. biometric prompt, which authorises exactly one signature over it
 *   3. deliver — LAN first when we are at home, cloud otherwise
 *
 * Signing before choosing a path is what makes the fallback safe: the same
 * envelope is valid on either route and neither can alter it. (PRD §8, §12)
 */
class CommandSender(
    private val store: SessionStore,
    private val cloud: SupabaseClient,
    private val lan: LanClient,
) {

    suspend fun send(
        activity: FragmentActivity,
        command: WolfCommand,
    ): SendOutcome {
        val snapshot = store.snapshot()
        if (snapshot.pcId.isBlank()) return SendOutcome.Failed("this phone is not paired with a PC")

        val envelope = Envelope.unsigned(snapshot.pcId, command)

        val signed = when (val result = BiometricSigner.sign(
            activity = activity,
            title = promptTitle(command),
            subtitle = promptSubtitle(command),
            message = envelope.signingString(),
        )) {
            is SignResult.Ok -> envelope.copy(signature = result.signatureB64Url)
            SignResult.Cancelled -> return SendOutcome.Cancelled
            SignResult.KeyInvalidated -> return SendOutcome.ReEnrolNeeded
            is SignResult.Failed -> return SendOutcome.Failed(result.message)
        }

        // The LAN attempt is bounded by two-second timeouts, so a phone that is
        // out of the house pays at most a couple of seconds before the cloud
        // takes over. Anything longer and the fallback would feel broken.
        if (snapshot.preferLan) {
            val lanError = when (command.target) {
                WolfCommand.Target.CONTROLLER ->
                    lan.sendToController(snapshot.lanControllerHost, signed)
                WolfCommand.Target.AGENT ->
                    lan.sendToAgent(snapshot.lanAgentHost, snapshot.lanAgentFingerprint, signed)
            }
            if (lanError == null) {
                return SendOutcome.Sent(signed.commandId, SendOutcome.Path.LAN)
            }
        }

        return try {
            val id = cloud.enqueueCommand(signed)
            SendOutcome.Sent(id.ifBlank { signed.commandId }, SendOutcome.Path.CLOUD)
        } catch (e: WolfApiException) {
            SendOutcome.Failed(explain(e))
        }
    }

    private fun explain(e: WolfApiException): String = when (e.code) {
        "offline" -> "No connection to the PC, on the network or through the internet."
        "expired" -> "That took too long and the command expired. Try again."
        "replay" -> "That command was already sent."
        "rate_limited" -> "Too many commands just now. Wait a moment."
        "not_found" -> "The PC is not registered yet. Check the agent is installed."
        "forbidden" -> "This phone is not allowed to control that PC."
        "bad_signature" -> "The signature was rejected. You may need to pair this phone again."
        else -> e.message ?: "Command failed."
    }

    private fun promptTitle(command: WolfCommand): String = when (command) {
        WolfCommand.POWER_ON -> "Power on the PC"
        WolfCommand.SHUTDOWN -> "Shut down Windows"
        WolfCommand.RESTART -> "Restart Windows"
        WolfCommand.HARD_RESET -> "Hard reset the PC"
        WolfCommand.LOGIN -> "Unlock the PC"
        WolfCommand.LOCK -> "Lock the PC"
    }

    private fun promptSubtitle(command: WolfCommand): String = when (command) {
        WolfCommand.HARD_RESET -> "Confirm with your fingerprint. Unsaved work will be lost."
        else -> "Confirm with your fingerprint"
    }
}
