package com.wolfpc.remote.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wolfpc.remote.data.model.PcStatus
import com.wolfpc.remote.data.model.SessionState
import com.wolfpc.remote.data.model.WindowsState
import com.wolfpc.remote.data.model.WolfCommand
import com.wolfpc.remote.ui.WolfUiState
import com.wolfpc.remote.ui.components.ConfirmDialog
import com.wolfpc.remote.ui.components.LoginPrompt

/**
 * The home screen from PRD §11.
 *
 * Two status lines rather than one, because the controller and Windows are
 * genuinely independent signals (PRD §28) and collapsing them into a single
 * "online" light would be a lie the user acts on.
 */
@Composable
fun HomeScreen(
    state: WolfUiState,
    onCommand: (WolfCommand) -> Unit,
    onRefresh: () -> Unit,
    onApproveLogin: () -> Unit,
    onDenyLogin: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    var pendingConfirmation by remember { mutableStateOf<WolfCommand?>(null) }

    val status = state.status

    // Reachable by *either* path. Gating on the cloud flags alone was a real
    // bug: with no internet the app cannot read pc_status at all, so it
    // concluded everything was offline and disabled precisely the buttons the
    // LAN fallback exists to keep working.
    val controllerOnline = status?.controllerOnline == true || state.lanControllerUp
    val agentOnline = status?.agentOnline == true || state.lanAgentUp

    // Running on the home network only.
    val localOnly = !state.cloudReachable && (state.lanControllerUp || state.lanAgentUp)

    val windows = status?.windowsState ?: WindowsState.UNKNOWN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.pcName.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    letterSpacing = 2.sp,
                )
                Text(
                    if (state.realtimeConnected) "live" else "polling",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
                }
            }
            IconButton(onClick = onHistory) {
                Icon(Icons.Default.History, contentDescription = "Command history")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!state.cloudReachable) {
            OfflineBanner(localOnly)
            Spacer(Modifier.height(12.dp))
        }

        StatusCard(status, controllerOnline, agentOnline, windows, localOnly)

        Spacer(Modifier.height(28.dp))

        // POWER ON is the only command that works with Windows off, so it gets
        // the big control. It also needs no confirmation (PRD §27) — turning a
        // computer on destroys nothing.
        Button(
            onClick = { onCommand(WolfCommand.POWER_ON) },
            enabled = controllerOnline && !state.busy,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("POWER ON", style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
        }

        if (!controllerOnline) {
            Text(
                "The controller is offline, so power on and hard reset are unavailable. " +
                    "The case buttons still work.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { pendingConfirmation = WolfCommand.RESTART },
                enabled = agentOnline && !state.busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("RESTART")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { pendingConfirmation = WolfCommand.SHUTDOWN },
                enabled = agentOnline && !state.busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("SHUTDOWN")
            }
        }

        if (!agentOnline) {
            Text(
                if (!state.agentNetworkPrivate) {
                    "Shutdown and restart are unavailable. Windows has classified the " +
                        "PC's network as Public, which makes the firewall rule for the " +
                        "local connection inert. On the PC: Settings, Network & internet, " +
                        "Ethernet, set the network profile to Private."
                } else {
                    "Shutdown and restart need the Windows agent, which is not reporting in."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Two states want unlocking, and only one of them is the login screen.
        // Win+L leaves windows_state at LOGGED_IN with session_state LOCKED -
        // the user is still signed in, the screen is just locked - so gating
        // on LOGIN_SCREEN alone hid the button in the commonest case there is.
        val locked = windows == WindowsState.LOGIN_SCREEN ||
            status?.sessionState == SessionState.LOCKED

        if (!locked) {
            OutlinedButton(
                onClick = { onCommand(WolfCommand.LOCK) },
                enabled = agentOnline && !state.busy && windows == WindowsState.LOGGED_IN,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("LOCK PC")
            }
            Spacer(Modifier.height(14.dp))
        }

        if (locked) {
            // Only offer unlock when a credential provider is actually
            // installed. Phase 8 is optional (PRD §31), and on a PC without it
            // this button can only ever cost a biometric prompt and return
            // "not installed" — which reads as a broken app rather than an
            // absent feature.
            if (status?.phoneLoginAvailable == true) {
                Button(
                    onClick = { onCommand(WolfCommand.LOGIN) },
                    enabled = agentOnline && !state.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("UNLOCK PC")
                }
            } else {
                Text(
                    "The PC is locked. Unlocking from here needs the Wolf tile to be " +
                        "showing on its login screen — if it is, give it a few seconds " +
                        "and tap Refresh. Otherwise, sign in with your " +
                        "Windows password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.weight(1f))

        // Hard reset lives at the bottom, outlined in the error colour and
        // behind a typed-intent dialog. It is the one control here that can
        // destroy work.
        OutlinedButton(
            onClick = { pendingConfirmation = WolfCommand.HARD_RESET },
            enabled = controllerOnline && !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("HARD RESET")
        }

        Spacer(Modifier.height(20.dp))
    }

    // Phase 8. This takes precedence over everything else on screen: the
    // person it is asking is standing at a login screen waiting for it.
    state.pendingLogin?.let { challenge ->
        LoginPrompt(
            pcName = state.pcName,
            challenge = challenge,
            onApprove = onApproveLogin,
            onDeny = onDenyLogin,
        )
    }

    pendingConfirmation?.let { command ->
        ConfirmDialog(
            command = command,
            onConfirm = {
                pendingConfirmation = null
                onCommand(command)
            },
            onDismiss = { pendingConfirmation = null },
        )
    }
}

/**
 * Shown when the cloud cannot be reached.
 *
 * Says which of the two situations the user is actually in, because they call
 * for opposite actions: on the home network everything still works and they
 * should carry on, while away from it nothing will and they should stop
 * pressing buttons.
 */
@Composable
private fun OfflineBanner(localOnly: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (localOnly) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (localOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (localOnly) {
                "No internet — running over your home network."
            } else {
                "No connection to your PC. Check you are on the home Wi-Fi, or wait " +
                    "for the internet to come back."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun StatusCard(
    status: PcStatus?,
    controllerOnline: Boolean,
    agentOnline: Boolean,
    windows: WindowsState,
    localOnly: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {

            StatusRow(
                label = "Controller",
                value = when {
                    controllerOnline && localOnly -> "Online, local"
                    controllerOnline -> "Online"
                    else -> "Offline"
                },
                online = controllerOnline,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )

            StatusRow(
                label = "Windows",
                value = if (localOnly && agentOnline && windows == WindowsState.UNKNOWN)
                    "Reachable" else status?.displayLabel ?: windows.label,
                online = agentOnline,
            )

            if (localOnly && agentOnline) {
                Text(
                    "The PC is answering on your network. Its detailed state comes " +
                        "through the internet, which is down.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else if (!agentOnline && windows == WindowsState.UNKNOWN) {
                Text(
                    "Nothing here can see whether the PC is powered — the agent is " +
                        "simply not reporting in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            status?.updatedAt?.let {
                Text(
                    "updated ${it.take(19).replace('T', ' ')} UTC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            if (status == null && !localOnly) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("waiting for first status", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (online) Color(0xFF49B675) else Color(0xFF8A8F98),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}
