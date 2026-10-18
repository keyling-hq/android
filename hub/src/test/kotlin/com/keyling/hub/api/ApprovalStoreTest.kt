package com.keyling.hub.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStoreTest {
    private class MemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun all() = map.toMap()
    }

    /** package -> certificates the installed package is signed with. */
    private val installed = mutableMapOf("org.example.vault" to setOf("aa11"))
    private val storage = MemoryStore()
    private fun store() = ApprovalStore(storage) { pkg, cert -> cert in installed[pkg].orEmpty() }

    private val vault = Caller("org.example.vault", "Vault | Pro", "aa11")

    @Test
    fun unknownUntilDecided() {
        assertEquals(Decision.UNKNOWN, store().decision(vault.packageName))
    }

    @Test
    fun allowAndDenyPersist() {
        store().allow(vault)
        assertEquals(Decision.ALLOWED, store().decision(vault.packageName))
        store().deny(vault)
        assertEquals(Decision.DENIED, store().decision(vault.packageName))
        assertEquals(listOf(Approval(vault.packageName, "Vault | Pro", false, "aa11")), store().approvals.value)
    }

    @Test
    fun otherSignerIsUnknown() {
        store().allow(vault)
        installed[vault.packageName] = setOf("bb22")
        assertEquals(Decision.UNKNOWN, store().decision(vault.packageName))
    }

    @Test
    fun removeForgetsDecision() {
        val store = store()
        store.allow(vault)
        store.remove(vault.packageName)
        assertEquals(Decision.UNKNOWN, store.decision(vault.packageName))
        assertTrue(store.approvals.value.isEmpty())
    }

    @Test
    fun ignoresMalformedEntries() {
        storage.put("org.example.broken", "maybe|aa11|Broken")
        storage.put("org.example.short", "allowed")
        assertTrue(store().approvals.value.isEmpty())
    }
}
