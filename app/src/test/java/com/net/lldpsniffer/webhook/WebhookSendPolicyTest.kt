package com.net.lldpsniffer.webhook

import com.net.lldpsniffer.model.MergedSwitchportRecord
import org.junit.Assert.*
import org.junit.Test

class WebhookSendPolicyTest {

    private fun record(vlan: Int? = 100, packets: Int = 1) = MergedSwitchportRecord(
        id = "s1",
        startTime = 0,
        vlanId = vlan,
        packetCount = packets
    )

    @Test
    fun noBaseSendWithoutTrigger() {
        val policy = WebhookSendPolicy()
        assertFalse(policy.shouldSend(record(), allowBaseSend = false))
    }

    @Test
    fun baseSendOnceOnTrigger() {
        val policy = WebhookSendPolicy()
        assertTrue(policy.shouldSend(record(vlan = 10), allowBaseSend = true))
        // Later triggers with the same VLAN never re-send
        assertFalse(policy.shouldSend(record(vlan = 10), allowBaseSend = true))
        assertFalse(policy.shouldSend(record(vlan = 10), allowBaseSend = false))
    }

    @Test
    fun vlanChangeResends() {
        val policy = WebhookSendPolicy()
        assertTrue(policy.shouldSend(record(vlan = 10), allowBaseSend = true))
        assertTrue(policy.shouldSend(record(vlan = 20), allowBaseSend = false))
        // Same new VLAN again - no re-send
        assertFalse(policy.shouldSend(record(vlan = 20), allowBaseSend = false))
        // And back again counts as another change
        assertTrue(policy.shouldSend(record(vlan = 10), allowBaseSend = false))
    }

    @Test
    fun unknownVlanAtBaseSendDoesNotTriggerResendWhenFirstLearned() {
        val policy = WebhookSendPolicy()
        assertTrue(policy.shouldSend(record(vlan = null), allowBaseSend = true))
        // Learning the VLAN for the first time is not a reassignment
        assertFalse(policy.shouldSend(record(vlan = 10), allowBaseSend = false))
        // A subsequent change away from it is
        assertTrue(policy.shouldSend(record(vlan = 20), allowBaseSend = false))
    }

    @Test
    fun repeatedUnknownVlanNeverResends() {
        val policy = WebhookSendPolicy()
        assertTrue(policy.shouldSend(record(vlan = null), allowBaseSend = true))
        assertFalse(policy.shouldSend(record(vlan = null), allowBaseSend = true))
        assertFalse(policy.shouldSend(record(vlan = null), allowBaseSend = false))
    }
}
