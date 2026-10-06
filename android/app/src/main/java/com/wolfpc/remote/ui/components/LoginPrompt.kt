package com.wolfpc.remote.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wolfpc.remote.data.model.LoginChallenge
import kotlinx.coroutines.delay

/**
 * "Your PC is asking to sign in." (PRD section 15, Phase 8)
 *
 * This is the one prompt in the app that interrupts rather than waits, because
 * the person it interrupts is already standing at a login screen watching a
 * spinner. It shows the time left for the same reason: a silent expiry would
 * leave them tapping Approve on a request the PC stopped waiting for.
 *
 * Deliberately nothing here identifies *what* is being approved beyond the PC's
 * name. A lock screen is a public surface and the countdown is visible on it;
 * the challenge, the signature and the command id are not things a passer-by
 * needs to read (PRD sections 16, 23).
 */
@Composable
fun LoginPrompt(
    pcName: String,
    challenge: LoginChallenge,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    var secondsLeft by remember(challenge.id) { mutableLongStateOf(challenge.secondsLeft) }

    LaunchedEffect(challenge.id) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft = challenge.secondsLeft
        }
        // Expiry is the server's call, not this dialog's. Closing here without
        // telling the ViewModel would leave the prompt to reappear on the next
        // refresh; letting it go quiet and stale would be worse. So the dialog
        // simply stops offering Approve, and the next read clears it.
        onDeny()
    }

    AlertDialog(
        onDismissRequest = { /* an accidental tap outside must not deny a login */ },
        title = { Text("Sign in to ${pcName.ifBlank { "your PC" }}?") },
        text = {
            Column {
                Text(
                    "Windows is waiting at the login screen. Approving unlocks it " +
                        "with your fingerprint.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { (secondsLeft / 60f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (secondsLeft > 0) "$secondsLeft seconds left" else "expired",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove, enabled = secondsLeft > 0) { Text("APPROVE") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("NOT ME") }
        },
    )
}
