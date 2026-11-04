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
import io.github.sargemck.practicalheadphones.link.AutoConnect
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.link.BoseAppDetector
import io.github.sargemck.practicalheadphones.link.HeadphonesLocator
import io.github.sargemck.practicalheadphones.link.RfcommConnector
import io.github.sargemck.practicalheadphones.link.ScreenMonitor
import io.github.sargemck.practicalheadphones.link.SystemLinkMonitor
import io.github.sargemck.practicalheadphones.state.FileLastKnownStore
import io.github.sargemck.practicalheadphones.state.HeadphoneRepository
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
    private lateinit var systemLink: SystemLinkMonitor
    private lateinit var screen: ScreenMonitor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Headphones", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build(NotificationModel("Headphones", "Starting…", emptyList())), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val locator = HeadphonesLocator(this)
        repository = HeadphoneRepository(
            scope = scope,
            connector = RfcommConnector(this, locator, BmapLog.frames, BmapLog::d),
            clock = SystemClock::elapsedRealtime,
            log = BmapLog::d,
            // No-backup dir: the snapshot (including the headphones' name) never leaves the phone.
            lastKnownStore = FileLastKnownStore(File(noBackupFilesDir, "last-known.txt"), BmapLog::d),
        )
        repository.setBoseAppInstalled(BoseAppDetector.isInstalled(this))
        AppGraph.controller.value = repository
        systemLink = SystemLinkMonitor(this, locator, repository::onSystemLink, BmapLog::d).also { it.start() }
        screen = ScreenMonitor(this, repository::onScreen).also { it.start() }

        scope.launch { uiVisible.collect(repository::onUiVisible) }
        scope.launch {
            repository.state.map(NotificationModel::from).distinctUntilChanged().collect { manager.notify(NOTIFICATION_ID, build(it)) }
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
        repository.setBoseAppInstalled(BoseAppDetector.isInstalled(this))
        // A restart from the background isn't allowed to go foreground; M6 handles auto-start.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppGraph.controller.value = null
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
