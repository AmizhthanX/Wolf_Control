package com.wolfpc.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First run: point the app at a Supabase project.
 *
 * Asked at runtime rather than baked into BuildConfig so that the repository
 * carries no project identifiers, and so a rebuilt APK is not needed to move
 * between a test project and the real one. Both values are public — the anon
 * key identifies the project, it authorises nothing on its own.
 */
@Composable
fun SetupScreen(busy: Boolean, onScan: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var anon by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("WOLF PC", style = MaterialTheme.typography.displaySmall, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect this phone to your Supabase project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(24.dp))

        // Scanning here fills in the project, the pairing code and the home
        // network details in one go, which is the whole point of the QR. The
        // fields below stay for anyone who would rather type.
        Button(
            onClick = onScan,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("SCAN THE QR ON YOUR PC") }

        Spacer(Modifier.height(20.dp))

        Text(
            "or enter it by hand",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Project URL") },
            placeholder = { Text("https://xxxx.supabase.co") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = anon,
            onValueChange = { anon = it.trim() },
            label = { Text("Anon key") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { onSave(url, anon) },
            enabled = !busy && url.isNotBlank() && anon.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("CONTINUE") }
    }
}

@Composable
fun SignInScreen(busy: Boolean, onSignIn: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign in", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "The account that owns this PC.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onSignIn(email, password) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("SIGN IN") }
    }
}

/**
 * Pairing (PRD §17).
 *
 * Scanned or typed; both redeem the same code. It is read off the PC's own
 * screen by the person holding both devices, lives ten minutes, is single use,
 * and still has to be redeemed by an account that owns that PC — so its job is
 * to link two things the user already controls, not to be a secret.
 */
@Composable
fun PairScreen(
    busy: Boolean,
    prefilledCode: String?,
    onScan: () -> Unit,
    onPair: (String) -> Unit,
) {
    // Keyed on the prefill so a scan that lands after this screen is already
    // composed still fills the field, instead of being silently dropped.
    var code by remember(prefilledCode) { mutableStateOf(prefilledCode.orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pair with your PC", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "On the PC, open an administrator prompt and run:\n\n" +
                "    \"C:\\Program Files\\WolfPC\\WolfAgent.exe\" pair\n\n" +
                "Scan the QR it prints, or type the code underneath it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onScan,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("SCAN QR") }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("Pairing code") },
            placeholder = { Text("WOLF-123456") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { onPair(code) },
            enabled = !busy && code.length >= 11,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("PAIR THIS PHONE") }

        Spacer(Modifier.height(16.dp))

        Text(
            "Pairing creates a signing key inside this phone's secure hardware. " +
                "It never leaves the phone, and every command is signed with it after " +
                "your fingerprint unlocks it.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
    }
}
