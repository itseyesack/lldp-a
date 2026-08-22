package com.net.lldpsniffer.vendor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the vendor of a MAC address via maclookup.app (api.maclookup.app/v2), with:
 * - No-network classification of broadcast/multicast/locally-administered addresses, which
 *   are never in the vendor database and would otherwise waste a lookup on every peer.
 * - A per-OUI cache (in-memory + persisted to SharedPreferences) so the same vendor is never
 *   looked up twice, on this run or a future one.
 * - A minimum spacing between outgoing requests, well under the API's published 10 req/sec
 *   limit (see https://maclookup.app/api-v2/rate-limits), plus honoring 429 responses by
 *   backing off instead of retrying immediately.
 */
object MacVendorLookup {

    private const val PREFS_NAME = "mac_vendor_cache"
    private const val KEY_CACHE = "oui_vendor_json"
    private const val MIN_REQUEST_INTERVAL_MS = 300L
    private const val FAILURE_RETRY_COOLDOWN_MS = 60_000L

    // Well-known fixed destination MACs used by L2 control protocols - never vendor-assigned
    // host addresses, so looking them up would just burn API quota for nothing useful.
    private val KNOWN_MULTICAST_EXACT = mapOf(
        "0180C2000000" to "Spanning Tree (STP)",
        "0180C2000003" to "IEEE 802.1X / non-TPMR bridge",
        "0180C200000E" to "LLDP (nearest bridge)",
        "01000CCCCCCC" to "Cisco CDP/VTP/PAgP/UDLD",
        "01000CCCCCCD" to "Cisco PVST+"
    )

    private lateinit var appContext: Context
    private var initialized = false
    private val memoryCache = ConcurrentHashMap<String, String>()
    private val recentFailures = ConcurrentHashMap<String, Long>()
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        loadPersistentCache()
    }

    private fun loadPersistentCache() {
        val stored = prefs().getString(KEY_CACHE, null) ?: return
        try {
            val obj = JSONObject(stored)
            obj.keys().forEach { oui -> memoryCache[oui] = obj.getString(oui) }
        } catch (e: Exception) {
            // Corrupt/older cache format - safe to drop and rebuild.
        }
    }

    private fun persistCache() {
        if (!initialized) return
        val obj = JSONObject()
        memoryCache.forEach { (oui, vendor) -> obj.put(oui, vendor) }
        prefs().edit().putString(KEY_CACHE, obj.toString()).apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizedHex(mac: String): String =
        mac.filterNot { it == ':' || it == '-' || it == '.' }.uppercase(Locale.US)

    private fun ouiOf(mac: String): String? {
        val hex = normalizedHex(mac)
        return if (hex.length >= 6) hex.substring(0, 6) else null
    }

    /**
     * Classifies well-known non-vendor addresses (broadcast, multicast control protocols,
     * locally-administered/randomized MACs) without any network call. Returns null if [mac]
     * looks like an ordinary vendor-assigned unicast address that's worth looking up.
     */
    fun specialLabel(mac: String): String? {
        val hex = normalizedHex(mac)
        if (hex.length != 12) return null
        if (hex == "FFFFFFFFFFFF") return "Broadcast"
        if (hex == "000000000000") return "Invalid (all-zero)"
        KNOWN_MULTICAST_EXACT[hex]?.let { return it }
        if (hex.startsWith("01005E")) return "IPv4 multicast"
        if (hex.startsWith("3333")) return "IPv6 multicast"

        val firstByte = hex.substring(0, 2).toInt(16)
        if (firstByte and 0x01 != 0) return "Multicast"
        if (firstByte and 0x02 != 0) return "Randomized / locally administered"
        return null
    }

    /** Returns an already-known label (special-case or cached vendor) without a network call. */
    fun cachedLabel(mac: String): String? {
        specialLabel(mac)?.let { return it }
        val oui = ouiOf(mac) ?: return null
        return memoryCache[oui]
    }

    /** Resolves a display label for [mac], hitting the network only if not already known. */
    suspend fun lookup(mac: String): String {
        specialLabel(mac)?.let { return it }
        val oui = ouiOf(mac) ?: return "Unknown vendor"
        memoryCache[oui]?.let { return it }

        val lastFailure = recentFailures[oui]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_RETRY_COOLDOWN_MS) {
            return "Vendor lookup unavailable"
        }

        return withContext(Dispatchers.IO) {
            requestMutex.withLock {
                memoryCache[oui]?.let { return@withLock it }
                val wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = System.currentTimeMillis()
                fetchVendor(oui)
            }
        }
    }

    private fun fetchVendor(oui: String): String {
        return try {
            val connection = (URL("https://api.maclookup.app/v2/macs/$oui").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code == 429) {
                recentFailures[oui] = System.currentTimeMillis()
                return "Vendor lookup unavailable"
            }
            if (code !in 200..299) {
                recentFailures[oui] = System.currentTimeMillis()
                return "Vendor lookup unavailable"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val label = when {
                !json.optBoolean("found", false) -> "Unknown vendor"
                json.optBoolean("isPrivate", false) -> "Vendor (private listing)"
                else -> json.optString("company").ifBlank { "Unknown vendor" }
            }
            memoryCache[oui] = label
            persistCache()
            label
        } catch (e: Exception) {
            recentFailures[oui] = System.currentTimeMillis()
            "Vendor lookup unavailable"
        }
    }
}
