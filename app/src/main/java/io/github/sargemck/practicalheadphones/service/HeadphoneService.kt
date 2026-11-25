package io.github.sargemck.practicalheadphones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import io.github.sargemck.practicalheadphones.AppGraph
import io.github.sargemck.practicalheadphones.BuildConfig
import io.github.sargemck.practicalheadphones.MainActivity
import io.github.sargemck.practicalheadphones.R
import android.bluetooth.BluetoothManager
import io.github.sargemck.practicalheadphones.link.AutoConnect
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.link.BoseAppDetector
import io.github.sargemck.practicalheadphones.link.HeadphonesLocator
import io.github.sargemck.practicalheadphones.link.MultiBrandConnector
import io.github.sargemck.practicalheadphones.link.ScreenMonitor
import io.github.sargemck.practicalheadphones.link.SystemLinkMonitor
import io.github.sargemck.practicalheadphones.state.FileLastKnownStore
import io.github.sargemck.practicalheadphones.state.HeadphoneRepository
import io.github.sargemck.practicalheadphones.widget.HeadphoneWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the one connection to the headphones. Started by [MainActivity], or by [PresenceService]
 * when the associated headphones connect. With auto-connect set up it stops itself once the
 * headphones are away and no screen is visible ([ServiceLifetime]); without it, it stays up while
 * they're away so it hears the next connected event.
 */
class HeadphoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: HeadphoneRepository
    private lateinit var locator: HeadphonesLocator
    private lateinit var systemLink: SystemLinkMonitor
    private lateinit var screen: ScreenMonitor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Headphones", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build(NotificationModel("Headphones", "Starting…", emptyList())), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        locator = HeadphonesLocator(this)
        repository = HeadphoneRepository(
            scope = scope,
            driverConnector = MultiBrandConnector(this, locator, BmapLog.frames, BmapLog::d),
            clock = SystemClock::elapsedRealtime,
            log = BmapLog::d,
            // No-backup dir: the snapshot (including the headphones' name) never leaves the phone.
            lastKnownStore = FileLastKnownStore(File(noBackupFilesDir, "last-known.txt"), BmapLog::d),
            onSelectDevice = { address ->
                locator.selectAddress(address)
                refreshDevicesAndCompanion()
                systemLink.stop()
                systemLink.start()
                repository.reconnect()
            },
        )
        refreshDevicesAndCompanion()
        AppGraph.controller.value = repository
        systemLink = SystemLinkMonitor(this, locator, { link ->
            refreshDevicesAndCompanion()
            repository.onSystemLink(link)
        }, BmapLog::d).also { it.start() }
        screen = ScreenMonitor(this, repository::onScreen).also { it.start() }

        scope.launch { uiVisible.collect(repository::onUiVisible) }
        scope.launch {
            repository.state.map(NotificationModel::from).distinctUntilChanged().collect { manager.notify(NOTIFICATION_ID, build(it)) }
        }
        scope.launch {
            repository.state.collect { HeadphoneWidgetProvider.updateAll(this@HeadphoneService, it) }
        }
        if (BuildConfig.DEBUG) scope.launch { repository.state.collect { BmapLog.d("STATE ${it.summary()}") } }

        val autoConnect = AutoConnect(this, BmapLog::d).also { it.observe() }
        scope.launch {
            combine(repository.state.map { it.link }.distinctUntilChanged(), uiVisible) { link, visible -> link to visible }
                .collectLatest { (link, visible) ->
                    // Checked each time: the association can be made while the service runs.
                    val set = autoConnect.status() == AutoConnect.Status.On
                    if (!ServiceLifetime.shouldStop(link, visible, set)) return@collectLatest
                    delay(ServiceLifetime.STOP_AFTER_MS)
                    BmapLog.d("SERVICE stopping: headphones away ($link), app not visible")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEXT_MODE -> repository.nextFavouriteMode()
            ACTION_TOGGLE_ANC -> repository.toggleAnc()
            ACTION_RECONNECT -> repository.reconnect()
        }
        refreshDevicesAndCompanion()
        // A restart from the background isn't allowed to go foreground; M6 handles auto-start.
        return START_NOT_STICKY
    }

    private fun refreshDevicesAndCompanion() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.let { runCatching { locator.listAll(it) }.getOrDefault(emptyList()) }.orEmpty()
        repository.setAvailableDevices(devices)
        val active = adapter?.let { runCatching { locator.find(it) }.getOrNull() }
        val brand = active?.let { locator.brandOf(it) }
        val companion = BoseAppDetector.detectFor(this, brand)
        repository.setBoseAppInstalled(companion.installed, companion.displayName, companion.packageName)
    }

    override fun onDestroy() {
        AppGraph.controller.value = null
        HeadphoneWidgetProvider.updateAll(this, null)
        systemLink.stop()
        screen.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun build(model: NotificationModel): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                for (action in model.actions) {
                    val serviceAction = if (action == NotificationAction.NextMode) ACTION_NEXT_MODE else ACTION_TOGGLE_ANC
                    val pending = PendingIntent.getService(
                        this@HeadphoneService,
                        serviceAction.hashCode(),
                        Intent(this@HeadphoneService, HeadphoneService::class.java).setAction(serviceAction),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(Notification.Action.Builder(null, NotificationModel.label(action), pending).build())
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_NEXT_MODE = "io.github.sargemck.practicalheadphones.NEXT_MODE"
        const val ACTION_TOGGLE_ANC = "io.github.sargemck.practicalheadphones.TOGGLE_ANC"
        const val ACTION_RECONNECT = "io.github.sargemck.practicalheadphones.RECONNECT"

        /** Set by [MainActivity] in onStart/onStop; drives the polling cadence. */
        val uiVisible = MutableStateFlow(false)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HeadphoneService::class.java))
        }
    }
}
