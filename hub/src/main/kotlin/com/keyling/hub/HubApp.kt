package com.keyling.hub

import android.app.Application
import android.content.Context
import com.keyling.hub.api.ApprovalNotifier
import com.keyling.hub.api.ApprovalStore
import com.keyling.hub.api.CallerResolver
import com.keyling.hub.api.PreferencesStore
import com.keyling.hub.dongle.DongleController
import com.keyling.hub.layout.HubSettings
import com.keyling.hub.layout.KeyboardLayout
import com.keyling.hub.layout.LayoutRegistry
import com.keyling.hub.layout.UnknownLayoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HubApp : Application() {
    /** Work that must outlive the component that started it, such as a client's typing request. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dongle by lazy { DongleController(this) }
    val callers by lazy { CallerResolver(this) }
    val approvals by lazy {
        ApprovalStore(PreferencesStore(getSharedPreferences("approvals", MODE_PRIVATE)), callers::hasCertificate)
    }
    val approvalNotifier by lazy { ApprovalNotifier(this) }
    val layouts by lazy { LayoutRegistry(this) }
    val settings by lazy { HubSettings(getSharedPreferences("settings", MODE_PRIVATE)) }

    /**
     * The layout for a request naming [id], or the default layout if [id] is null. Null means
     * the dongle translates. Throws [UnknownLayoutException] for an unknown [id].
     */
    fun layoutFor(id: String?): KeyboardLayout? {
        if (id != null) return layouts[id] ?: throw UnknownLayoutException(id)
        return settings.layout.value?.let { layouts[it] }
    }
}

val Context.dongle: DongleController get() = (applicationContext as HubApp).dongle
