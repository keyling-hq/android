package com.keyling.hub.layout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UnknownLayoutException(id: String) : IllegalArgumentException("unknown layout $id")

/** The layouts shipped in assets/layouts, loaded on first use. */
class LayoutRegistry(private val context: Context) {
    val all: List<KeyboardLayout> by lazy {
        val assets = context.assets
        assets.list(DIR).orEmpty().filter { it.endsWith(".txt") }.map { file ->
            assets.open("$DIR/$file").bufferedReader().use { KeyboardLayout.parse(it.readText()) }
        }.sortedBy { it.name.lowercase() }
    }

    /** The layout with [id], ignoring case, or null. */
    operator fun get(id: String): KeyboardLayout? = all.find { it.id.equals(id, ignoreCase = true) }

    private companion object {
        const val DIR = "layouts"
    }
}

/** The hub's user settings. */
class HubSettings(private val prefs: SharedPreferences) {
    private val _layout = MutableStateFlow(prefs.getString(KEY_LAYOUT, null))

    /** Default layout id for requests that name none, or null to let the dongle translate. */
    val layout: StateFlow<String?> = _layout.asStateFlow()

    fun setLayout(id: String?) {
        prefs.edit().putString(KEY_LAYOUT, id).apply()
        _layout.value = id
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
    }
}
