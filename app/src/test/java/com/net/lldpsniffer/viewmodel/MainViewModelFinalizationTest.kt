package com.net.lldpsniffer.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.model.CdpFrame
import com.net.lldpsniffer.model.LldpFrame
import com.net.lldpsniffer.model.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelFinalizationTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        viewModel = MainViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createLldpPacket(
        switchName: String = "Switch1",
        portId: String = "Gi1/0/1",
        chassisId: String = "00:11:22:33:44:55",
        vlanId: Int = 100,
        managementIp: String = "10.0.0.1"
    ): CapturedPacket {
        return CapturedPacket(
            id = "lldp-${System.nanoTime()}",
            timestamp = System.currentTimeMillis(),
            protocol = ProtocolType.LLDP,
            srcMac = "00:11:22:33:44:55",
            dstMac = "01:80:C2:00:00:0E",
            length = 200,
            rawBytes = ByteArray(200),
            lldpFrame = LldpFrame(
                systemName = switchName,
                portId = portId,
                chassisId = chassisId,
                vlanId = vlanId,
                managementAddress = managementIp,
                ttl = 120
            ),
            cdpFrame = null
        )
    }

    private fun createCdpPacket(
        deviceId: String = "Switch1",
        portId: String = "Gi1/0/1",
        platform: String = "WS-C3850",
        vlanId: Int = 100,
        managementIp: String = "10.0.0.1"
    ): CapturedPacket {
        return CapturedPacket(
            id = "cdp-${System.nanoTime()}",
            timestamp = System.currentTimeMillis(),
            protocol = ProtocolType.CDP,
            srcMac = "00:11:22:33:44:66",
            dstMac = "01:00:0C:CC:CC:CC",
            length = 250,
            rawBytes = ByteArray(250),
            lldpFrame = null,
            cdpFrame = CdpFrame(
                deviceId = deviceId,
                portId = portId,
                platform = platform,
                nativeVlan = vlanId,
                addresses = listOf(managementIp),
                ttl = 180
            )
        )
    }

    @Test
    fun testImmediateFinalizationWhenBothProtocolsReceived() = runTest {
        // RED: This test should fail because current code finalizes after first complete packet

        // Simulate link up
        viewModel.onLinkStateChanged(true)

        // Send LLDP packet - record becomes complete
        val lldpPacket = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket)

        // Record should be complete but NOT finalized yet (waiting for CDP)
        val currentRecord = viewModel.currentRecord.value
        assertNotNull(currentRecord)
        assertTrue(currentRecord!!.hasLldp)
        assertFalse(currentRecord.hasCdp)

        // History should still be empty
        assertEquals(0, viewModel.history.value.size)
        assertFalse(viewModel.currentRecordFinalized.value)

        // Send CDP packet - now we have both protocols
        val cdpPacket = createCdpPacket()
        viewModel.onPacketForRecord(cdpPacket)

        // Should finalize immediately (no delay)
        val updatedRecord = viewModel.currentRecord.value
        assertNotNull(updatedRecord)
        assertTrue(updatedRecord!!.hasLldp)
        assertTrue(updatedRecord.hasCdp)

        // Should be finalized immediately
        assertEquals(1, viewModel.history.value.size)
        assertTrue(viewModel.currentRecordFinalized.value)
    }

    @Test
    fun testDelayedFinalizationWithOnlyLldp() = runTest {
        // RED: This test should fail because current code has no timeout mechanism

        // Simulate link up
        viewModel.onLinkStateChanged(true)

        // Send LLDP packet - record becomes complete with only LLDP
        val lldpPacket = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket)

        // Record should be complete but NOT finalized yet
        val currentRecord = viewModel.currentRecord.value
        assertNotNull(currentRecord)
        assertTrue(currentRecord!!.hasLldp)
        assertFalse(currentRecord.hasCdp)
        assertFalse(viewModel.currentRecordFinalized.value)
        assertEquals(0, viewModel.history.value.size)

        // Advance time by 39 seconds - should NOT finalize yet
        advanceTimeBy(39_000)
        assertFalse(viewModel.currentRecordFinalized.value)
        assertEquals(0, viewModel.history.value.size)

        // Advance time by 2 more seconds (total 41s) - should finalize now
        advanceTimeBy(2_000)
        runCurrent()

        // Should be finalized after timeout
        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.history.value.size)
    }

    @Test
    fun testDelayedFinalizationWithOnlyCdp() = runTest {
        // RED: This test should fail because current code has no timeout mechanism

        // Simulate link up
        viewModel.onLinkStateChanged(true)

        // Send CDP packet - record becomes complete with only CDP
        val cdpPacket = createCdpPacket()
        viewModel.onPacketForRecord(cdpPacket)

        // Record should be complete but NOT finalized yet
        val currentRecord = viewModel.currentRecord.value
        assertNotNull(currentRecord)
        assertFalse(currentRecord!!.hasLldp)
        assertTrue(currentRecord.hasCdp)
        assertFalse(viewModel.currentRecordFinalized.value)
        assertEquals(0, viewModel.history.value.size)

        // Advance time by 39 seconds - should NOT finalize yet
        advanceTimeBy(39_000)
        assertFalse(viewModel.currentRecordFinalized.value)
        assertEquals(0, viewModel.history.value.size)

        // Advance time by 2 more seconds (total 41s) - should finalize now
        advanceTimeBy(2_000)
        runCurrent()

        // Should be finalized after timeout
        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.history.value.size)
    }

    @Test
    fun testTimeoutCanceledWhenSecondProtocolArrives() = runTest {
        // RED: This test should fail because current code has no timeout cancellation

        // Simulate link up
        viewModel.onLinkStateChanged(true)

        // Send LLDP packet - starts timeout
        val lldpPacket = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Advance time by 20 seconds (before timeout)
        advanceTimeBy(20_000)
        assertFalse(viewModel.currentRecordFinalized.value)

        // Send CDP packet - should finalize immediately and cancel timeout
        val cdpPacket = createCdpPacket()
        viewModel.onPacketForRecord(cdpPacket)

        // Should finalize immediately (no delay)
        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.history.value.size)

        // Advance time past the original timeout - should not create duplicate
        advanceTimeBy(25_000)
        runCurrent()

        // Should still only have 1 record in history
        assertEquals(1, viewModel.history.value.size)
    }

    @Test
    fun testNewSessionOnlyAfterLinkDown() = runTest {
        // Verify existing behavior: new session requires link down >5 seconds

        // Start first session
        viewModel.onLinkStateChanged(true)
        val lldpPacket1 = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket1)

        val firstSessionId = viewModel.currentRecord.value?.id
        assertNotNull(firstSessionId)

        // Link goes down
        viewModel.onLinkStateChanged(false)

        // Advance 4 seconds (less than 5-second debounce)
        advanceTimeBy(4_000)

        // Link comes back up - should reuse same session
        viewModel.onLinkStateChanged(true)
        val lldpPacket2 = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket2)

        assertEquals(firstSessionId, viewModel.currentRecord.value?.id)

        // Link goes down again
        viewModel.onLinkStateChanged(false)

        // Advance 6 seconds (more than 5-second debounce)
        advanceTimeBy(6_000)
        runCurrent()

        // Session should be finalized
        assertNull(viewModel.currentRecord.value)

        // Link comes back up - should create NEW session
        viewModel.onLinkStateChanged(true)
        val lldpPacket3 = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket3)

        val newSessionId = viewModel.currentRecord.value?.id
        assertNotNull(newSessionId)
        assertNotEquals(firstSessionId, newSessionId)
    }

    @Test
    fun testSmartFinalization_FirstTimeSwitch_UseFullTimeout() = runTest {
        // Enable smart finalization
        viewModel.setSmartFinalizationEnabled(true)

        // Start session
        viewModel.onLinkStateChanged(true)

        // Send LLDP packet from unknown switch
        val lldpPacket = createLldpPacket()
        viewModel.onPacketForRecord(lldpPacket)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Should NOT finalize after 3 seconds (because switch is unknown)
        advanceTimeBy(3_500)
        assertFalse(viewModel.currentRecordFinalized.value)

        // Should finalize after full 40s timeout
        advanceTimeBy(37_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)
    }

    @Test
    fun testSmartFinalization_KnownSingleProtocolSwitch_UseFastTimeout() = runTest {
        // Enable smart finalization
        viewModel.setSmartFinalizationEnabled(true)

        // First session: learn the switch only sends LLDP
        viewModel.onLinkStateChanged(true)
        val lldpPacket1 = createLldpPacket(switchName = "Switch1", chassisId = "AA:BB:CC:DD:EE:FF")
        viewModel.onPacketForRecord(lldpPacket1)

        advanceTimeBy(41_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.switchProfiles.value.size)

        // Reset for second session
        viewModel.onLinkStateChanged(false)
        advanceTimeBy(6_000)
        runCurrent()

        // Second session: same switch, should use 3s timeout
        viewModel.onLinkStateChanged(true)
        val lldpPacket2 = createLldpPacket(switchName = "Switch1", chassisId = "AA:BB:CC:DD:EE:FF")
        viewModel.onPacketForRecord(lldpPacket2)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Should finalize after just 3s (smart finalization knows it's LLDP-only)
        advanceTimeBy(3_500)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)
    }

    @Test
    fun testSmartFinalization_KnownDualProtocolSwitch_UseFullTimeout() = runTest {
        // Enable smart finalization
        viewModel.setSmartFinalizationEnabled(true)

        // First session: switch sends both protocols
        viewModel.onLinkStateChanged(true)
        val lldpPacket1 = createLldpPacket(switchName = "Switch2", chassisId = "11:22:33:44:55:66")
        viewModel.onPacketForRecord(lldpPacket1)

        val cdpPacket1 = createCdpPacket(deviceId = "Switch2", platform = "11:22:33:44:55:66")
        viewModel.onPacketForRecord(cdpPacket1)

        // Should finalize immediately (both protocols received)
        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.switchProfiles.value.size)
        val profile = viewModel.switchProfiles.value.first()
        assertEquals(2, profile.observedProtocols.size)

        // Reset for second session
        viewModel.onLinkStateChanged(false)
        advanceTimeBy(6_000)
        runCurrent()

        // Second session: same switch
        viewModel.onLinkStateChanged(true)
        val lldpPacket2 = createLldpPacket(switchName = "Switch2", chassisId = "11:22:33:44:55:66")
        viewModel.onPacketForRecord(lldpPacket2)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Should NOT finalize after 3s (because we know it sends both protocols)
        advanceTimeBy(3_500)
        assertFalse(viewModel.currentRecordFinalized.value)

        // Should wait for full timeout OR second protocol
        val cdpPacket2 = createCdpPacket(deviceId = "Switch2", platform = "11:22:33:44:55:66")
        viewModel.onPacketForRecord(cdpPacket2)

        // Should finalize immediately now
        assertTrue(viewModel.currentRecordFinalized.value)
    }

    @Test
    fun testSmartFinalization_VersionChange_TreatAsNewSwitch() = runTest {
        // Enable smart finalization
        viewModel.setSmartFinalizationEnabled(true)

        // First session: version 1.0
        viewModel.onLinkStateChanged(true)
        val cdpPacket1 = createCdpPacket(
            deviceId = "Switch3",
            platform = "AA:AA:AA:AA:AA:AA",
            managementIp = "10.0.0.3"
        )
        viewModel.onPacketForRecord(cdpPacket1)

        advanceTimeBy(41_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)

        // Reset
        viewModel.onLinkStateChanged(false)
        advanceTimeBy(6_000)
        runCurrent()

        // Second session: version 2.0 (different software version in CDP)
        viewModel.onLinkStateChanged(true)
        val cdpPacket2 = createCdpPacket(
            deviceId = "Switch3",
            platform = "AA:AA:AA:AA:AA:AA",
            managementIp = "10.0.0.3"
        ).copy(cdpFrame = cdpPacket1.cdpFrame!!.copy(softwareVersion = "2.0.0"))
        viewModel.onPacketForRecord(cdpPacket2)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Should use full 40s timeout (version changed, treat as unknown)
        advanceTimeBy(3_500)
        assertFalse(viewModel.currentRecordFinalized.value)

        advanceTimeBy(37_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)
    }

    @Test
    fun testSmartFinalization_Disabled_AlwaysUseFullTimeout() = runTest {
        // Smart finalization is DISABLED (default state)
        assertFalse(viewModel.smartFinalizationEnabled.value)

        // First session: establish a profile manually
        viewModel.setSmartFinalizationEnabled(true)
        viewModel.onLinkStateChanged(true)
        val lldpPacket1 = createLldpPacket(switchName = "Switch4", chassisId = "BB:BB:BB:BB:BB:BB")
        viewModel.onPacketForRecord(lldpPacket1)

        advanceTimeBy(41_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)
        assertEquals(1, viewModel.switchProfiles.value.size)

        // Reset and DISABLE smart finalization
        viewModel.setSmartFinalizationEnabled(false)
        viewModel.onLinkStateChanged(false)
        advanceTimeBy(6_000)
        runCurrent()

        // Second session: even though we have a profile, should use full timeout
        viewModel.onLinkStateChanged(true)
        val lldpPacket2 = createLldpPacket(switchName = "Switch4", chassisId = "BB:BB:BB:BB:BB:BB")
        viewModel.onPacketForRecord(lldpPacket2)

        assertFalse(viewModel.currentRecordFinalized.value)

        // Should NOT finalize after 3s (smart finalization is disabled)
        advanceTimeBy(3_500)
        assertFalse(viewModel.currentRecordFinalized.value)

        // Should use full 40s timeout
        advanceTimeBy(37_000)
        runCurrent()

        assertTrue(viewModel.currentRecordFinalized.value)

        // Profile should NOT be updated (smart finalization disabled)
        assertEquals(1, viewModel.switchProfiles.value.first().sessionCount)
    }

    @Test
    fun testSwitchMemoryLimit_EnforcedOnSave() = runTest {
        viewModel.setSmartFinalizationEnabled(true)
        viewModel.setSwitchMemoryLimit(2)

        // Create 3 sessions with different switches, with real time delays
        // to ensure different lastSeen timestamps
        for (i in 1..3) {
            viewModel.onLinkStateChanged(true)
            val packet = createLldpPacket(
                switchName = "Switch$i",
                chassisId = "00:00:00:00:00:0$i"
            )
            viewModel.onPacketForRecord(packet)

            advanceTimeBy(41_000)
            runCurrent()

            viewModel.onLinkStateChanged(false)
            advanceTimeBy(6_000)
            runCurrent()

            // Add a small real-time delay to ensure timestamps differ
            Thread.sleep(10)
        }

        // Should only have 2 profiles (limit is 2, oldest should be evicted)
        assertEquals(2, viewModel.switchProfiles.value.size)

        // Most recent should be kept
        val profileIds = viewModel.switchProfiles.value.map { it.switchId }
        assertTrue(profileIds.contains("00:00:00:00:00:03"))
        assertTrue(profileIds.contains("00:00:00:00:00:02"))
        assertFalse(profileIds.contains("00:00:00:00:00:01"))
    }
}
