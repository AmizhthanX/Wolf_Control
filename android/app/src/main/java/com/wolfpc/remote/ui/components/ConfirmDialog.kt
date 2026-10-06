package com.wolfpc.remote.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wolfpc.remote.data.model.WolfCommand

/**
 * Confirmation, scaled to consequence (PRD §27).
 *
 * Shutdown and restart get an ordinary yes/no. Hard reset makes you type the
 * word, because it is indistinguishable from yanking the power cord and the
 * muscle memory for tapping a blue button is exactly what it needs to defeat.
 */
@Composable
fun ConfirmDialog(
    command: WolfCommand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dangerous = command == WolfCommand.HARD_RESET
    var typed by remember { mutableStateOf("") }
    val unlocked = !dangerous || typed.trim().equals("RESET", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (command) {
                    WolfCommand.HARD_RESET -> "Hard reset"
                    WolfCommand.SHUTDOWN -> "Shut down Windows?"
                    WolfCommand.RESTART -> "Restart Windows?"
                    else -> "Confirm"
                },
                color = if (dangerous) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    when (command) {
                        WolfCommand.HARD_RESET ->
                            "This cuts the machine off mid-flight, exactly like pressing the " +
                                "reset button on the case. Anything unsaved is lost, and Windows " +
                                "may run a disk check on the way back up.\n\n" +
                                "Use shutdown or restart unless Windows has stopped responding."
                        WolfCommand.SHUTDOWN ->
                            "Save your work before continuing. Windows will start shutting down " +
                                "5 seconds after the command reaches the PC. Unsaved work may be lost."
                        WolfCommand.RESTART ->
                            "Save your work before continuing. Windows will start restarting " +
                                "5 seconds after the command reaches the PC. Unsaved work may be lost."
                        else -> "Confirm this action."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (dangerous) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Type RESET to confirm") },
                        singleLine = true,
                        isError = typed.isNotEmpty() && !unlocked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "You will be asked for your fingerprint next.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = unlocked) {
                Text(
                    when (command) {
                        WolfCommand.HARD_RESET -> "HARD RESET"
                        WolfCommand.SHUTDOWN -> "Shut down"
                        WolfCommand.RESTART -> "Restart"
                        else -> "Confirm"
                    },
                    color = if (dangerous && unlocked) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
