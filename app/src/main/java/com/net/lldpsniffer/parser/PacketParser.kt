package com.net.lldpsniffer.parser

import com.net.lldpsniffer.model.*
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object PacketParser {

    private val LLDP_MAC = byteArrayOf(0x01.toByte(), 0x80.toByte(), 0xC2.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0E.toByte())
    private val CDP_MAC = byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x0C.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0xCC.toByte())

    fun parseFrame(rawBytes: ByteArray, length: Int): CapturedPacket? {
        if (length < 14) return null

        val (packetBytes, frameLen) = extractEthernetPayload(rawBytes, length)
        if (frameLen < 14) return null

        val dstMac = packetBytes.sliceArray(0 until 6)
        val srcMac = packetBytes.sliceArray(6 until 12)

        val dstMacStr = formatMacAddress(dstMac)
        val srcMacStr = formatMacAddress(srcMac)

        var payloadOffset = 12
        var vlanFrom8021Q: Int? = null

        // Check for 802.1Q VLAN Tagging (EtherType 0x8100 or 0x88A8)
        var etherType = getUnsignedShort(packetBytes, payloadOffset)
        if (etherType == 0x8100 || etherType == 0x88A8) {
            if (frameLen < 18) return null
            val tci = getUnsignedShort(packetBytes, payloadOffset + 2)
            vlanFrom8021Q = tci and 0x0FFF
            payloadOffset += 4
            etherType = getUnsignedShort(packetBytes, payloadOffset)
        }

        val isLldpMac = isMacEqual(dstMac, LLDP_MAC)
        val isCdpMac = isMacEqual(dstMac, CDP_MAC)

        if (isLldpMac || etherType == 0x88CC) {
            // LLDP Payload starts right after EtherType (2 bytes)
            val lldpStart = payloadOffset + 2
            val lldpFrame = parseLldpPayload(packetBytes, lldpStart, frameLen, vlanFrom8021Q)
            return CapturedPacket(
                srcMac = srcMacStr,
                dstMac = dstMacStr,
                protocol = ProtocolType.LLDP,
                length = frameLen,
                rawBytes = packetBytes.copyOf(frameLen),
                lldpFrame = lldpFrame
            )
        } else if (isCdpMac || isCdpLlcSnap(packetBytes, payloadOffset)) {
            val cdpStart = locateCdpHeader(packetBytes, payloadOffset)
            val cdpFrame = parseCdpPayload(packetBytes, cdpStart, frameLen, vlanFrom8021Q)
            return CapturedPacket(
                srcMac = srcMacStr,
                dstMac = dstMacStr,
                protocol = ProtocolType.CDP,
                length = frameLen,
                rawBytes = packetBytes.copyOf(frameLen),
                cdpFrame = cdpFrame
            )
        }

        return null
    }

    /**
     * A single bulk IN read can contain several aggregated Ethernet frames back to back
     * (the RTL8153 vendor RX path packs multiple frames per URB). We don't have a verified
     * byte-for-byte layout of the vendor RX descriptor that separates them, so rather than
     * assume a specific descriptor size (which previously caused every frame after the first
     * in a read to be silently dropped), this scans the whole buffer for every LLDP/CDP
     * multicast destination MAC and treats each match as the start of one frame, ending at
     * the next match or the end of the buffer. Any inter-frame descriptor/padding bytes end
     * up as harmless trailing bytes past the parsed TLVs.
     */
    fun parseAggregateFrames(rawBytes: ByteArray, length: Int): List<CapturedPacket> {
        if (length < 14) return emptyList()

        val starts = mutableListOf<Int>()
        var offset = 0
        while (offset <= length - 6) {
            if (matchesMacAt(rawBytes, offset, LLDP_MAC) || matchesMacAt(rawBytes, offset, CDP_MAC)) {
                starts.add(offset)
                offset += 6
            } else {
                offset++
            }
        }

        if (starts.isEmpty()) {
            // No literal dest-MAC match at any offset - fall back to the single-frame path,
            // which also handles the case where a vendor descriptor prefix pushes the frame
            // start beyond a simple linear scan (e.g. length-guided unwrap).
            return listOfNotNull(parseFrame(rawBytes, length))
        }

        val packets = mutableListOf<CapturedPacket>()
        for (i in starts.indices) {
            val frameStart = starts[i]
            val frameEnd = if (i + 1 < starts.size) starts[i + 1] else length
            if (frameEnd - frameStart < 14) continue
            val frameBytes = rawBytes.sliceArray(frameStart until frameEnd)
            parseFrame(frameBytes, frameBytes.size)?.let { packets.add(it) }
        }
        return packets
    }

    private fun matchesMacAt(bytes: ByteArray, offset: Int, mac: ByteArray): Boolean {
        for (i in 0 until 6) {
            if (bytes[offset + i] != mac[i]) return false
        }
        return true
    }

    /**
     * In RTL8153 vendor mode, bulk frames often include an 8-byte RX descriptor header:
     * Bytes 0-1: packet length (little endian, bits 0-13 = length)
     * If the buffer begins with an RX descriptor, unwrap it to get standard Ethernet frame.
     */
    fun extractEthernetPayload(rawBytes: ByteArray, length: Int): Pair<ByteArray, Int> {
        // First check standard Ethernet frame (first byte is 01 for multicast MAC)
        if (isMacEqual(rawBytes, LLDP_MAC) || isMacEqual(rawBytes, CDP_MAC)) {
            return Pair(rawBytes, length)
        }

        // Check if there is an 8-byte Realtek vendor RX descriptor prefix
        if (length > 22) {
            val rxPktLen = (rawBytes[0].toInt() and 0xFF) or ((rawBytes[1].toInt() and 0x3F) shl 8)
            if (rxPktLen in 14..length - 8) {
                val candidateMac = rawBytes.sliceArray(8 until 14)
                if (isMacEqual(candidateMac, LLDP_MAC) || isMacEqual(candidateMac, CDP_MAC)) {
                    return Pair(rawBytes.sliceArray(8 until length), length - 8)
                }
            }
        }

        // Check anywhere in first 32 bytes for LLDP/CDP multicast MAC offset
        for (offset in 0 until Math.min(32, length - 14)) {
            val slice = rawBytes.sliceArray(offset until offset + 6)
            if (isMacEqual(slice, LLDP_MAC) || isMacEqual(slice, CDP_MAC)) {
                return Pair(rawBytes.sliceArray(offset until length), length - offset)
            }
        }

        return Pair(rawBytes, length)
    }

    // EtherTypes we treat as evidence of a genuine frame boundary when validating a
    // candidate offset for parseGenericFrame. Values <= 1500 are handled separately as
    // 802.3 length fields (also a valid frame).
    private val PLAUSIBLE_ETHERTYPES = setOf(0x0800, 0x0806, 0x86DD, 0x8100, 0x88A8, 0x88CC)

    /**
     * Best-effort extraction of source MAC/IP from ANY Ethernet frame (not just LLDP/CDP),
     * for passively identifying a directly-connected peer that doesn't speak LLDP/CDP.
     * We don't have a verified per-chipset RX descriptor layout the way parseFrame does via
     * its LLDP/CDP destination-MAC scan, so this instead validates candidate frame-start
     * offsets by checking for a plausible EtherType/802.3-length field 12 bytes in - offset 0
     * (no descriptor prefix; true for AX88772/AX88179 vendor mode) and offset 8 (the known
     * RTL8153 8-byte RX descriptor prefix) are tried in that order.
     */
    fun parseGenericFrame(rawBytes: ByteArray, length: Int): PeerFrameInfo? {
        if (length < 14) return null

        for (offset in intArrayOf(0, 8)) {
            if (offset + 14 > length) continue
            val etherType = getUnsignedShort(rawBytes, offset + 12)
            if (etherType !in PLAUSIBLE_ETHERTYPES && etherType > 1500) continue

            val srcMac = rawBytes.sliceArray(offset + 6 until offset + 12)
            if (srcMac.all { it == 0.toByte() }) continue

            var payloadOffset = offset + 12
            var type = etherType
            if (type == 0x8100 || type == 0x88A8) {
                if (offset + 18 > length) continue
                payloadOffset += 4
                type = getUnsignedShort(rawBytes, payloadOffset)
            }

            val srcIp = when (type) {
                0x0806 -> extractArpSenderIp(rawBytes, payloadOffset + 2, length)
                0x0800 -> extractIpv4SrcIp(rawBytes, payloadOffset + 2, length)
                else -> null
            }
            val label = when (type) {
                0x0800 -> "IPv4"
                0x0806 -> "ARP"
                0x86DD -> "IPv6"
                0x88CC -> "LLDP"
                else -> if (type <= 1500) "802.3" else "0x${String.format(Locale.US, "%04X", type)}"
            }

            return PeerFrameInfo(formatMacAddress(srcMac), srcIp, label)
        }
        return null
    }

    /** ARP sender IP sits 14 bytes into the ARP payload (hw/proto type/len, opcode, sender MAC). */
    private fun extractArpSenderIp(rawBytes: ByteArray, arpStart: Int, length: Int): String? {
        if (arpStart + 18 > length) return null
        return formatIpAddress(rawBytes.sliceArray(arpStart + 14 until arpStart + 18))
    }

    /** IPv4 source address sits 12 bytes into the IP header. */
    private fun extractIpv4SrcIp(rawBytes: ByteArray, ipStart: Int, length: Int): String? {
        if (ipStart + 16 > length) return null
        return formatIpAddress(rawBytes.sliceArray(ipStart + 12 until ipStart + 16))
    }

    private fun isCdpLlcSnap(rawBytes: ByteArray, offset: Int): Boolean {
        // LLC/SNAP header check: DSAP=0xAA, SSAP=0xAA, Control=0x03, OUI=0x00000C, Protocol=0x2000
        if (rawBytes.size < offset + 8) return false
        val dsap = rawBytes[offset].toInt() and 0xFF
        val ssap = rawBytes[offset + 1].toInt() and 0xFF
        val control = rawBytes[offset + 2].toInt() and 0xFF
        val oui0 = rawBytes[offset + 3].toInt() and 0xFF
        val oui1 = rawBytes[offset + 4].toInt() and 0xFF
        val oui2 = rawBytes[offset + 5].toInt() and 0xFF
        val proto = getUnsignedShort(rawBytes, offset + 6)

        return dsap == 0xAA && ssap == 0xAA && control == 0x03 &&
                oui0 == 0x00 && oui1 == 0x00 && oui2 == 0x0C && proto == 0x2000
    }

    private fun locateCdpHeader(rawBytes: ByteArray, offset: Int): Int {
        // EtherType/Length field
        val lengthOrType = getUnsignedShort(rawBytes, offset)
        if (lengthOrType <= 1500) {
            // 802.3 length + LLC/SNAP header (8 bytes: 3 LLC + 3 OUI + 2 Proto ID)
            return offset + 2 + 8
        }
        return offset + 2
    }

    private fun parseLldpPayload(rawBytes: ByteArray, startOffset: Int, totalLength: Int, outerVlan: Int?): LldpFrame {
        var offset = startOffset
        val tlvs = mutableListOf<LldpTlv>()

        var chassisSubtype: Int? = null
        var chassisId: String? = null
        var portSubtype: Int? = null
        var portId: String? = null
        var ttl: Int? = null
        var portDesc: String? = null
        var sysName: String? = null
        var sysDesc: String? = null
        var sysCaps: String? = null
        var mgmtAddr: String? = null
        var extractedVlan: Int? = outerVlan

        while (offset + 2 <= totalLength) {
            val header = getUnsignedShort(rawBytes, offset)
            val type = (header shr 9) and 0x7F
            val length = header and 0x01FF

            offset += 2
            if (offset + length > totalLength) break

            val valueBytes = rawBytes.sliceArray(offset until offset + length)
            tlvs.add(LldpTlv(type, length, valueBytes))

            when (type) {
                0 -> {
                    // End of LLDPDU
                    break
                }
                1 -> { // Chassis ID
                    if (length >= 2) {
                        chassisSubtype = valueBytes[0].toInt() and 0xFF
                        val valSlice = valueBytes.sliceArray(1 until length)
                        chassisId = if (chassisSubtype == 4) {
                            formatMacAddress(valSlice)
                        } else if (chassisSubtype == 5) {
                            formatIpAddress(valSlice)
                        } else {
                            String(valSlice, Charsets.UTF_8).trim()
                        }
                    }
                }
                2 -> { // Port ID
                    if (length >= 2) {
                        portSubtype = valueBytes[0].toInt() and 0xFF
                        val valSlice = valueBytes.sliceArray(1 until length)
                        portId = if (portSubtype == 3) {
                            formatMacAddress(valSlice)
                        } else {
                            String(valSlice, Charsets.UTF_8).trim()
                        }
                    }
                }
                3 -> { // TTL
                    if (length >= 2) {
                        ttl = getUnsignedShort(valueBytes, 0)
                    }
                }
                4 -> { // Port Description
                    portDesc = String(valueBytes, Charsets.UTF_8).trim()
                }
                5 -> { // System Name
                    sysName = String(valueBytes, Charsets.UTF_8).trim()
                }
                6 -> { // System Description
                    sysDesc = String(valueBytes, Charsets.UTF_8).trim()
                }
                7 -> { // System Capabilities
                    if (length >= 4) {
                        val enabledCapBits = getUnsignedShort(valueBytes, 2)
                        sysCaps = decodeLldpCapabilities(enabledCapBits)
                    }
                }
                8 -> { // Management Address
                    if (length >= 2) {
                        // Per IEEE 802.1AB, this length byte counts the address-subtype byte
                        // PLUS the address itself, not just the address - using it directly as
                        // the address byte count grabs one extra trailing byte (the interface
                        // numbering subtype that follows) and produces garbled output (e.g. a
                        // 4-byte IPv4 address rendered as 5 bytes of hex instead of a dotted quad).
                        val addrFieldLen = valueBytes[0].toInt() and 0xFF
                        val addrSubtype = valueBytes[1].toInt() and 0xFF
                        val addrLen = addrFieldLen - 1
                        if (addrLen > 0 && length >= 2 + addrLen) {
                            val addrBytes = valueBytes.sliceArray(2 until 2 + addrLen)
                            mgmtAddr = if (addrSubtype == 1 && addrLen == 4) {
                                formatIpAddress(addrBytes)
                            } else if (addrSubtype == 2 && addrLen == 16) {
                                InetAddress.getByAddress(addrBytes).hostAddress
                            } else {
                                formatHex(addrBytes)
                            }
                        }
                    }
                }
                127 -> { // Org Specific TLV
                    if (length >= 4) {
                        val oui0 = valueBytes[0].toInt() and 0xFF
                        val oui1 = valueBytes[1].toInt() and 0xFF
                        val oui2 = valueBytes[2].toInt() and 0xFF
                        val subtype = valueBytes[3].toInt() and 0xFF

                        // IEEE 802.1 OUI (00:80:C2) Subtype 1 = Port VLAN ID
                        if (oui0 == 0x00 && oui1 == 0x80 && oui2 == 0xC2 && subtype == 1) {
                            if (length >= 6) {
                                extractedVlan = getUnsignedShort(valueBytes, 4)
                            }
                        }
                        // TIA TR-41 LLDP-MED OUI (00:12:BB) Subtype 3 = Network Policy (VLAN)
                        if (oui0 == 0x00 && oui1 == 0x12 && oui2 == 0xBB && subtype == 3) {
                            if (length >= 8) {
                                val policyData = getInt(valueBytes, 4)
                                // Bit 9..20 = VLAN ID (12 bits)
                                extractedVlan = (policyData shr 9) and 0x0FFF
                            }
                        }
                    }
                }
            }

            offset += length
        }

        return LldpFrame(
            chassisIdSubtype = chassisSubtype,
            chassisId = chassisId,
            portIdSubtype = portSubtype,
            portId = portId,
            ttl = ttl,
            portDescription = portDesc,
            systemName = sysName,
            systemDescription = sysDesc,
            systemCapabilities = sysCaps,
            managementAddress = mgmtAddr,
            vlanId = extractedVlan,
            tlvs = tlvs
        )
    }

    private fun parseCdpPayload(rawBytes: ByteArray, startOffset: Int, totalLength: Int, outerVlan: Int?): CdpFrame {
        if (startOffset + 4 > totalLength) {
            return CdpFrame()
        }

        val version = rawBytes[startOffset].toInt() and 0xFF
        val ttl = rawBytes[startOffset + 1].toInt() and 0xFF
        val checksum = getUnsignedShort(rawBytes, startOffset + 2)

        var offset = startOffset + 4
        val tlvs = mutableListOf<CdpTlv>()

        var deviceId: String? = null
        val addresses = mutableListOf<String>()
        var portId: String? = null
        var capabilities: String? = null
        var swVersion: String? = null
        var platform: String? = null
        var duplexStr: String? = null
        var nativeVlan: Int? = outerVlan

        while (offset + 4 <= totalLength) {
            val type = getUnsignedShort(rawBytes, offset)
            val length = getUnsignedShort(rawBytes, offset + 2)

            if (length < 4 || offset + length > totalLength) break

            val valueLen = length - 4
            val valueBytes = rawBytes.sliceArray(offset + 4 until offset + length)
            tlvs.add(CdpTlv(type, length, valueBytes))

            when (type) {
                0x0001 -> { // Device ID
                    deviceId = String(valueBytes, Charsets.UTF_8).trim()
                }
                0x0002 -> { // Addresses
                    if (valueLen >= 4) {
                        val numAddrs = getInt(valueBytes, 0)
                        var addrOffset = 4
                        for (i in 0 until numAddrs) {
                            if (addrOffset + 2 > valueLen) break
                            val protoType = valueBytes[addrOffset].toInt() and 0xFF
                            val protoLen = valueBytes[addrOffset + 1].toInt() and 0xFF
                            addrOffset += 2 + protoLen
                            if (addrOffset + 2 > valueLen) break
                            val addrLen = getUnsignedShort(valueBytes, addrOffset)
                            addrOffset += 2
                            if (addrOffset + addrLen > valueLen) break

                            if (protoType == 1 && addrLen == 4) {
                                val ipBytes = valueBytes.sliceArray(addrOffset until addrOffset + 4)
                                addresses.add(formatIpAddress(ipBytes))
                            }
                            addrOffset += addrLen
                        }
                    }
                }
                0x0003 -> { // Port ID
                    portId = String(valueBytes, Charsets.UTF_8).trim()
                }
                0x0004 -> { // Capabilities
                    if (valueLen >= 4) {
                        val caps = getInt(valueBytes, 0)
                        capabilities = decodeCdpCapabilities(caps)
                    }
                }
                0x0005 -> { // Software Version
                    swVersion = String(valueBytes, Charsets.UTF_8).trim()
                }
                0x0006 -> { // Platform
                    platform = String(valueBytes, Charsets.UTF_8).trim()
                }
                0x000B -> { // Duplex
                    if (valueLen >= 1) {
                        val dupByte = valueBytes[0].toInt() and 0xFF
                        duplexStr = if (dupByte == 1) "Full" else "Half"
                    }
                }
                0x000A -> { // Native VLAN ID
                    if (valueLen >= 2) {
                        nativeVlan = getUnsignedShort(valueBytes, 0)
                    }
                }
            }

            offset += length
        }

        return CdpFrame(
            version = version,
            ttl = ttl,
            checksum = checksum,
            deviceId = deviceId,
            addresses = addresses,
            portId = portId,
            capabilities = capabilities,
            softwareVersion = swVersion,
            platform = platform,
            duplex = duplexStr,
            nativeVlan = nativeVlan,
            tlvs = tlvs
        )
    }

    private fun isMacEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < 6 || b.size < 6) return false
        for (i in 0 until 6) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun formatMacAddress(mac: ByteArray): String {
        return mac.take(6).joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private fun formatIpAddress(ip: ByteArray): String {
        return ip.take(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun formatHex(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format(Locale.US, "%02X", it) }
    }

    private fun getUnsignedShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun getInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun decodeLldpCapabilities(enabledCap: Int): String {
        val caps = mutableListOf<String>()
        if (enabledCap and 0x0001 != 0) caps.add("Other")
        if (enabledCap and 0x0002 != 0) caps.add("Repeater")
        if (enabledCap and 0x0004 != 0) caps.add("Bridge/Switch")
        if (enabledCap and 0x0008 != 0) caps.add("WLAN AP")
        if (enabledCap and 0x0010 != 0) caps.add("Router")
        if (enabledCap and 0x0020 != 0) caps.add("Telephone")
        if (enabledCap and 0x0040 != 0) caps.add("DOCSIS")
        if (enabledCap and 0x0080 != 0) caps.add("Station")
        return if (caps.isEmpty()) "0x${Integer.toHexString(enabledCap)}" else caps.joinToString(", ")
    }

    private fun decodeCdpCapabilities(caps: Int): String {
        val list = mutableListOf<String>()
        if (caps and 0x0001 != 0) list.add("Router")
        if (caps and 0x0002 != 0) list.add("Transparent Bridge")
        if (caps and 0x0004 != 0) list.add("Source Route Bridge")
        if (caps and 0x0008 != 0) list.add("Switch")
        if (caps and 0x0010 != 0) list.add("Host")
        if (caps and 0x0020 != 0) list.add("IGMP Filter")
        if (caps and 0x0040 != 0) list.add("Repeater")
        if (caps and 0x0400 != 0) list.add("IP Phone")
        return if (list.isEmpty()) "0x${Integer.toHexString(caps)}" else list.joinToString(", ")
    }
}
