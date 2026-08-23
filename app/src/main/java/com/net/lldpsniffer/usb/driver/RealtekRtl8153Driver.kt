package com.net.lldpsniffer.usb.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Realtek RTL8152/8153/8156 vendor-mode (Configuration 1) bring-up. Extracted from the
 * original monolithic UsbConnectionManager implementation - behavior is unchanged, only
 * the connection/logDiag dependencies moved from instance fields to parameters.
 */
class RealtekRtl8153Driver : VendorAdapterDriver {

    companion object {
        const val REALTEK_VID = 0x0BDA
        const val RTL8153_PID = 0x8153
        const val RTL8152_PID = 0x8152
        const val RTL8156_PID = 0x8156

        private const val VENDOR_REQ_SET_REGS = 0x05
        private const val VENDOR_REQ_GET_REGS = 0x05
        private const val REQUEST_TYPE_VENDOR_OUT = 0x40
        private const val REQUEST_TYPE_VENDOR_IN = 0xC0
        private const val MCU_TYPE_PLA = 0x0100

        private const val PLA_RCR = 0xC010
        private const val PLA_RMS = 0xC016
        private const val PLA_MAR = 0xCD00
        private const val PLA_CR = 0xE813
        private const val PLA_CRWECR = 0xE81C
        private const val PLA_PHYSTATUS = 0xE908
        private const val PLA_FMC = 0xC0B4
        private const val FMC_FCR_MCU_EN = 0x0001
        private const val PLA_MISC_1 = 0xE85A
        private const val RXDY_GATED_EN = 0x0008
        private const val CRWECR_CONFIG = 0xC0
        private const val CRWECR_NORMAL = 0x00
        private const val RCR_ACCEPT_ALL = 0x0000000F
        private const val CR_RST = 0x10
        private const val CR_TE = 0x04
        private const val CR_RE = 0x08

        private const val PLA_PHYSTATUS_FULL_DUP = 0x01
        private const val PLA_PHYSTATUS_LINK_STATUS = 0x02
        private const val PLA_PHYSTATUS_10BPS = 0x04
        private const val PLA_PHYSTATUS_100BPS = 0x08
        private const val PLA_PHYSTATUS_1000BPS = 0x10
    }

    override val name = "Realtek RTL8153/8152/8156"

    // RTL8153/8156 are Gigabit-capable; the older RTL8152 in this same family is 10/100
    // only, but the driver has no way to disambiguate PID from this static property, so
    // this reports the family's ceiling rather than a per-chip exact figure.
    override val maxLinkMbps = 1000

    override fun matches(device: UsbDevice): Boolean {
        return device.vendorId == REALTEK_VID &&
            device.productId in setOf(RTL8153_PID, RTL8152_PID, RTL8156_PID)
    }

    // laneMask matches the kernel's per-width shift: ocp_write_byte uses `index & 3`
    // (any byte lane), ocp_write_word uses `index & 2` (word-aligned, so bit0 is
    // always 0), ocp_write_dword always targets a 4-aligned address (lane 0).
    private fun ocpWrite(connection: UsbDeviceConnection, logDiag: (String) -> Unit, mcuType: Int, reg: Int, byteEnBase: Int, laneMask: Int, value: Long): Int {
        return try {
            val lane = reg and laneMask
            val byteEn = (byteEnBase shl lane) and 0xFF
            val index = mcuType or byteEn
            val addr = reg and 0xFFFC.toInt()
            val shifted = value shl (lane * 8)
            val buf = ByteArray(4)
            for (i in 0 until 4) {
                buf[i] = ((shifted shr (i * 8)) and 0xFF).toByte()
            }
            connection.controlTransfer(REQUEST_TYPE_VENDOR_OUT, VENDOR_REQ_SET_REGS, addr, index, buf, 4, 1000)
        } catch (e: Exception) {
            logDiag("Vendor register write to 0x${String.format("%04X", reg)} threw: ${e.message}")
            -1
        }
    }

