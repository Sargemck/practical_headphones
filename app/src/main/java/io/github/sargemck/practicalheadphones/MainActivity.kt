package io.github.sargemck.practicalheadphones

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sargemck.practicalheadphones.link.BoseAppDetector
import io.github.sargemck.practicalheadphones.service.HeadphoneService
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.ui.Actions
import io.github.sargemck.practicalheadphones.ui.AppTheme
import io.github.sargemck.practicalheadphones.ui.HeadphonesApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) HeadphoneService.start(this)
    }

    override fun onStart() {
        super.onStart()
        HeadphoneService.uiVisible.value = true
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) HeadphoneService.start(this) else permissions.launch(needed.toTypedArray())
    }

    override fun onStop() {
        HeadphoneService.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val android = Actions(
            openBluetoothSettings = { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            openBoseAppSettings = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", BoseAppDetector.PACKAGE, null)))
            },
            openUrl = { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
        )
        setContent {
            val controller by AppGraph.controller.collectAsStateWithLifecycle()
            val state by remember(controller) { controller?.state ?: MutableStateFlow(HeadphoneState()) }.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(controller) { controller?.messages?.collect { snackbar.showSnackbar(it.text) } }
            AppTheme {
                HeadphonesApp(state, controller?.let { Actions.of(it, android) } ?: android, BuildConfig.VERSION_NAME, snackbar)
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Nothing on this phone handles it; the button just does nothing.
        }
    }
}
