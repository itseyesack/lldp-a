package com.net.lldpsniffer.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Fixtures taken from netutils' own doctests (networktocode/netutils, interface.py) plus the
 * real-world LLDP/CDP abbreviation mismatch that motivated this class.
 */
class PortIdTest {

    @Test
    fun netutilsDoctestParity() {
        assertEquals("GigabitEthernet", PortId("GigabitEthernet1/0/1").type)
        assertEquals(listOf(1, 0, 1), PortId("GigabitEthernet1/0/1").numbers)
        assertEquals("Ethernet", PortId("Eth1").type)
        assertEquals(listOf(1), PortId("Eth1").numbers)
    }

    @Test
    fun abbreviatedAndExpandedFormsShareIdentityKey() {
        assertEquals(PortId("TenGigabitEthernet1/0/40").key, PortId("Te1/0/40").key)
        assertEquals(PortId("GigabitEthernet2/0/12").key, PortId("Gi2/0/12").key)
        assertEquals(PortId("FastEthernet0/1").key, PortId("Fa0/1").key)
    }

    @Test
    fun differentPortNumbersDiffer() {
        assertNotEquals(PortId("TenGigabitEthernet1/0/40").key, PortId("TenGigabitEthernet1/0/41").key)
        assertNotEquals(PortId("Te1/0/40").key, PortId("Te1/0/41").key)
        assertNotEquals(PortId("Gi1/0/1").key, PortId("Gi2/0/1").key)
    }

    @Test
    fun differentInterfaceTypesDiffer() {
        assertNotEquals(PortId("Gi1/0/1").key, PortId("Te1/0/1").key)
        assertNotEquals(PortId("Fa0/1").key, PortId("Gi0/1").key)
    }

    @Test
    fun tenGigAliasesAllCanonicalize() {
        for (raw in listOf(
            "TenGigabitEthernet1/0/40", "Te1/0/40", "Ten1/0/40", "XGE1/0/40",
            "Ten-GigabitEthernet1/0/40", "T1/0/40"
        )) {
            assertEquals("raw=$raw", "TenGigabitEthernet", PortId(raw).type)
        }
    }

    @Test
    fun subinterfaceNumbersParse() {
        assertEquals(listOf(1, 0, 1, 100), PortId("Gi1/0/1.100").numbers)
        assertEquals(PortId("GigabitEthernet1/0/1.100").key, PortId("Gi1/0/1.100").key)
    }

    @Test
    fun unknownPrefixFallsBackToRawString() {
        val p = PortId("FooBarBaz3/2")
        assertEquals("FooBarBaz3/2", p.key)
        // Two unknown-but-identical strings still match each other
        assertEquals(p.key, PortId("FooBarBaz3/2").key)
        // and differ from a different unknown string
        assertNotEquals(p.key, PortId("FooBarBaz3/3").key)
    }

    @Test
    fun caseSensitivePrefixMapping() {
        assertEquals("TenGigabitEthernet", PortId("Te1/0/1").type)
        assertEquals("Port-channel", PortId("Po12").type)
    }

    @Test
    fun blankAndNumberlessInputsAreSafe() {
        assertEquals("", PortId("").key)
        assertEquals("Ethernet", PortId("Ethernet").type)
        // no numbers -> raw fallback equals trimmed raw
        assertEquals("Ethernet", PortId("Ethernet").key)
    }
}
