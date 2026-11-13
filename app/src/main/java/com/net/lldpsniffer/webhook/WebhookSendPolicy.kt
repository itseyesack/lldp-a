package com.net.lldpsniffer.webhook

import com.net.lldpsniffer.model.MergedSwitchportRecord

/**
 * Per-session webhook send policy. Exactly one "base" webhook is sent per session, fired by
 * whichever trigger happens first (the caller decides when to ask):
 *
 *  1. both LLDP and CDP frames have been seen (record is as complete as it will get),
 *  2. a deadline (60s after session start) so a left-plugged cable still reports,
 *  3. session end (link-down debounce or USB detach).
 *
 * After the base send, a resend happens only when the VLAN ID changes within the same
 * session (e.g. 802.1X/MAB reassigning the port) — one resend per distinct VLAN. Everything
 * else (repeat packets, port-id abbreviation flips like "Te1/0/40" vs
 * "TenGigabitEthernet1/0/40", protocol-set expansion) never re-posts.
 *
 * Pure state machine: no time, no I/O — the ViewModel owns timers and HTTP.
 */
class WebhookSendPolicy {

    private var baseSent = false
    private var lastSentVlanId: Int? = null
    private var lastSentVlanKnown = false

    /**
     * Whether [record] should be posted now, recording the send if so.
     *
     * [allowBaseSend] is true when a base-send trigger applies: both protocols seen, the 60s
     * deadline elapsed, or the session ended. Before the base send, nothing is posted unless
     * a trigger allows it. After the base send, only a VLAN change re-posts.
     */
    fun shouldSend(record: MergedSwitchportRecord, allowBaseSend: Boolean): Boolean {
        val vlan = record.vlanId
        return if (!baseSent) {
            if (!allowBaseSend) return false
            recordSend(vlan)
            true
        } else if (vlan == null || !lastSentVlanKnown) {
            // A VLAN that was simply unknown at the previous send doesn't count as a change -
            // adopt it as the baseline instead, so a later reassignment away from it is detected.
            if (vlan != null) {
                lastSentVlanId = vlan
                lastSentVlanKnown = true
            }
            false
        } else if (vlan != lastSentVlanId) {
            // Genuine VLAN reassignment within the session
            recordSend(vlan)
            true
        } else {
            false
        }
    }

    private fun recordSend(vlan: Int?) {
        baseSent = true
        lastSentVlanId = vlan
        lastSentVlanKnown = vlan != null
    }
}
