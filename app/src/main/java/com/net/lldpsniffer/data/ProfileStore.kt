package com.net.lldpsniffer.data

import android.content.Context
import com.net.lldpsniffer.model.SwitchProtocolProfile
import com.net.lldpsniffer.model.fromJson
import com.net.lldpsniffer.model.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent storage for switch protocol profiles. Maintains a cache of known switches
 * and their observed protocol patterns to optimize session finalization.
 */
class ProfileStore(context: Context) {

    companion object {
        private const val FILE_NAME = "switch_profiles.json"
    }

    private val file = context.filesDir.resolve(FILE_NAME)

    /**
     * Loads all stored profiles. Returns empty list if no profiles exist or on parse error.
     */
    fun load(): List<SwitchProtocolProfile> {
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            val profiles = mutableListOf<SwitchProtocolProfile>()
            for (i in 0 until array.length()) {
                try {
                    profiles.add(SwitchProtocolProfile.fromJson(array.getJSONObject(i)))
                } catch (e: Exception) {
                    // Skip malformed entries rather than failing entire load
                }
            }
            profiles
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Saves profiles to disk, enforcing the configured memory limit. Profiles are sorted by
     * lastSeen descending (most recent first), then the list is truncated to maxEntries.
     */
    fun save(profiles: List<SwitchProtocolProfile>, maxEntries: Int) {
        val limited = profiles
            .sortedByDescending { it.lastSeen }
            .take(maxEntries)

        val array = JSONArray()
        limited.forEach { profile ->
            try {
                array.put(profile.toJson())
            } catch (e: Exception) {
                // Skip profiles that fail to serialize
            }
        }

        file.writeText(array.toString(2))
    }

    /**
     * Clears all stored profiles.
     */
    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }
}
