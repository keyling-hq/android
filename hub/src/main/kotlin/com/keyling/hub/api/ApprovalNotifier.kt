package com.keyling.hub.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keyling.hub.HubApp
import com.keyling.hub.R
import com.keyling.hub.ui.MainActivity

/**
 * Asks the user, with one notification per app, whether an unknown client app may type.
 * A request from an app that already has a notification showing posts nothing new.
 */
class ApprovalNotifier(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val showing = mutableSetOf<String>()

    fun request(caller: Caller) {
        synchronized(showing) {
            if (!showing.add(caller.packageName)) return
        }
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val id = notificationId(caller.packageName)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.approval_title, caller.label))
            .setContentText(context.getString(R.string.approval_text, caller.label))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action(caller, allow = false, R.string.approval_deny))
            .addAction(action(caller, allow = true, R.string.approval_allow))
            .build()
        notifications.notify(id, notification)
    }

    fun dismiss(packageName: String) {
        synchronized(showing) { showing.remove(packageName) }
        notifications.cancel(notificationId(packageName))
    }

    private fun action(caller: Caller, allow: Boolean, label: Int): Notification.Action {
        val intent = Intent(context, ApprovalReceiver::class.java)
            .putExtra(EXTRA_PACKAGE, caller.packageName)
            .putExtra(EXTRA_LABEL, caller.label)
            .putExtra(EXTRA_CERT, caller.certSha256)
            .putExtra(EXTRA_ALLOW, allow)
        val requestCode = notificationId(caller.packageName) * 2 + if (allow) 1 else 0
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, context.getString(label), pending).build()
    }

    private fun notificationId(packageName: String) = NOTIFICATION_BASE + (packageName.hashCode() and 0xFFFF)

    companion object {
        private const val CHANNEL_ID = "approvals"
        private const val NOTIFICATION_BASE = 0x10000
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CERT = "cert"
        const val EXTRA_ALLOW = "allow"
    }
}

/** Receives Allow and Deny from approval notifications. Not exported: only they can reach it. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as HubApp
        val packageName = intent.getStringExtra(ApprovalNotifier.EXTRA_PACKAGE) ?: return
        val label = intent.getStringExtra(ApprovalNotifier.EXTRA_LABEL) ?: packageName
        val cert = intent.getStringExtra(ApprovalNotifier.EXTRA_CERT) ?: return
        app.approvalNotifier.dismiss(packageName)
        if (!app.callers.hasCertificate(packageName, cert)) return
        val caller = Caller(packageName, label, cert)
        if (intent.getBooleanExtra(ApprovalNotifier.EXTRA_ALLOW, false)) {
            app.approvals.allow(caller)
        } else {
            app.approvals.deny(caller)
        }
    }
}
