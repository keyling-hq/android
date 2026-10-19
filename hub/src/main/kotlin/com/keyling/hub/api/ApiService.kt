package com.keyling.hub.api

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import bridge.Result
import com.keyling.client.IKeylingCallback
import com.keyling.client.IKeylingHub
import com.keyling.client.IKeylingListener
import com.keyling.client.Keyling
import com.keyling.hub.HubApp
import com.keyling.hub.dongle.DongleState
import com.keyling.hub.dongle.DongleUnavailableException
import com.keyling.hub.layout.UnknownLayoutException
import com.keyling.hub.layout.UntypableTextException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "ApiService"

fun DongleState.toApiState(): Int = when (this) {
    DongleState.NotPaired -> Keyling.STATE_NOT_PAIRED
    DongleState.Idle -> Keyling.STATE_DISCONNECTED
    DongleState.Pairing, DongleState.Connecting -> Keyling.STATE_CONNECTING
    is DongleState.Connected -> Keyling.STATE_CONNECTED
    DongleState.NoPermission, DongleState.BluetoothOff, DongleState.BondLost, DongleState.PhoneBondMissing ->
        Keyling.STATE_NEEDS_ATTENTION
}

/**
 * The public API bound by client apps. Every request is checked against the caller's approval;
 * an unknown caller triggers an approval notification.
 */
class ApiService : Service() {
    private val app by lazy { application as HubApp }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = RemoteCallbackList<IKeylingListener>()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            app.dongle.state.map { it.toApiState() }.distinctUntilChanged().collect { state ->
                val n = listeners.beginBroadcast()
                try {
                    for (i in 0 until n) {
                        try {
                            listeners.getBroadcastItem(i).onStateChanged(state)
                        } catch (e: RemoteException) {
                            // The client died; RemoteCallbackList drops it.
                        }
                    }
                } finally {
                    listeners.finishBroadcast()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        listeners.kill()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** The approved caller, or null after replying to [callback] why not. */
    private fun approvedCaller(uid: Int, callback: IKeylingCallback?): Caller? {
        val caller = app.callers.forUid(uid)
        if (caller == null) {
            reply(callback, Keyling.RESULT_INVALID, "cannot identify the calling app")
            return null
        }
        return when (app.approvals.decision(caller.packageName)) {
            Decision.ALLOWED -> caller
            Decision.DENIED -> {
                reply(callback, Keyling.RESULT_DENIED, "${caller.label} is denied in the Keyling app")
                null
            }
            Decision.UNKNOWN -> {
                app.approvalNotifier.request(caller)
                reply(callback, Keyling.RESULT_NOT_APPROVED, "allow ${caller.label} in the Keyling app")
                null
            }
        }
    }

    private val binder = object : IKeylingHub.Stub() {
        override fun getApiVersion(): Int = Keyling.API_VERSION

        override fun getState(): Int = app.dongle.state.value.toApiState()

        override fun typeText(text: String?, layout: String?, callback: IKeylingCallback?) {
            if (text == null) return reply(callback, Keyling.RESULT_INVALID, "no text")
            val caller = approvedCaller(Binder.getCallingUid(), callback) ?: return
            val keyboard = try {
                app.layoutFor(layout)
            } catch (e: UnknownLayoutException) {
                return reply(callback, Keyling.RESULT_INVALID, e.message)
            }
            Log.i(TAG, "${caller.packageName} types ${text.length} chars, layout ${keyboard?.id ?: "dongle"}")
            app.scope.launch {
                val (result, message) = try {
                    when (val r = app.dongle.typeText(text, caller.label, keyboard)) {
                        Result.RESULT_OK -> Keyling.RESULT_OK to null
                        Result.RESULT_ABORTED -> Keyling.RESULT_ABORTED to null
                        Result.RESULT_INVALID -> Keyling.RESULT_INVALID to "the dongle rejected the request"
                        else -> Keyling.RESULT_FAILED to "the dongle could not type ($r)"
                    }
                } catch (e: UntypableTextException) {
                    Keyling.RESULT_INVALID to e.message
                } catch (e: IllegalArgumentException) {
                    Keyling.RESULT_FAILED to e.message
                } catch (e: DongleUnavailableException) {
                    Keyling.RESULT_UNAVAILABLE to e.message
                } catch (e: IOException) {
                    Keyling.RESULT_FAILED to e.message
                }
                if (result != Keyling.RESULT_OK) Log.i(TAG, "${caller.packageName}: result $result, $message")
                reply(callback, result, message)
            }
        }

        override fun abort() {
            approvedCaller(Binder.getCallingUid(), null) ?: return
            app.scope.launch { app.dongle.abort() }
        }

        override fun registerListener(listener: IKeylingListener?) {
            if (listener == null) return
            listeners.register(listener)
            try {
                listener.onStateChanged(app.dongle.state.value.toApiState())
            } catch (e: RemoteException) {
                listeners.unregister(listener)
            }
        }

        override fun unregisterListener(listener: IKeylingListener?) {
            if (listener != null) listeners.unregister(listener)
        }

        override fun getLayouts(): Array<String> = app.layouts.all.map { it.id }.toTypedArray()
    }

    private fun reply(callback: IKeylingCallback?, result: Int, message: String?) {
        try {
            callback?.onResult(result, message)
        } catch (e: RemoteException) {
            Log.i(TAG, "client gone before its result")
        }
    }
}
