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
import io.github.sargemck.practicalheadphones.state.HeadphoneController
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.ui.Actions
import io.github.sargemck.practicalheadphones.ui.AppTheme
import io.github.sargemck.practicalheadphones.ui.HeadphonesApp
import io.github.sargemck.practicalheadphones.ui.Permission
import io.github.sargemck.practicalheadphones.ui.Permissions
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Remembered so a later "no prompt" (rationale false) can be told apart from "never asked".
        asked.edit().apply { for (permission in granted.keys) putBoolean(permission, true) }.apply()
        refreshPermissions()
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) HeadphoneService.start(this)
    }

    private val asked by lazy { getSharedPreferences("permissions-asked", MODE_PRIVATE) }
    private val permissionState = MutableStateFlow(Permissions())

    /** Debug demo mode: the screen runs on a simulated device instead of the service. */
    private var demo: HeadphoneController? = null

    private val wanted = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        if (demo != null) return
        HeadphoneService.uiVisible.value = true
        // Also picks up a permission granted in settings while the app was in the background.
        refreshPermissions()
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) HeadphoneService.start(this)
        val needed = wanted.filterNot(::granted)
        // Asked once per process. After that the screen offers it, so the prompt doesn't come back on every visit.
        if (needed.isNotEmpty() && !promptedThisProcess) {
            promptedThisProcess = true
            permissions.launch(needed.toTypedArray())
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun status(permission: String): Permission = when {
        granted(permission) -> Permission.Granted
        asked.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission) -> Permission.Blocked
        else -> Permission.Denied
    }

    private fun refreshPermissions() {
        permissionState.value = Permissions(
            bluetooth = status(Manifest.permission.BLUETOOTH_CONNECT),
            notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) status(Manifest.permission.POST_NOTIFICATIONS) else Permission.Granted,
        )
    }

    override fun onStop() {
        if (demo == null) HeadphoneService.uiVisible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DebugEntry.onCreate(this)
        demo = DebugEntry.demoController(this)
        val source = demo?.let { MutableStateFlow(it) } ?: AppGraph.controller
        val android = Actions(
            openBluetoothSettings = { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            openBoseAppSettings = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", BoseAppDetector.PACKAGE, null)))
            },
            openUrl = { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
            requestBluetooth = { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
            openAppSettings = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) },
        )
        val demoPermissions = DebugEntry.demoPermissions(this)
        setContent {
            val controller by source.collectAsStateWithLifecycle()
            val state by remember(controller) { controller?.state ?: MutableStateFlow(HeadphoneState()) }.collectAsStateWithLifecycle()
            val permissions by permissionState.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(controller) { controller?.messages?.collect { snackbar.showSnackbar(it.text) } }
            AppTheme {
                HeadphonesApp(
                    state,
                    controller?.let { Actions.of(it, android) } ?: android,
                    BuildConfig.VERSION_NAME,
                    snackbar,
                    permissions = demoPermissions ?: permissions,
                )
            }
        }
    }

    private companion object {
        @Volatile var promptedThisProcess = false
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Nothing on this phone handles it; the button just does nothing.
        }
    }
}
