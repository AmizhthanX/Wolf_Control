package com.wolfpc.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wolfpc.remote.ui.HistoryScreen
import com.wolfpc.remote.ui.PairScreen
import com.wolfpc.remote.ui.SettingsScreen
import com.wolfpc.remote.ui.SetupScreen
import com.wolfpc.remote.ui.SignInScreen
import com.wolfpc.remote.ui.Stage
import com.wolfpc.remote.ui.WolfViewModel
import com.wolfpc.remote.ui.home.HomeScreen
import com.wolfpc.remote.ui.scan.QrScannerScreen
import com.wolfpc.remote.ui.theme.WolfTheme

/**
 * FragmentActivity rather than ComponentActivity: BiometricPrompt needs a
 * fragment host to survive configuration changes while the sensor dialog is up.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WolfApplication

        setContent {
            WolfTheme {
                val viewModel: WolfViewModel = viewModel(
                    factory = WolfViewModel.Factory(app),
                )
                WolfApp(viewModel, this)
            }
        }
    }
}

private enum class Screen { HOME, HISTORY, SETTINGS }

@Composable
private fun WolfApp(viewModel: WolfViewModel, activity: FragmentActivity) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.HOME) }
    // The scanner overlays whichever onboarding step is showing rather than
    // being a stage of its own: a scan can arrive at setup or at pairing, and
    // the stage it lands on is decided by what the payload turned out to hold.
    var scanning by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Asked once the user reaches the working app, not at first launch. On a
    // setup screen there is nothing to be notified about yet, and a permission
    // prompt in front of the first useful screen is the kind users reflexively
    // decline. Declining costs notifications and nothing else.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: the app is fully usable either way */ }

    LaunchedEffect(state.stage) {
        if (state.stage == Stage.READY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (scanning) {
                QrScannerScreen(
                    onScanned = { payload ->
                        scanning = false
                        viewModel.applyScannedPayload(payload)
                    },
                    onCancel = { scanning = false },
                )
                return@Scaffold
            }

            when (state.stage) {
                Stage.LOADING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                Stage.SETUP -> SetupScreen(
                    busy = state.busy,
                    onScan = { scanning = true },
                    onSave = { url, anon -> viewModel.saveProject(url, anon) },
                )

                Stage.SIGN_IN -> SignInScreen(state.busy) { email, password ->
                    viewModel.signIn(email, password)
                }

                Stage.PAIR -> PairScreen(
                    busy = state.busy,
                    prefilledCode = state.scannedCode,
                    onScan = { scanning = true },
                    onPair = { code -> viewModel.pair(activity, code) },
                )

                Stage.READY -> when (screen) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        onCommand = { viewModel.send(activity, it) },
                        onRefresh = { viewModel.refresh() },
                        onApproveLogin = { viewModel.approveLogin(activity) },
                        onDenyLogin = { viewModel.denyLogin() },
                        onHistory = { screen = Screen.HISTORY },
                        onSettings = { screen = Screen.SETTINGS },
                    )

                    Screen.HISTORY -> HistoryScreen(state.history) { screen = Screen.HOME }

                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        onBack = { screen = Screen.HOME },
                        onPreferLan = viewModel::setPreferLan,
                        onSaveLan = viewModel::setLanHosts,
                        onSignOut = { viewModel.signOut(); screen = Screen.HOME },
                        onUnpair = { viewModel.unpair(); screen = Screen.HOME },
                    )
                }
            }
        }
    }
}
