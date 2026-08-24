package com.net.lldpsniffer.data

import android.content.Context
import com.net.lldpsniffer.model.CopyFieldConfig
import com.net.lldpsniffer.model.CopyFieldId
import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.CopyFormat
import com.net.lldpsniffer.model.WebhookConfig
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "settings"

        // Legacy (pre-v2) per-field boolean keys, kept only to migrate existing installs once.
        private const val KEY_SWITCH_NAME = "copy_switch_name"
        private const val KEY_PORT_ID = "copy_port_id"
        private const val KEY_CHASSIS_ID = "copy_chassis_id"
        private const val KEY_VLAN_ID = "copy_vlan_id"
        private const val KEY_MANAGEMENT_IP = "copy_management_ip"
        private const val KEY_DUPLEX = "copy_duplex"
        private const val KEY_SYSTEM_DESCRIPTION = "copy_system_description"
        private const val KEY_PLATFORM = "copy_platform"
        private const val KEY_SOFTWARE_VERSION = "copy_software_version"
        private const val KEY_CAPABILITIES = "copy_capabilities"
        private const val KEY_PROTOCOLS = "copy_protocols"
        private const val KEY_PACKET_COUNT = "copy_packet_count"
        private const val KEY_TIMESTAMPS = "copy_timestamps"

        private const val KEY_SHOW_LOG_VIEWS = "show_log_views"
        private const val KEY_COPY_FIELDS_V2 = "copy_fields_v2_json"
        private const val KEY_COPY_FORMAT = "copy_format"
        private const val KEY_WEBHOOK_CONFIG = "webhook_config_json"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_SMART_FINALIZATION_ENABLED = "smart_finalization_enabled"
        private const val KEY_SWITCH_MEMORY_LIMIT = "switch_memory_limit"

        const val DEFAULT_HISTORY_LIMIT = 100
        const val HISTORY_LIMIT_UNLIMITED = -1
        const val DEFAULT_SWITCH_MEMORY_LIMIT = 20
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCopyFieldsConfig(): CopyFieldsConfig {
        val stored = prefs.getString(KEY_COPY_FIELDS_V2, null)
        if (stored != null) {
            return deserializeCopyFields(stored)
        }
        val migrated = migrateLegacyCopyFieldsConfig()
        saveCopyFieldsConfig(migrated)
        return migrated
    }

    private fun migrateLegacyCopyFieldsConfig(): CopyFieldsConfig {
        val defaults = CopyFieldsConfig()
        fun enabledFor(id: CopyFieldId): Boolean = when (id) {
            CopyFieldId.SWITCH_NAME -> prefs.getBoolean(KEY_SWITCH_NAME, true)
            CopyFieldId.PORT_ID -> prefs.getBoolean(KEY_PORT_ID, true)
            CopyFieldId.CHASSIS_ID -> prefs.getBoolean(KEY_CHASSIS_ID, true)
            CopyFieldId.VLAN_ID -> prefs.getBoolean(KEY_VLAN_ID, true)
            CopyFieldId.MANAGEMENT_IP -> prefs.getBoolean(KEY_MANAGEMENT_IP, true)
            CopyFieldId.DUPLEX -> prefs.getBoolean(KEY_DUPLEX, true)
            // The legacy store never actually persisted this key despite the old Settings
            // dialog showing a checkbox for it, so it always behaved as enabled.
            CopyFieldId.PORT_DESCRIPTION -> true
            CopyFieldId.SYSTEM_DESCRIPTION -> prefs.getBoolean(KEY_SYSTEM_DESCRIPTION, true)
            CopyFieldId.PLATFORM -> prefs.getBoolean(KEY_PLATFORM, true)
            CopyFieldId.SOFTWARE_VERSION -> prefs.getBoolean(KEY_SOFTWARE_VERSION, true)
            CopyFieldId.CAPABILITIES -> prefs.getBoolean(KEY_CAPABILITIES, true)
            CopyFieldId.PROTOCOLS -> prefs.getBoolean(KEY_PROTOCOLS, true)
            CopyFieldId.PACKET_COUNT -> prefs.getBoolean(KEY_PACKET_COUNT, true)
            CopyFieldId.TIMESTAMPS -> prefs.getBoolean(KEY_TIMESTAMPS, true)
        }
        return CopyFieldsConfig(defaults.fields.map { it.copy(enabled = enabledFor(it.id)) })
    }

    private fun deserializeCopyFields(json: String): CopyFieldsConfig {
        val array = JSONArray(json)
        val parsed = mutableListOf<CopyFieldConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = try {
                CopyFieldId.valueOf(obj.getString("id"))
            } catch (e: IllegalArgumentException) {
                continue
            }
            parsed.add(
                CopyFieldConfig(
                    id = id,
                    label = obj.optString("label", id.defaultLabel()),
                    enabled = obj.optBoolean("enabled", true)
                )
            )
        }
        // Backfill any field type missing from a saved blob (e.g. a future new field added
        // after this config was last saved) by appending it at the end with its default.
        val presentIds = parsed.map { it.id }.toSet()
        CopyFieldId.entries.filterNot { it in presentIds }.forEach { parsed.add(CopyFieldConfig(it)) }
        return CopyFieldsConfig(parsed)
    }

    fun saveCopyFieldsConfig(config: CopyFieldsConfig) {
        val array = JSONArray()
        config.fields.forEach { field ->
            array.put(
                JSONObject().apply {
                    put("id", field.id.name)
                    put("label", field.label)
                    put("enabled", field.enabled)
                }
            )
        }
        prefs.edit().putString(KEY_COPY_FIELDS_V2, array.toString()).apply()
    }

    fun loadCopyFormat(): CopyFormat {
        val stored = prefs.getString(KEY_COPY_FORMAT, null) ?: return CopyFormat.BASIC
        return try {
            CopyFormat.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            CopyFormat.BASIC
        }
    }

    fun saveCopyFormat(format: CopyFormat) {
        prefs.edit().putString(KEY_COPY_FORMAT, format.name).apply()
    }

    fun loadWebhookConfig(): WebhookConfig {
        val stored = prefs.getString(KEY_WEBHOOK_CONFIG, null) ?: return WebhookConfig()
        return try {
            val obj = JSONObject(stored)
            WebhookConfig(
                enabled = obj.optBoolean("enabled", false),
                url = obj.optString("url", ""),
                deviceName = obj.optString("deviceName", ""),
                authHeaderName = obj.optString("authHeaderName", ""),
                authHeaderValue = obj.optString("authHeaderValue", ""),
                useCustomTemplate = obj.optBoolean("useCustomTemplate", false),
                template = obj.optString("template", WebhookConfig.DEFAULT_DISCORD_TEMPLATE)
            )
        } catch (e: Exception) {
            WebhookConfig()
        }
    }

    fun saveWebhookConfig(config: WebhookConfig) {
        val obj = JSONObject().apply {
            put("enabled", config.enabled)
            put("url", config.url)
            put("deviceName", config.deviceName)
            put("authHeaderName", config.authHeaderName)
            put("authHeaderValue", config.authHeaderValue)
            put("useCustomTemplate", config.useCustomTemplate)
            put("template", config.template)
        }
        prefs.edit().putString(KEY_WEBHOOK_CONFIG, obj.toString()).apply()
    }

    fun loadShowLogViews(): Boolean = prefs.getBoolean(KEY_SHOW_LOG_VIEWS, false)

    fun saveShowLogViews(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LOG_VIEWS, show).apply()
    }

    /** Returns [HISTORY_LIMIT_UNLIMITED] or a positive max record count. */
    fun loadHistoryLimit(): Int {
        val stored = prefs.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        return if (stored == HISTORY_LIMIT_UNLIMITED || stored >= 1) stored else DEFAULT_HISTORY_LIMIT
    }

    fun saveHistoryLimit(limit: Int) {
        prefs.edit().putInt(KEY_HISTORY_LIMIT, limit).apply()
    }

    fun loadSmartFinalizationEnabled(): Boolean =
        prefs.getBoolean(KEY_SMART_FINALIZATION_ENABLED, false)

    fun saveSmartFinalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_FINALIZATION_ENABLED, enabled).apply()
    }

    fun loadSwitchMemoryLimit(): Int {
        val stored = prefs.getInt(KEY_SWITCH_MEMORY_LIMIT, DEFAULT_SWITCH_MEMORY_LIMIT)
        return if (stored >= 1) stored else DEFAULT_SWITCH_MEMORY_LIMIT
    }

    fun saveSwitchMemoryLimit(limit: Int) {
        prefs.edit().putInt(KEY_SWITCH_MEMORY_LIMIT, limit.coerceAtLeast(1)).apply()
    }
}
