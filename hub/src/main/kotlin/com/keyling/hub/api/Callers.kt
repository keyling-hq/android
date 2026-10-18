package com.keyling.hub.api

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.security.MessageDigest

/** An app calling the hub, identified by package and current signing certificate. */
data class Caller(val packageName: String, val label: String, val certSha256: String)

/** Identifies calling apps through the package manager. */
class CallerResolver(context: Context) {
    private val packages = context.packageManager

    /** The app running as [uid], or null if it can't be identified unambiguously. */
    fun forUid(uid: Int): Caller? {
        val names = packages.getPackagesForUid(uid) ?: return null
        return if (names.size == 1) forPackage(names[0]) else null
    }

    fun forPackage(packageName: String): Caller? {
        val info = try {
            packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val signing = info.signingInfo ?: return null
        val cert = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners.firstOrNull()
        } else {
            signing.signingCertificateHistory.lastOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        val label = info.applicationInfo?.let { packages.getApplicationLabel(it).toString() } ?: packageName
        return Caller(packageName, label, digest.toHex())
    }

    fun hasCertificate(packageName: String, certSha256: String): Boolean = try {
        packages.hasSigningCertificate(packageName, certSha256.hexToBytes(), PackageManager.CERT_INPUT_SHA256)
    } catch (e: IllegalArgumentException) {
        false
    }
}

class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
