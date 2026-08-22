package com.net.lldpsniffer

import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.ProtocolType
import com.net.lldpsniffer.model.mergeWithPacket
import com.net.lldpsniffer.parser.PacketParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PacketParserTest {

    @Test
    fun testParseLldpFrame() {
        val bos = ByteArrayOutputStream()

        // Dest MAC: LLDP Multicast 01:80:C2:00:00:0E
        bos.write(byteArrayOf(0x01.toByte(), 0x80.toByte(), 0xC2.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0E.toByte()))
        // Src MAC: 00:11:22:33:44:55
        bos.write(byteArrayOf(0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte()))
        // EtherType: 0x88CC
        bos.write(byteArrayOf(0x88.toByte(), 0xCC.toByte()))

        // TLV 1: Chassis ID (Subtype 4 MAC = 00:AA:BB:CC:DD:EE)
        // Header: Type 1, Length 7 -> (1 << 9) | 7 = 0x0207
        bos.write(byteArrayOf(0x02.toByte(), 0x07.toByte()))
        bos.write(4) // Subtype MAC
        bos.write(byteArrayOf(0x00.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte()))

        // TLV 2: Port ID (Subtype 5 Interface Name = "GigabitEthernet1/0/12")
        val portNameBytes = "GigabitEthernet1/0/12".toByteArray(Charsets.UTF_8)
        val portLen = 1 + portNameBytes.size
        val portHeader = (2 shl 9) or portLen
        bos.write((portHeader shr 8) and 0xFF)
        bos.write(portHeader and 0xFF)
        bos.write(5) // Subtype Interface Name
        bos.write(portNameBytes)

        // TLV 3: TTL = 120 seconds
        // Header: Type 3, Length 2 -> (3 << 9) | 2 = 0x0602
        bos.write(byteArrayOf(0x06.toByte(), 0x02.toByte()))
        bos.write(0)
        bos.write(120)

        // TLV 5: System Name = "Core-Switch-01"
        val sysNameBytes = "Core-Switch-01".toByteArray(Charsets.UTF_8)
        val sysNameHeader = (5 shl 9) or sysNameBytes.size
        bos.write((sysNameHeader shr 8) and 0xFF)
        bos.write(sysNameHeader and 0xFF)
        bos.write(sysNameBytes)

        // TLV 127: IEEE 802.1 VLAN ID = 100
        // OUI 00:80:C2, Subtype 1, VLAN ID 100 (0x0064)
        // Length = 6. Header: (127 << 9) | 6 = 0xFE06
        bos.write(byteArrayOf(0xFE.toByte(), 0x06.toByte()))
        bos.write(byteArrayOf(0x00.toByte(), 0x80.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte(), 0x64.toByte()))

        // TLV 0: End of LLDPDU
        bos.write(byteArrayOf(0x00, 0x00))

        val bytes = bos.toByteArray()
        val packet = PacketParser.parseFrame(bytes, bytes.size)

        assertNotNull(packet)
        assertEquals(ProtocolType.LLDP, packet!!.protocol)
        val lldp = packet.lldpFrame
        assertNotNull(lldp)
        assertEquals("00:AA:BB:CC:DD:EE", lldp!!.chassisId)
        assertEquals("GigabitEthernet1/0/12", lldp.portId)
        assertEquals(120, lldp.ttl)
        assertEquals("Core-Switch-01", lldp.systemName)
        assertEquals(100, lldp.vlanId)

        val record = MergedSwitchportRecord(id = "test", startTime = 0L).mergeWithPacket(packet)
        assertEquals("Core-Switch-01", record.switchName)
        assertEquals("GigabitEthernet1/0/12", record.portId)
        assertEquals(100, record.vlanId)
    }

    @Test
    fun testParseCdpFrame() {
        val bos = ByteArrayOutputStream()

        // Dest MAC: CDP Multicast 01:00:0C:CC:CC:CC
        bos.write(byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x0C.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0xCC.toByte()))
        // Src MAC: 00:22:33:44:55:66
        bos.write(byteArrayOf(0x00.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte()))
        // 802.3 Length
        bos.write(byteArrayOf(0x00.toByte(), 0x60.toByte()))

        // LLC/SNAP header (8 bytes)
        // DSAP=AA, SSAP=AA, Control=03, OUI=00:00:0C, Proto=2000
        bos.write(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x20.toByte(), 0x00.toByte()))

        // CDP Header: Version 2, TTL 180, Checksum 0x1234
        bos.write(2) // Version
        bos.write(180) // TTL
        bos.write(byteArrayOf(0x12.toByte(), 0x34.toByte()))

        // TLV 0x0001: Device ID "Cisco-3850-SW1"
        val devIdBytes = "Cisco-3850-SW1".toByteArray(Charsets.UTF_8)
        val devIdLen = 4 + devIdBytes.size
        bos.write(byteArrayOf(0x00.toByte(), 0x01.toByte()))
        bos.write((devIdLen shr 8) and 0xFF)
        bos.write(devIdLen and 0xFF)
        bos.write(devIdBytes)

        // TLV 0x0003: Port ID "TenGigabitEthernet1/0/1"
        val portBytes = "TenGigabitEthernet1/0/1".toByteArray(Charsets.UTF_8)
        val portLen = 4 + portBytes.size
        bos.write(byteArrayOf(0x00.toByte(), 0x03.toByte()))
        bos.write((portLen shr 8) and 0xFF)
        bos.write(portLen and 0xFF)
        bos.write(portBytes)

        // TLV 0x000E: Native VLAN = 20
        bos.write(byteArrayOf(0x00.toByte(), 0x0E.toByte()))
        bos.write(byteArrayOf(0x00.toByte(), 0x06.toByte())) // Length 6
        bos.write(byteArrayOf(0x00.toByte(), 0x14.toByte())) // VLAN 20

        // TLV 0x000B: Duplex = Full (1)
        bos.write(byteArrayOf(0x00.toByte(), 0x0B.toByte()))
        bos.write(byteArrayOf(0x00.toByte(), 0x05.toByte())) // Length 5
        bos.write(1) // Full duplex

        val bytes = bos.toByteArray()
        val packet = PacketParser.parseFrame(bytes, bytes.size)

        assertNotNull(packet)
        assertEquals(ProtocolType.CDP, packet!!.protocol)
        val cdp = packet.cdpFrame
        assertNotNull(cdp)
        assertEquals("Cisco-3850-SW1", cdp!!.deviceId)
        assertEquals("TenGigabitEthernet1/0/1", cdp.portId)
        assertEquals(20, cdp.nativeVlan)
        assertEquals("Full", cdp.duplex)

        val record = MergedSwitchportRecord(id = "test", startTime = 0L).mergeWithPacket(packet)
        assertEquals("Cisco-3850-SW1", record.switchName)
        assertEquals("TenGigabitEthernet1/0/1", record.portId)
        assertEquals(20, record.vlanId)
        assertEquals("Full", record.duplex)
    }

    @Test
    fun testParse8021QTaggedFrame() {
        val bos = ByteArrayOutputStream()

        // Dest MAC: LLDP Multicast
        bos.write(byteArrayOf(0x01.toByte(), 0x80.toByte(), 0xC2.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0E.toByte()))
        // Src MAC
        bos.write(byteArrayOf(0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte()))
        
        // 802.1Q Tag: EtherType 0x8100, TCI = VLAN 42
        bos.write(byteArrayOf(0x81.toByte(), 0x00.toByte()))
        bos.write(byteArrayOf(0x00.toByte(), 0x2A.toByte())) // VLAN 42

        // EtherType: 0x88CC (LLDP)
        bos.write(byteArrayOf(0x88.toByte(), 0xCC.toByte()))

        // TLV 5: System Name = "VLAN-Test-Switch"
        val nameBytes = "VLAN-Test-Switch".toByteArray(Charsets.UTF_8)
        val nameHeader = (5 shl 9) or nameBytes.size
        bos.write((nameHeader shr 8) and 0xFF)
        bos.write(nameHeader and 0xFF)
        bos.write(nameBytes)

        // TLV 0: End
        bos.write(byteArrayOf(0x00, 0x00))

        val bytes = bos.toByteArray()
        val packet = PacketParser.parseFrame(bytes, bytes.size)

        assertNotNull(packet)
        assertEquals(ProtocolType.LLDP, packet!!.protocol)
        assertEquals(42, packet.lldpFrame?.vlanId)
    }

    @Test
    fun testInvalidOrShortFramesHandledGracefully() {
        assertNull(PacketParser.parseFrame(byteArrayOf(1, 2, 3), 3))
        assertNull(PacketParser.parseFrame(ByteArray(14), 14)) // Non-LLDP/CDP
    }
}
