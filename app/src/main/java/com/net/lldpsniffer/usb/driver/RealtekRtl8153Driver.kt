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

    private fun readPlaByte(connection: UsbDeviceConnection, reg: Int): Int? {
        val buf = ByteArray(1)
        val n = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
            reg and 0xFFFC.toInt(), MCU_TYPE_PLA or (0x11 shl (reg and 3)),
            buf, 1, 1000
        )
        return if (n > 0) buf[0].toInt() and 0xFF else null
    }

    private fun readPlaDword(connection: UsbDeviceConnection, reg: Int): Long? {
        val buf = ByteArray(4)
        val n = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
            reg and 0xFFFC.toInt(), MCU_TYPE_PLA or 0xFF,
            buf, 4, 1000
        )
        if (n <= 0) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    /** Raw PLA_PHYSTATUS (0xE908) byte read, or null if the control transfer failed. */
    private fun readPlaPhyStatus(connection: UsbDeviceConnection): Int? {
        val linkBuf = ByteArray(1)
        val linkRead = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
            PLA_PHYSTATUS and 0xFFFC.toInt(), MCU_TYPE_PLA or (0x11 shl (PLA_PHYSTATUS and 3)),
            linkBuf, 1, 1000
        )
        return if (linkRead > 0) linkBuf[0].toInt() and 0xFF else null
    }

    /** Decodes a PLA_PHYSTATUS byte and logs it. Returns the link-up bit. */
    private fun decodeAndLogLinkStatus(linkByte: Int, logDiag: (String) -> Unit): Boolean {
        val linkUp = (linkByte and PLA_PHYSTATUS_LINK_STATUS) != 0
        val speedDesc = when {
            linkByte and PLA_PHYSTATUS_1000BPS != 0 -> "1000Mbps"
            linkByte and PLA_PHYSTATUS_100BPS != 0 -> "100Mbps"
            linkByte and PLA_PHYSTATUS_10BPS != 0 -> "10Mbps"
            else -> "unknown speed"
        }
        val dupDesc = if (linkByte and PLA_PHYSTATUS_FULL_DUP != 0) "full-duplex" else "half-duplex"
        logDiag("RTL8153 PHY Link Status (0xE908): 0x${String.format("%02X", linkByte)} (Link up: $linkUp, $speedDesc, $dupDesc)")
        return linkUp
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

        // 1. Unlock config-locked registers before touching RCR/CR/RMS/MAR.
        val unlock = writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_CONFIG)
        logDiag("RTL8153 PLA_CRWECR unlock: $unlock")
        if (unlock < 0) criticalFailures++

        // 2. Reset the MAC (PLA_CR bit RST) and poll until the device clears it.
        val resetIssued = writePlaByte(connection, logDiag, PLA_CR, CR_RST)
        logDiag("RTL8153 PLA_CR reset issued: $resetIssued")
        if (resetIssued < 0) {
            criticalFailures++
        } else {
            var resetCleared = false
            val readBuf = ByteArray(1)
            for (attempt in 1..20) {
                val read = connection.controlTransfer(
                    REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
                    PLA_CR and 0xFFFC.toInt(), MCU_TYPE_PLA or (0x11 shl (PLA_CR and 3)),
                    readBuf, 1, 1000
                )
                if (read > 0 && (readBuf[0].toInt() and CR_RST) == 0) {
                    resetCleared = true
                    break
                }
                Thread.sleep(10)
            }
            logDiag("RTL8153 MAC reset cleared: $resetCleared")
        }

        // 3. Set Rx Max Packet Size (PLA_RMS) to 1536 bytes.
        val rmsRes = writePlaWord(connection, logDiag, PLA_RMS, 1536)
        logDiag("RTL8153 PLA_RMS (1536B): $rmsRes")

        // 4. Set Multicast Hash Table (PLA_MAR, 8 bytes) to accept all multicast.
        val mar0 = writePlaDword(connection, logDiag, PLA_MAR, 0xFFFFFFFFL)
        val mar4 = writePlaDword(connection, logDiag, PLA_MAR + 4, 0xFFFFFFFFL)
        logDiag("RTL8153 Multicast Hash Table (MAR0-7 0xFF): MAR0=$mar0, MAR4=$mar4")

        // 5. Configure PLA_RCR: AAP|APM|AM|AB = accept everything (promiscuous sniffing).
        val rcrRes = writePlaDword(connection, logDiag, PLA_RCR, RCR_ACCEPT_ALL.toLong())
        // Read back the register we just wrote instead of trusting the transfer's return
        // code (a successful controlTransfer only proves the USB host controller ACK'd the
        // OUT packet, not that the chip's MCU actually decoded and applied it) - several
        // prior fixes assumed "transfer succeeded" == "register took effect" and were wrong.
        val rcrReadback = readPlaDword(connection, PLA_RCR)
        logDiag("RTL8153 PLA_RCR (accept-all 0x0F): write=$rcrRes readback=0x${rcrReadback?.let { String.format("%08X", it) } ?: "read failed"}")
        if (rcrRes < 0) criticalFailures++

        // 5.5. Reset the RX packet filter (PLA_FMC FCR_MCU_EN off/on), matching the kernel's
        // rtl_enable(): r8152b_reset_packet_filter() runs immediately before RE/TE are set.
        val fmcBefore = readPlaByte(connection, PLA_FMC)
        val fmcCleared = fmcBefore?.let { writePlaByte(connection, logDiag, PLA_FMC, it and FMC_FCR_MCU_EN.inv()) } ?: -1
        val fmcSet = fmcBefore?.let { writePlaByte(connection, logDiag, PLA_FMC, it or FMC_FCR_MCU_EN) } ?: -1
        logDiag("RTL8153 PLA_FMC packet-filter reset (clear=$fmcCleared, set=$fmcSet)")

        // 6. Enable RX and TX in the Command Register: TE|RE. Read-modify-write, matching
        // the kernel's ocp_byte_set_bits() - a blind overwrite clobbers whatever other bits
        // the chip had already set in CR.
        val crBefore = readPlaByte(connection, PLA_CR) ?: 0
        val crRes = writePlaByte(connection, logDiag, PLA_CR, crBefore or CR_TE or CR_RE)
        val crReadback = readPlaByte(connection, PLA_CR)
        logDiag("RTL8153 PLA_CR (Rx/Tx Enable, RMW from 0x${String.format("%02X", crBefore)}): write=$crRes readback=0x${crReadback?.let { String.format("%02X", it) } ?: "read failed"}")
        if (crRes < 0) criticalFailures++

        // 6.5. Clear RXDY_GATED_EN in PLA_MISC_1. The kernel's rtl_enable() always clears
        // this as the final step before traffic flows - when set, it gates (blocks) the
        // RX-ready signal at the FIFO, so the MAC can look fully RX-enabled with link up
        // and still deliver zero bytes to the host.
        val miscBefore = readPlaByte(connection, PLA_MISC_1)
        val rxdyGateRes = miscBefore?.let { writePlaByte(connection, logDiag, PLA_MISC_1, it and RXDY_GATED_EN.inv()) } ?: -1
        logDiag("RTL8153 PLA_MISC_1 RXDY_GATED_EN clear (was 0x${miscBefore?.let { String.format("%02X", it) } ?: "read failed"}): $rxdyGateRes")

        // 7. Re-lock config registers.
        val lock = writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_NORMAL)
        logDiag("RTL8153 PLA_CRWECR lock: $lock")

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
            val linkUp = decodeAndLogLinkStatus(linkByte, logDiag)
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
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_CONFIG)
        val fmcBefore = readPlaByte(connection, PLA_FMC)
        val fmcCleared = fmcBefore?.let { writePlaByte(connection, logDiag, PLA_FMC, it and FMC_FCR_MCU_EN.inv()) } ?: -1
        val fmcSet = fmcBefore?.let { writePlaByte(connection, logDiag, PLA_FMC, it or FMC_FCR_MCU_EN) } ?: -1
        val crBefore = readPlaByte(connection, PLA_CR) ?: 0
        val crRes = writePlaByte(connection, logDiag, PLA_CR, crBefore or CR_TE or CR_RE)
        val crReadback = readPlaByte(connection, PLA_CR)
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_NORMAL)
        logDiag(
            "RTL8153 link-up RX re-kick: FMC(clear=$fmcCleared,set=$fmcSet) " +
                "CR(write=$crRes readback=0x${crReadback?.let { String.format("%02X", it) } ?: "read failed"})"
        )
    }

    override fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean? {
        val phyByte = readPlaPhyStatus(connection) ?: return null
        return decodeAndLogLinkStatus(phyByte, logDiag)
    }
}
