package com.net.lldpsniffer.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ProtocolType {
    LLDP,
    CDP,
    UNKNOWN
}

/** Source MAC/IP/EtherType decoded from any Ethernet frame, not just LLDP/CDP. */
data class PeerFrameInfo(
    val srcMac: String,
    val srcIp: String?,
    val protocolLabel: String
)

data class LldpTlv(
    val type: Int,
    val length: Int,
    val rawValue: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LldpTlv
        if (type != other.type) return false
        if (length != other.length) return false
        if (!rawValue.contentEquals(other.rawValue)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + length
        result = 31 * result + rawValue.contentHashCode()
        return result
    }
}

data class LldpFrame(
    val chassisIdSubtype: Int? = null,
    val chassisId: String? = null,
    val portIdSubtype: Int? = null,
    val portId: String? = null,
    val ttl: Int? = null,
    val portDescription: String? = null,
    val systemName: String? = null,
    val systemDescription: String? = null,
    val systemCapabilities: String? = null,
    val managementAddress: String? = null,
    val vlanId: Int? = null,
    val tlvs: List<LldpTlv> = emptyList()
)

data class CdpTlv(
    val type: Int,
    val length: Int,
    val rawValue: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CdpTlv
        if (type != other.type) return false
        if (length != other.length) return false
        if (!rawValue.contentEquals(other.rawValue)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + length
        result = 31 * result + rawValue.contentHashCode()
        return result
    }
}

data class CdpFrame(
    val version: Int = 0,
    val ttl: Int = 0,
    val checksum: Int = 0,
    val deviceId: String? = null,
    val addresses: List<String> = emptyList(),
    val portId: String? = null,
    val capabilities: String? = null,
    val softwareVersion: String? = null,
    val platform: String? = null,
    val duplex: String? = null,
    val nativeVlan: Int? = null,
    val tlvs: List<CdpTlv> = emptyList()
)

data class MergedSwitchportRecord(
    val id: String,
    val name: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val switchName: String? = null,
    val portId: String? = null,
    val chassisId: String? = null,
    val vlanId: Int? = null,
    val managementIp: String? = null,
    val duplex: String? = null,
    val portDescription: String? = null,
    val systemDescription: String? = null,
    val platform: String? = null,
    val softwareVersion: String? = null,
    val capabilities: String? = null,
    val ttlSeconds: Int? = null,
    val packetCount: Int = 0,
    val hasLldp: Boolean = false,
    val hasCdp: Boolean = false
) {
    companion object
}

fun MergedSwitchportRecord.mergeWithPacket(packet: CapturedPacket): MergedSwitchportRecord {
    val lldp = packet.lldpFrame
    val cdp = packet.cdpFrame
    val ttl = lldp?.ttl ?: cdp?.ttl ?: ttlSeconds
    return copy(
        switchName = lldp?.systemName ?: cdp?.deviceId ?: switchName,
        portId = lldp?.portId ?: cdp?.portId ?: portId,
        // cdp.platform is a hardware model string, not a real chassis identifier - it's only
        // a stand-in for switches that never send an LLDP frame. Once a genuine LLDP chassisId
        // has been captured, a later CDP frame (which has no chassisId of its own) must not
        // clobber it with the model string.
        chassisId = lldp?.chassisId ?: chassisId ?: cdp?.platform,
        vlanId = lldp?.vlanId ?: cdp?.nativeVlan ?: vlanId,
        managementIp = lldp?.managementAddress ?: cdp?.addresses?.firstOrNull() ?: managementIp,
        duplex = cdp?.duplex ?: duplex,
        portDescription = lldp?.portDescription ?: portDescription,
        systemDescription = lldp?.systemDescription ?: systemDescription,
        platform = cdp?.platform ?: platform,
        softwareVersion = cdp?.softwareVersion ?: softwareVersion,
        capabilities = cdp?.capabilities ?: capabilities,
        ttlSeconds = ttl,
        packetCount = packetCount + 1,
        hasLldp = hasLldp || lldp != null,
        hasCdp = hasCdp || cdp != null
    )
}

fun MergedSwitchportRecord.isComplete(): Boolean =
    switchName != null && portId != null && chassisId != null && vlanId != null && managementIp != null

fun MergedSwitchportRecord.displayTitle(): String =
    name ?: "${switchName ?: "Unnamed"} · ${portId ?: "?"}"

enum class CopyFieldId {
    SWITCH_NAME,
    PORT_ID,
    CHASSIS_ID,
    VLAN_ID,
    MANAGEMENT_IP,
    DUPLEX,
    PORT_DESCRIPTION,
    SYSTEM_DESCRIPTION,
    PLATFORM,
    SOFTWARE_VERSION,
    CAPABILITIES,
    PROTOCOLS,
    PACKET_COUNT,
    TIMESTAMPS;

    fun defaultLabel(): String = when (this) {
        SWITCH_NAME -> "Switch Hostname"
        PORT_ID -> "Port ID"
        CHASSIS_ID -> "Chassis ID / Model"
        VLAN_ID -> "VLAN ID"
        MANAGEMENT_IP -> "Management IP"
        DUPLEX -> "Duplex"
        PORT_DESCRIPTION -> "Interface Description"
        SYSTEM_DESCRIPTION -> "System Description"
        PLATFORM -> "Platform"
        SOFTWARE_VERSION -> "Software Version"
        CAPABILITIES -> "Capabilities"
        PROTOCOLS -> "Protocols Seen"
        PACKET_COUNT -> "Packet Count"
        TIMESTAMPS -> "Timestamps"
    }

