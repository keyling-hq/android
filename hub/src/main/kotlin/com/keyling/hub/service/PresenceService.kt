package com.keyling.hub.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import com.keyling.hub.dongle

/**
 * Woken by the system when the paired dongle starts or stops advertising nearby, which happens
 * when it is plugged in or unplugged.
 *
 * Each Android version calls a different callback; each override handles only its own range so
 * that default implementations forwarding to older callbacks don't act twice.
 */
class PresenceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> appeared()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> disappeared()
        }
    }

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU until 36) appeared()
    }

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU until 36) disappeared()
    }

    @Deprecated("Replaced by onDeviceAppeared(AssociationInfo) on API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) appeared()
    }

    @Deprecated("Replaced by onDeviceDisappeared(AssociationInfo) on API 33")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) disappeared()
    }

    private fun appeared() {
        Log.i("PresenceService", "dongle appeared")
        if (dongle.isPaired) dongle.connect()
    }

    private fun disappeared() = dongle.onDongleDisappeared()
}
