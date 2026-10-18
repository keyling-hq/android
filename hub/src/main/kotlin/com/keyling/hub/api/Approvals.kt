package com.keyling.hub.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Decision { ALLOWED, DENIED, UNKNOWN }

/** The user's decision about a client app, bound to the certificate it was signed with. */
data class Approval(val packageName: String, val label: String, val allowed: Boolean, val certSha256: String)

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun all(): Map<String, String>
}

/**
 * Which client apps may type. A decision only applies while the app is still signed with the
 * certificate it had when the user decided ([hasCertificate] checks that, rotation included);
 * an app reinstalled with another signer is unknown again.
 */
class ApprovalStore(
    private val storage: KeyValueStore,
    private val hasCertificate: (packageName: String, certSha256: String) -> Boolean,
) {
    private val _approvals = MutableStateFlow(load())

    val approvals: StateFlow<List<Approval>> = _approvals.asStateFlow()

    fun decision(packageName: String): Decision {
        val approval = _approvals.value.find { it.packageName == packageName } ?: return Decision.UNKNOWN
        if (!hasCertificate(packageName, approval.certSha256)) return Decision.UNKNOWN
        return if (approval.allowed) Decision.ALLOWED else Decision.DENIED
    }

    fun allow(caller: Caller) = set(caller, allowed = true)

    fun deny(caller: Caller) = set(caller, allowed = false)

    fun remove(packageName: String) {
        synchronized(this) {
            storage.remove(packageName)
            _approvals.value = load()
        }
    }

    private fun set(caller: Caller, allowed: Boolean) {
        synchronized(this) {
            val value = listOf(if (allowed) ALLOWED else DENIED, caller.certSha256, caller.label).joinToString(SEPARATOR)
            storage.put(caller.packageName, value)
            _approvals.value = load()
        }
    }

    private fun load(): List<Approval> = storage.all().mapNotNull { (packageName, value) ->
        val parts = value.split(SEPARATOR, limit = 3)
        if (parts.size != 3 || parts[0] !in setOf(ALLOWED, DENIED)) return@mapNotNull null
        Approval(packageName, label = parts[2], allowed = parts[0] == ALLOWED, certSha256 = parts[1])
    }.sortedBy { it.label.lowercase() }

    private companion object {
        const val SEPARATOR = "|"
        const val ALLOWED = "allowed"
        const val DENIED = "denied"
    }
}
