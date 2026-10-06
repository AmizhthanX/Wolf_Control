package com.wolfpc.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wolfpc.remote.data.model.CommandRecord

@Composable
fun HistoryScreen(history: List<CommandRecord>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("Command history", onBack)

        if (history.isEmpty()) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history) { record ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.command,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            record.status,
                            style = MaterialTheme.typography.labelMedium,
                            color = when (record.status) {
                                "EXECUTED" -> MaterialTheme.colorScheme.primary
                                "FAILED", "EXPIRED" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                        )
                    }
                    Text(
                        record.createdAt.take(19).replace('T', ' ') + " UTC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                    record.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: WolfUiState,
    onBack: () -> Unit,
    onPreferLan: (Boolean) -> Unit,
    onSaveLan: (String, String, String) -> Unit,
    onSignOut: () -> Unit,
    onUnpair: () -> Unit,
) {
    // Keyed on the stored values so the fields show what is actually saved.
    // They used to be hardcoded defaults, which meant this screen displayed a
    // blank agent address and fingerprint over a perfectly good configuration —
    // and saving would then have written those blanks back over it.
    var preferLan by remember(state.preferLan) { mutableStateOf(state.preferLan) }
    var controllerHost by remember(state.lanControllerHost) {
        mutableStateOf(state.lanControllerHost.ifBlank { "wolfpc.local" })
    }
    var agentHost by remember(state.lanAgentHost) { mutableStateOf(state.lanAgentHost) }
    var fingerprint by remember(state.lanFingerprint) { mutableStateOf(state.lanFingerprint) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar("Settings", onBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {

            Section("Account")
            Text(state.email, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Paired with ${state.pcName} (${state.pcId})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            Section("Home network")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Try the local network first", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Keeps power on and hard reset working when the internet is down.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = preferLan,
                    onCheckedChange = { preferLan = it; onPreferLan(it) },
                )
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = controllerHost,
                onValueChange = { controllerHost = it },
                label = { Text("Controller address") },
                supportingText = { Text("mDNS name or IP of the ESP32") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = agentHost,
                onValueChange = { agentHost = it },
                label = { Text("PC address and port") },
                placeholder = { Text("192.168.1.20:8443") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = fingerprint,
                onValueChange = { fingerprint = it.lowercase() },
                label = { Text("PC certificate fingerprint") },
                supportingText = { Text("From: WolfAgent.exe status") },
                minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { onSaveLan(controllerHost, agentHost, fingerprint) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save local network settings") }

            Section("Build")
            InstalledBuild()

            Section("This phone")
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { confirmUnpair = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(if (confirmUnpair) "Tap again to unpair" else "Unpair this phone") }

            if (confirmUnpair) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Unpairing destroys this phone's signing key. It cannot be recovered — " +
                        "you will pair again from scratch, and any command signed by the old " +
                        "key stops being accepted.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onUnpair,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Unpair now") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * When this APK was installed, taken from the package manager.
 *
 * Exists because "did the new build actually get installed?" came up during
 * testing and there was no way to answer it from the phone — two identical
 * screenshots, and no way to tell a bug from an un-installed fix.
 */
@Composable
private fun InstalledBuild() {
    val context = LocalContext.current
    val info = remember {
        runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val when_ = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(pkg.lastUpdateTime))
            "${pkg.versionName}  ·  installed $when_"
        }.getOrDefault("unknown")
    }
    Text(info, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}
