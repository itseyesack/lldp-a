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

data class CopyFieldsConfig(
    val switchName: Boolean = true,
    val portId: Boolean = true,
    val chassisId: Boolean = true,
    val vlanId: Boolean = true,
    val managementIp: Boolean = true,
    val duplex: Boolean = true,
    val portDescription: Boolean = true,
    val systemDescription: Boolean = true,
    val platform: Boolean = true,
    val softwareVersion: Boolean = true,
    val capabilities: Boolean = true,
    val protocols: Boolean = true,
    val packetCount: Boolean = true,
    val timestamps: Boolean = true
)

fun MergedSwitchportRecord.toDisplayText(config: CopyFieldsConfig = CopyFieldsConfig()): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val lines = mutableListOf<String>()
    lines.add(displayTitle())
    if (config.switchName) lines.add("Switch: ${switchName ?: "N/A"}")
    if (config.portId) lines.add("Port: ${portId ?: "N/A"}")
    if (config.chassisId) lines.add("Chassis ID: ${chassisId ?: "N/A"}")
    if (config.vlanId) lines.add("VLAN: ${vlanId?.toString() ?: "N/A"}")
    if (config.managementIp) lines.add("Management IP: ${managementIp ?: "N/A"}")
    if (config.duplex) duplex?.let { lines.add("Duplex: $it") }
    if (config.portDescription) portDescription?.let { lines.add("Interface Description: $it") }
    if (config.systemDescription) systemDescription?.let { lines.add("System Description: $it") }
    if (config.platform) platform?.let { lines.add("Platform: $it") }
    if (config.softwareVersion) softwareVersion?.let { lines.add("Software Version: $it") }
    if (config.capabilities) capabilities?.let { lines.add("Capabilities: $it") }
    if (config.protocols) {
        lines.add("Protocols seen: ${listOfNotNull(if (hasLldp) "LLDP" else null, if (hasCdp) "CDP" else null).joinToString(", ").ifEmpty { "None" }}")
    }
    if (config.packetCount) lines.add("Packets: $packetCount")
    if (config.timestamps) {
        lines.add("Start: ${dateFormat.format(Date(startTime))}")
        endTime?.let { lines.add("End: ${dateFormat.format(Date(it))}") }
    }
    return lines.joinToString("\n")
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
