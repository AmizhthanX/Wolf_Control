package com.wolfpc.remote.notif

import com.wolfpc.remote.data.model.CommandRecord
import com.wolfpc.remote.data.model.PcStatus
import com.wolfpc.remote.data.model.WindowsState

/**
 * Turns observed state changes into notifications. PRD §23.
 *
 * There is no push in this build by design, so notifications come from what the
 * app itself sees: the Realtime subscription, or the polling fallback behind it.
 * That covers the case that actually matters — you tap POWER ON, switch to
 * another app, and get told when the PC reaches the login screen. It does not
 * wake a phone whose app has been swapped out entirely; see NOTIFICATIONS.md
 * for why that is the accepted trade.
 *
 * All notification copy lives here, in one place, so it stays easy to confirm
 * that none of it carries anything sensitive. PRD §23 forbids passwords, keys,
 * nonces, signatures and pairing codes in a notification, and a lock screen is
 * a public surface.
 */
class StateNotifier(private val notifier: Notifier) {

    private var previous: PcStatus? = null
    private val commandStatus = mutableMapOf<String, String>()

    /** Forget everything, e.g. after sign-out, so the next sync is a clean baseline. */
    fun reset() {
        previous = null
        commandStatus.clear()
    }

    fun onStatus(current: PcStatus) {
        val before = previous
        previous = current

        // Nothing to compare against on the first read. Announcing the state
        // the PC was already in when the app opened would fire a notification
        // every single launch.
        if (before == null) return

        if (before.windowsStateRaw != current.windowsStateRaw) {
            when (current.windowsState) {
                WindowsState.LOGIN_SCREEN -> notifier.event(
                    "Login screen",
                    "The PC has booted and is waiting to be unlocked.",
                )
                WindowsState.LOGGED_IN -> notifier.event(
                    "Signed in",
                    "Someone is now signed in to Windows.",
                )
                WindowsState.OFF -> notifier.event(
                    "Shutdown complete",
                    "Windows has powered off.",
                )
                WindowsState.RESTARTING -> notifier.event(
                    "Restarting",
                    "Windows is closing your apps and restarting.",
                )
                WindowsState.SHUTTING_DOWN -> notifier.event(
                    "Shutting down",
                    "Windows is closing your apps and powering off.",
                )
                // BOOTING and UNKNOWN are steps on the way to something the
                // user cares about, not news in themselves.
                else -> Unit
            }
        }

        // Two independent signals, two independent alerts (PRD §28). Losing the
        // controller and losing the agent mean completely different things
        // about what you can still do, so they are never collapsed into one
        // "offline" message.
        if (before.controllerOnline && !current.controllerOnline) {
            notifier.event(
                "Controller offline",
                "The ESP32 stopped reporting in. Power on and hard reset are " +
                    "unavailable; the case buttons still work.",
                alert = true,
            )
        }
        if (before.agentOnline && !current.agentOnline) {
            notifier.event(
                "PC agent offline",
                "The Windows agent stopped reporting in. Shutdown, restart and " +
                    "status are unavailable.",
                alert = true,
            )
        }
    }

    /**
     * Notify on commands that have just reached a terminal state.
     *
     * Keyed on command id rather than on position, because history is ordered
     * by creation and a new command shifts everything down.
     */
    fun onCommands(history: List<CommandRecord>) {
        for (record in history) {
            val before = commandStatus.put(record.id, record.status)

            // Unseen commands are recorded silently: on first sync the whole
            // history is new, and replaying it as notifications would be a
            // wall of stale noise.
            if (before == null || before == record.status) continue

            when (record.status) {
                "EXECUTED" -> executedMessage(record.command)?.let {
                    notifier.event(it.first, it.second, alert = record.command == "HARD_RESET")
                }
                "FAILED", "EXPIRED" -> notifier.event(
                    "Command failed",
                    "${pretty(record.command)} did not complete" +
                        (record.error?.let { ": $it" } ?: "."),
                    alert = true,
                )
            }
        }

        // The map would otherwise grow for the life of the process. History is
        // capped at 30 rows, so anything no longer in it can never transition
        // again.
        val live = history.map { it.id }.toSet()
        commandStatus.keys.retainAll(live)
    }

    private fun executedMessage(command: String): Pair<String, String>? = when (command) {
        "POWER_ON" -> "PC turned on" to "The power button was pressed. Windows is starting."
        "HARD_RESET" -> "Hard reset executed" to
            "The reset button was pressed. Unsaved work may be lost."
        "SHUTDOWN" -> "Shutting down" to "Windows is closing your apps and powering off."
        "RESTART" -> "Restarting" to "Windows is closing your apps and restarting."
        "LOCK" -> "Lock requested" to "Windows was asked to lock your session."
        else -> null
    }

    private fun pretty(command: String): String =
        command.take(1) + command.drop(1).lowercase().replace('_', ' ')
}