    private fun writePlaByte(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int) =
        ocpWrite(connection, logDiag, MCU_TYPE_PLA, reg, 0x11, 3, value.toLong() and 0xFF)

    private fun writePlaWord(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int) =
        ocpWrite(connection, logDiag, MCU_TYPE_PLA, reg, 0x33, 2, value.toLong() and 0xFFFF)

    private fun writePlaDword(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Long) =
        ocpWrite(connection, logDiag, MCU_TYPE_PLA, reg, 0xFF, 0, value and 0xFFFFFFFFL)

    /**
     * Reads back a full aligned dword at `reg`'s 4-byte-aligned base address. Matches the
     * kernel's generic_ocp_read (r8152.c): unlike writes, OCP reads take no byte-enable at
     * all - wIndex is just the MCU type - and always return the whole dword; the caller
     * shifts out whichever byte/word lane it actually wants. Earlier revisions of this file
     * OR'd a byte-enable into wIndex and requested a short (1/2-byte) transfer on reads, the
     * same shape as writes - that isn't a request real RTL8153 firmware honors, and sending
     * it wedges the chip's MCU (every subsequent register access then fails and the adapter
     * drops off the bus), rather than just being ignored or harmless.
     */
    private fun readPlaAlignedDword(connection: UsbDeviceConnection, reg: Int): Long? {
        val buf = ByteArray(4)
        val n = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
            reg and 0xFFFC.toInt(), MCU_TYPE_PLA,
            buf, 4, 1000
        )
        if (n <= 0) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun readPlaByte(connection: UsbDeviceConnection, reg: Int): Int? {
        val dword = readPlaAlignedDword(connection, reg) ?: return null
        val lane = reg and 3
        return ((dword shr (lane * 8)) and 0xFF).toInt()
    }

    private fun readPlaWord(connection: UsbDeviceConnection, reg: Int): Int? {
        val dword = readPlaAlignedDword(connection, reg) ?: return null
        val lane = reg and 2
        return ((dword shr (lane * 8)) and 0xFFFF).toInt()
    }

    private fun readPlaDword(connection: UsbDeviceConnection, reg: Int): Long? =
        readPlaAlignedDword(connection, reg)

    /** Raw PLA_PHYSTATUS (0xE908) byte read, or null if the control transfer failed. */
    private fun readPlaPhyStatus(connection: UsbDeviceConnection): Int? =
        readPlaByte(connection, PLA_PHYSTATUS)

    /** Decodes a PLA_PHYSTATUS byte and logs it. */
    private fun decodeAndLogLinkStatus(linkByte: Int, logDiag: (String) -> Unit): LinkStatus {
        val linkUp = (linkByte and PLA_PHYSTATUS_LINK_STATUS) != 0
        val speedMbps = when {
            linkByte and PLA_PHYSTATUS_1000BPS != 0 -> 1000
            linkByte and PLA_PHYSTATUS_100BPS != 0 -> 100
            linkByte and PLA_PHYSTATUS_10BPS != 0 -> 10
            else -> null
        }
        val duplex = if (linkByte and PLA_PHYSTATUS_FULL_DUP != 0) "Full" else "Half"
        logDiag("RTL8153 PHY Link Status (0xE908): 0x${String.format("%02X", linkByte)} (Link up: $linkUp, ${speedMbps ?: "unknown"}Mbps, $duplex-duplex)")
        return LinkStatus(up = linkUp, speedMbps = speedMbps, duplex = duplex)
    }

    // The four helpers below all follow the same shape: write, read the register back, and
    // only report success if the readback actually matches what was written - a successful
    // controlTransfer only proves the USB host controller ACK'd the OUT packet, not that the
    // target MCU decoded and applied it. A transient miss (e.g. right as the chip is still
    // settling after the MAC reset) can succeed a moment later, so each retries a few times
    // before being treated as a real hardware fault.

