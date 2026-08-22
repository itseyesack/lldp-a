package com.net.lldpsniffer.data

import android.content.Context
import com.net.lldpsniffer.model.CopyFieldsConfig

class SettingsStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "settings"
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
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCopyFieldsConfig(): CopyFieldsConfig {
        val defaults = CopyFieldsConfig()
        return CopyFieldsConfig(
            switchName = prefs.getBoolean(KEY_SWITCH_NAME, defaults.switchName),
            portId = prefs.getBoolean(KEY_PORT_ID, defaults.portId),
            chassisId = prefs.getBoolean(KEY_CHASSIS_ID, defaults.chassisId),
            vlanId = prefs.getBoolean(KEY_VLAN_ID, defaults.vlanId),
            managementIp = prefs.getBoolean(KEY_MANAGEMENT_IP, defaults.managementIp),
            duplex = prefs.getBoolean(KEY_DUPLEX, defaults.duplex),
            systemDescription = prefs.getBoolean(KEY_SYSTEM_DESCRIPTION, defaults.systemDescription),
            platform = prefs.getBoolean(KEY_PLATFORM, defaults.platform),
            softwareVersion = prefs.getBoolean(KEY_SOFTWARE_VERSION, defaults.softwareVersion),
            capabilities = prefs.getBoolean(KEY_CAPABILITIES, defaults.capabilities),
            protocols = prefs.getBoolean(KEY_PROTOCOLS, defaults.protocols),
            packetCount = prefs.getBoolean(KEY_PACKET_COUNT, defaults.packetCount),
            timestamps = prefs.getBoolean(KEY_TIMESTAMPS, defaults.timestamps)
        )
    }

    fun saveCopyFieldsConfig(config: CopyFieldsConfig) {
        prefs.edit()
            .putBoolean(KEY_SWITCH_NAME, config.switchName)
            .putBoolean(KEY_PORT_ID, config.portId)
            .putBoolean(KEY_CHASSIS_ID, config.chassisId)
            .putBoolean(KEY_VLAN_ID, config.vlanId)
            .putBoolean(KEY_MANAGEMENT_IP, config.managementIp)
            .putBoolean(KEY_DUPLEX, config.duplex)
            .putBoolean(KEY_SYSTEM_DESCRIPTION, config.systemDescription)
            .putBoolean(KEY_PLATFORM, config.platform)
            .putBoolean(KEY_SOFTWARE_VERSION, config.softwareVersion)
            .putBoolean(KEY_CAPABILITIES, config.capabilities)
            .putBoolean(KEY_PROTOCOLS, config.protocols)
            .putBoolean(KEY_PACKET_COUNT, config.packetCount)
            .putBoolean(KEY_TIMESTAMPS, config.timestamps)
            .apply()
    }

    fun loadShowLogViews(): Boolean = prefs.getBoolean(KEY_SHOW_LOG_VIEWS, false)

    fun saveShowLogViews(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LOG_VIEWS, show).apply()
    }
}