    /** Placeholder key used in webhook templates, e.g. "{{vlan_id}}". */
    fun templateKey(): String = name.lowercase()
}

data class CopyFieldConfig(
    val id: CopyFieldId,
    val label: String = id.defaultLabel(),
    val enabled: Boolean = true
)

data class CopyFieldsConfig(
    val fields: List<CopyFieldConfig> = CopyFieldId.entries.map { CopyFieldConfig(it) }
) {
    fun labelFor(id: CopyFieldId): String = fields.firstOrNull { it.id == id }?.label ?: id.defaultLabel()
    fun isEnabled(id: CopyFieldId): Boolean = fields.firstOrNull { it.id == id }?.enabled ?: true
}

enum class CopyFormat {
    BASIC,
    MARKDOWN,
    JSON
}

/**
 * Raw string form of a field's value, or null when there's nothing to show. The 5 "identity"
 * fields fall back to "N/A" to match the legacy plain-text renderer's behavior exactly; other
 * optional fields return null (and callers omit the line/key) exactly as before.
 */
fun MergedSwitchportRecord.rawValueFor(id: CopyFieldId, dateFormat: SimpleDateFormat): String? = when (id) {
    CopyFieldId.SWITCH_NAME -> switchName ?: "N/A"
    CopyFieldId.PORT_ID -> portId ?: "N/A"
    CopyFieldId.CHASSIS_ID -> chassisId ?: "N/A"
    CopyFieldId.VLAN_ID -> vlanId?.toString() ?: "N/A"
    CopyFieldId.MANAGEMENT_IP -> managementIp ?: "N/A"
    CopyFieldId.DUPLEX -> duplex
    CopyFieldId.PORT_DESCRIPTION -> portDescription
    CopyFieldId.SYSTEM_DESCRIPTION -> systemDescription
    CopyFieldId.PLATFORM -> platform
    CopyFieldId.SOFTWARE_VERSION -> softwareVersion
    CopyFieldId.CAPABILITIES -> capabilities
    CopyFieldId.PROTOCOLS ->
        listOfNotNull(if (hasLldp) "LLDP" else null, if (hasCdp) "CDP" else null).joinToString(", ").ifEmpty { "None" }
    CopyFieldId.PACKET_COUNT -> packetCount.toString()
    CopyFieldId.TIMESTAMPS -> {
        val start = "Start: ${dateFormat.format(Date(startTime))}"
        val end = endTime?.let { "End: ${dateFormat.format(Date(it))}" }
        listOfNotNull(start, end).joinToString(", ")
    }
}

fun MergedSwitchportRecord.toCopyText(config: CopyFieldsConfig = CopyFieldsConfig(), format: CopyFormat = CopyFormat.BASIC): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val enabledFields = config.fields.filter { it.enabled }

    return when (format) {
        CopyFormat.BASIC -> {
            val lines = mutableListOf<String>()
            enabledFields.forEach { field ->
                rawValueFor(field.id, dateFormat)?.let { lines.add("${field.label}: $it") }
            }
            lines.joinToString("\n")
        }
        CopyFormat.MARKDOWN -> {
            val lines = mutableListOf<String>()
            lines.add("### ${displayTitle()}")
            enabledFields.forEach { field ->
                rawValueFor(field.id, dateFormat)?.let { lines.add("**${field.label}:** $it") }
            }
            lines.joinToString("\n")
        }
        CopyFormat.JSON -> {
            val obj = JSONObject()
            enabledFields.forEach { field ->
                obj.put(field.id.name, rawValueFor(field.id, dateFormat))
            }
            obj.toString(2)
        }
    }
}

fun MergedSwitchportRecord.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("startTime", startTime)
    put("endTime", endTime)
    put("switchName", switchName)
    put("portId", portId)
    put("chassisId", chassisId)
    put("vlanId", vlanId)
    put("managementIp", managementIp)
    put("duplex", duplex)
    put("portDescription", portDescription)
    put("systemDescription", systemDescription)
    put("platform", platform)
    put("softwareVersion", softwareVersion)
    put("capabilities", capabilities)
    put("ttlSeconds", ttlSeconds)
    put("packetCount", packetCount)
    put("hasLldp", hasLldp)
    put("hasCdp", hasCdp)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

fun MergedSwitchportRecord.Companion.fromJson(json: JSONObject): MergedSwitchportRecord = MergedSwitchportRecord(
    id = json.getString("id"),
    name = json.optNullableString("name"),
    startTime = json.getLong("startTime"),
    endTime = json.optNullableLong("endTime"),
    switchName = json.optNullableString("switchName"),
    portId = json.optNullableString("portId"),
    chassisId = json.optNullableString("chassisId"),
    vlanId = json.optNullableInt("vlanId"),
    managementIp = json.optNullableString("managementIp"),
    duplex = json.optNullableString("duplex"),
    portDescription = json.optNullableString("portDescription"),
    systemDescription = json.optNullableString("systemDescription"),
    platform = json.optNullableString("platform"),
    softwareVersion = json.optNullableString("softwareVersion"),
    capabilities = json.optNullableString("capabilities"),
    ttlSeconds = json.optNullableInt("ttlSeconds"),
    packetCount = json.optInt("packetCount", 0),
    hasLldp = json.optBoolean("hasLldp", false),
    hasCdp = json.optBoolean("hasCdp", false)
)

data class CapturedPacket(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val srcMac: String,
    val dstMac: String,
    val protocol: ProtocolType,
    val length: Int,
    val rawBytes: ByteArray,
    val lldpFrame: LldpFrame? = null,
    val cdpFrame: CdpFrame? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CapturedPacket
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
