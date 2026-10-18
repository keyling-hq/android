package com.keyling.hub

import android.app.Application
import android.content.Context
import com.keyling.hub.dongle.DongleController

class HubApp : Application() {
    val dongle by lazy { DongleController(this) }
}

val Context.dongle: DongleController get() = (applicationContext as HubApp).dongle
