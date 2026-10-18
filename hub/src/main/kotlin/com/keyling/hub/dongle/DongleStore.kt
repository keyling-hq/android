package com.keyling.hub.dongle

import android.content.Context

/** The paired dongle, as remembered across restarts. */
class DongleStore(context: Context) {
    private val prefs = context.getSharedPreferences("dongle", Context.MODE_PRIVATE)

    var address: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    /** CompanionDeviceManager association id, or [NO_ASSOCIATION_ID] before API 33. */
    var associationId: Int
        get() = prefs.getInt(KEY_ASSOCIATION_ID, NO_ASSOCIATION_ID)
        set(value) = prefs.edit().putInt(KEY_ASSOCIATION_ID, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val NO_ASSOCIATION_ID = -1
        private const val KEY_ADDRESS = "address"
        private const val KEY_ASSOCIATION_ID = "association_id"
    }
}
