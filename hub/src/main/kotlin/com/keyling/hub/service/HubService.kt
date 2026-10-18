package com.keyling.hub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.keyling.hub.R
import com.keyling.hub.dongle
import com.keyling.hub.dongle.DongleState
import com.keyling.hub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the dongle connection loop runs, with a
 * notification showing the connection state and a Disconnect action.
 */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            combine(dongle.state, dongle.running, dongle.status, dongle.typingClient) { state, running, status, client ->
                if (!running) null else notificationText(state, status?.typing == true, client)
            }.distinctUntilChanged().collect { text ->
                if (text != null) {
                    notifications.notify(NOTIFICATION_ID, notification(text))
                } else {
                    stopSelf()
                }
            }
        }
    }

    private fun notificationText(state: DongleState, typing: Boolean, client: String?): String = when {
        state !is DongleState.Connected -> getString(R.string.state_connecting)
        typing && client != null -> getString(R.string.state_typing_for, client)
        else -> getString(R.string.state_connected)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = notificationText(dongle.state.value, false, null)
        startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (intent?.action == ACTION_DISCONNECT) dongle.disconnect()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 0, Intent(this, HubService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), disconnect).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.keyling.hub.action.DISCONNECT"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, HubService::class.java))
            } catch (e: IllegalStateException) {
                Log.w("HubService", "cannot start the foreground service", e)
            }
        }
    }
}
