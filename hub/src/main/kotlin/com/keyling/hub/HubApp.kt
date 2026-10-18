package com.keyling.hub

import android.app.Application
import android.content.Context
import com.keyling.hub.api.ApprovalNotifier
import com.keyling.hub.api.ApprovalStore
import com.keyling.hub.api.CallerResolver
import com.keyling.hub.api.PreferencesStore
import com.keyling.hub.dongle.DongleController
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
}

val Context.dongle: DongleController get() = (applicationContext as HubApp).dongle