    private fun writeVerifyByte(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int, label: String, maxAttempts: Int = 3): Boolean {
        val target = value and 0xFF
        for (attempt in 1..maxAttempts) {
            val res = writePlaByte(connection, logDiag, reg, target)
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readPlaByte(connection, reg)
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): write=$res readback=${readback?.let { "0x" + String.format("%02X", it) } ?: "read failed"} target=0x${String.format("%02X", target)} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) return true
            if (attempt < maxAttempts) Thread.sleep(10)
        }
        return false
    }

    private fun writeVerifyWord(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int, label: String, maxAttempts: Int = 3): Boolean {
        val target = value and 0xFFFF
        for (attempt in 1..maxAttempts) {
            val res = writePlaWord(connection, logDiag, reg, target)
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readPlaWord(connection, reg)
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): write=$res readback=${readback?.let { "0x" + String.format("%04X", it) } ?: "read failed"} target=0x${String.format("%04X", target)} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) return true
            if (attempt < maxAttempts) Thread.sleep(10)
        }
        return false
    }

    private fun writeVerifyDword(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Long, label: String, maxAttempts: Int = 3): Boolean {
        val target = value and 0xFFFFFFFFL
        for (attempt in 1..maxAttempts) {
            val res = writePlaDword(connection, logDiag, reg, target)
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readPlaDword(connection, reg)
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): write=$res readback=0x${readback?.let { String.format("%08X", it) } ?: "read failed"} target=0x${String.format("%08X", target)} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) return true
            if (attempt < maxAttempts) Thread.sleep(10)
        }
        return false
    }

    /**
     * Read-modify-write a byte register (matching the kernel's ocp_byte_set_bits/clear_bits):
     * re-reads the current value on every attempt rather than reusing one snapshot, so a bit
     * that another retry already managed to land isn't clobbered back off by this one.
     *
     * A hard transfer failure (pre-read or write returning an error, not just a value
     * mismatch) means the device isn't responding right now - observed on hardware to
     * follow a chip-level drop that needs the device to fully re-enumerate, so hammering
     * it with more immediate retries only adds traffic to an already-unresponsive bus.
     * Those cases fail fast instead of exhausting all attempts.
     */
    private fun writeVerifyBits(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, setMask: Int, clearMask: Int, label: String, maxAttempts: Int = 3): Boolean {
        for (attempt in 1..maxAttempts) {
            val current = readPlaByte(connection, reg)
            if (current == null) {
                logDiag("$label (attempt $attempt/$maxAttempts): pre-read failed - device unresponsive, not retrying")
                return false
            }
            val target = (current and clearMask.inv()) or setMask
            val res = writePlaByte(connection, logDiag, reg, target)
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readPlaByte(connection, reg)
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): current=0x${String.format("%02X", current)} write=$res readback=${readback?.let { "0x" + String.format("%02X", it) } ?: "read failed"} target=0x${String.format("%02X", target)} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) return true
            if (attempt < maxAttempts) Thread.sleep(10)
        }
        return false
    }

    /**
     * Byte-enable protocol (Linux r8152.c generic_ocp_write): unaligned writes always send a
     * 4-byte transfer with the value shifted into the lane selected by byte_en, not a short
     * transfer of just the changed bytes - a short transfer silently writes into the wrong lane.
     *
     * Returns true only if the register writes we treat as load-bearing (CRWECR unlock, MAC
     * reset, RCR, CR) all reported success, so the caller can tell "hardware not accepting
     * commands" apart from "hardware configured, just no traffic yet".
     */
    override fun bringUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean {
        var criticalFailures = 0

        logDiag("Configuring RTL8153 Vendor Registers (with Linux r8152 byte-enables)...")

        // 1. Unlock config-locked registers before touching RCR/CR/RMS/MAR. This register's
        // readback doesn't reliably echo what was written on real hardware - observed a
        // consistent 0xD0 after writing 0xC0, with no timeout/error symptoms, so it isn't a
        // bus fault, just an unreliable echo - and the kernel driver never reads it back
        // either. Only the write's own transfer result gates failure here; the real signal
        // that the unlock actually took is whether the locked-register writes below succeed.
        val unlockRes = writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_CONFIG)
        val unlockReadback = readPlaByte(connection, PLA_CRWECR)
        logDiag("RTL8153 PLA_CRWECR unlock: write=$unlockRes readback=${unlockReadback?.let { "0x" + String.format("%02X", it) } ?: "read failed"} (informational only, not gated)")
        if (unlockRes < 0) criticalFailures++

        // 2. Reset the MAC (PLA_CR bit RST) and poll until the device clears it.
        val resetIssued = writePlaByte(connection, logDiag, PLA_CR, CR_RST)
        logDiag("RTL8153 PLA_CR reset issued: $resetIssued")
        if (resetIssued < 0) {
            criticalFailures++
        } else {
            var resetCleared = false
            for (attempt in 1..20) {
                val read = readPlaByte(connection, PLA_CR)
                if (read != null && (read and CR_RST) == 0) {
                    resetCleared = true
                    break
                }
                Thread.sleep(10)
            }
            logDiag("RTL8153 MAC reset cleared: $resetCleared")
            if (!resetCleared) criticalFailures++
        }

        // 3. Set Rx Max Packet Size (PLA_RMS) to 1536 bytes.
        val rmsOk = writeVerifyWord(connection, logDiag, PLA_RMS, 1536, "RTL8153 PLA_RMS (1536B)")
        if (!rmsOk) criticalFailures++

        // 4. Set Multicast Hash Table (PLA_MAR, 8 bytes) to accept all multicast.
        val mar0Ok = writeVerifyDword(connection, logDiag, PLA_MAR, 0xFFFFFFFFL, "RTL8153 PLA_MAR0")
        val mar4Ok = writeVerifyDword(connection, logDiag, PLA_MAR + 4, 0xFFFFFFFFL, "RTL8153 PLA_MAR4")
        if (!mar0Ok || !mar4Ok) criticalFailures++

        // 5. Configure PLA_RCR: AAP|APM|AM|AB = accept everything (promiscuous sniffing).
        // Verified via readback instead of trusting the transfer's return code - a successful
        // controlTransfer only proves the USB host controller ACK'd the OUT packet, not that
        // the chip's MCU actually decoded and applied it. Several prior fixes assumed
        // "transfer succeeded" == "register took effect" and were wrong.
        val rcrOk = writeVerifyDword(connection, logDiag, PLA_RCR, RCR_ACCEPT_ALL.toLong(), "RTL8153 PLA_RCR (accept-all 0x0F)")
        if (!rcrOk) criticalFailures++

        // 5.5. Reset the RX packet filter (PLA_FMC FCR_MCU_EN off/on), matching the kernel's
        // rtl_enable(): r8152b_reset_packet_filter() runs immediately before RE/TE are set.
        val fmcClearOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = 0, clearMask = FMC_FCR_MCU_EN, label = "RTL8153 PLA_FMC clear FCR_MCU_EN")
        val fmcSetOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = FMC_FCR_MCU_EN, clearMask = 0, label = "RTL8153 PLA_FMC set FCR_MCU_EN")
        if (!fmcClearOk || !fmcSetOk) criticalFailures++

        // 6. Enable RX and TX in the Command Register: TE|RE. Read-modify-write, matching
        // the kernel's ocp_byte_set_bits() - a blind overwrite clobbers whatever other bits
        // the chip had already set in CR.
        val crOk = writeVerifyBits(connection, logDiag, PLA_CR, setMask = CR_TE or CR_RE, clearMask = 0, label = "RTL8153 PLA_CR (Rx/Tx Enable)")
        if (!crOk) criticalFailures++

        // 6.5. Clear RXDY_GATED_EN in PLA_MISC_1. The kernel's rtl_enable() always clears
        // this as the final step before traffic flows - when set, it gates (blocks) the
        // RX-ready signal at the FIFO, so the MAC can look fully RX-enabled with link up
        // and still deliver zero bytes to the host.
        val rxdyGateOk = writeVerifyBits(connection, logDiag, PLA_MISC_1, setMask = 0, clearMask = RXDY_GATED_EN, label = "RTL8153 PLA_MISC_1 RXDY_GATED_EN clear")
        if (!rxdyGateOk) criticalFailures++

        // 7. Re-lock config registers. Same unreliable-readback caveat as the unlock above -
        // logged for visibility only, not gated, and by this point RX/TX are already
        // enabled so failing to re-lock doesn't stop traffic anyway.
        val lockRes = writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_NORMAL)
        val lockReadback = readPlaByte(connection, PLA_CRWECR)
        logDiag("RTL8153 PLA_CRWECR lock: write=$lockRes readback=${lockReadback?.let { "0x" + String.format("%02X", it) } ?: "read failed"} (informational only, not gated)")

        // 8. Poll PHY Link Status for a few seconds instead of a single immediate read.
        // A one-shot check right after the MAC reset above previously misreported a
        // genuinely-plugged-in cable as "down" because PHY (re)negotiation had not yet
        // completed in the ~15ms between reset and read - autonegotiation legitimately
        // takes up to a few seconds. A failed read means "unknown", not "up".
        var linkByte: Int? = null
        for (attempt in 1..30) {
            linkByte = readPlaPhyStatus(connection)
            if (linkByte != null && (linkByte and PLA_PHYSTATUS_LINK_STATUS) != 0) break
            Thread.sleep(100)
        }
        if (linkByte != null) {
            val linkUp = decodeAndLogLinkStatus(linkByte, logDiag).up
            if (linkUp) {
                // Link may have come up seconds after the FMC/CR sequence above already ran
                // while link was still down - re-kick now that it's confirmed up.
                onLinkUp(connection, logDiag)
            }
        } else {
            logDiag("RTL8153 PHY Link Status (0xE908): read failed - link state unknown")
        }

        if (criticalFailures > 0) {
            logDiag("RTL8153 vendor bring-up had $criticalFailures critical register failure(s). Interface is likely not actually owned by us (kernel driver still bound, or wrong configuration active).")
        }
        return criticalFailures == 0
    }

    /**
     * Re-issues the packet-filter reset + TE/RE enable that [bringUp] normally runs once
     * at attach. The kernel's r8152 driver reruns this same sequence on every link-change-
     * to-up event (its link-change worker), not just once at device open - if our one-shot
     * attach-time bring-up ran while the PHY was still negotiating (link still down), the
     * chip's RX FIFO delivery never actually unblocked even after link later came up.
     * Re-running it on every confirmed down->up transition fixes that.
     */
    override fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        // PLA_CRWECR's readback doesn't reliably echo what was written (see bringUp) -
        // written and moved past without retrying, same as the attach-time bring-up.
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_CONFIG)
        val fmcClearOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = 0, clearMask = FMC_FCR_MCU_EN, label = "RTL8153 link-up PLA_FMC clear FCR_MCU_EN")
        val fmcSetOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = FMC_FCR_MCU_EN, clearMask = 0, label = "RTL8153 link-up PLA_FMC set FCR_MCU_EN")
        val crOk = writeVerifyBits(connection, logDiag, PLA_CR, setMask = CR_TE or CR_RE, clearMask = 0, label = "RTL8153 link-up PLA_CR (Rx/Tx Enable)")
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_NORMAL)
        logDiag("RTL8153 link-up RX re-kick summary: FMC(clear=$fmcClearOk,set=$fmcSetOk) CR=$crOk")
    }

    override fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean? {
        val phyByte = readPlaPhyStatus(connection) ?: return null
        return decodeAndLogLinkStatus(phyByte, logDiag).up
    }

    override fun readLinkStatus(connection: UsbDeviceConnection, logDiag: (String) -> Unit): LinkStatus? {
        val phyByte = readPlaPhyStatus(connection) ?: return null
        return decodeAndLogLinkStatus(phyByte, logDiag)
    }
}
