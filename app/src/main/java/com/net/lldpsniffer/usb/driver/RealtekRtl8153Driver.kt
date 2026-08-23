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
        private const val MCU_TYPE_USB = 0x0000

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
        private const val PLA_OOB_CTRL = 0xE84F
        private const val FIFO_EMPTY = 0x30 // TXFIFO_EMPTY (0x20) | RXFIFO_EMPTY (0x10)
        private const val NOW_IS_OOB = 0x80
        private const val LINK_LIST_READY = 0x02
        private const val PLA_SFF_STS_7 = 0xE8DE
        private const val RE_INIT_LL = 0x8000
        private const val MCU_BORW_EN = 0x4000
        private const val USB_BMU_RESET = 0xD4B0
        private const val BMU_RESET_EP_IN = 0x01
        private const val BMU_RESET_EP_OUT = 0x02
        private const val PLA_TCR0 = 0xE610
        private const val TCR0_TX_EMPTY = 0x0800
        private const val TCR0_AUTO_FIFO = 0x0080
        private const val PLA_RXFIFO_CTRL0 = 0xC0A0
        private const val PLA_RXFIFO_CTRL1 = 0xC0A4
        private const val PLA_RXFIFO_CTRL2 = 0xC0A8
        private const val PLA_TXFIFO_CTRL = 0xE618
        private const val RXFIFO_THR1_NORMAL = 0x00080002L
        private const val RXFIFO_THR2_NORMAL = 0x00A0
        private const val RXFIFO_THR3_NORMAL = 0x0110
        private const val TXFIFO_THR_NORMAL2 = 0x01000008L
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

    // The BMU (bus master unit, i.e. the USB-side DMA engines) lives in the USB register
    // space rather than the PLA/MAC space - same OCP protocol, different wIndex MCU type.
    private fun writeUsbByte(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int) =
        ocpWrite(connection, logDiag, MCU_TYPE_USB, reg, 0x11, 3, value.toLong() and 0xFF)

    private fun readUsbByte(connection: UsbDeviceConnection, reg: Int): Int? {
        val dword = readOcpAlignedDword(connection, MCU_TYPE_USB, reg) ?: return null
        return ((dword shr ((reg and 3) * 8)) and 0xFF).toInt()
    }

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
    private fun readOcpAlignedDword(connection: UsbDeviceConnection, mcuType: Int, reg: Int): Long? {
        val buf = ByteArray(4)
        val n = connection.controlTransfer(
            REQUEST_TYPE_VENDOR_IN, VENDOR_REQ_GET_REGS,
            reg and 0xFFFC.toInt(), mcuType,
            buf, 4, 1000
        )
        if (n <= 0) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun readPlaAlignedDword(connection: UsbDeviceConnection, reg: Int): Long? =
        readOcpAlignedDword(connection, MCU_TYPE_PLA, reg)

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
     * Read-modify-write a dword register, same shape as writeVerifyBits below but for the
     * wider registers (PLA_RCR) that need it: hardware showed RCR consistently reading back
     * with an extra bit set (0x00020000) that was never written and can't be cleared by
     * writing 0 to it - some bits in this register are apparently owned by the chip itself,
     * not just an unwritten reserved field. A blind absolute write's readback could never
     * match that, permanently flagging bring-up as broken. Reading current and only
     * OR/AND-ing the bits this driver actually cares about preserves whatever the chip owns
     * instead of fighting it.
     */
    private fun writeVerifyDwordBits(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, setMask: Long, clearMask: Long, label: String, maxAttempts: Int = 3): Boolean {
        for (attempt in 1..maxAttempts) {
            val current = readPlaDword(connection, reg)
            if (current == null) {
                logDiag("$label (attempt $attempt/$maxAttempts): pre-read failed - device unresponsive, not retrying")
                return false
            }
            val target = (current and clearMask.inv()) or setMask
            val res = writePlaDword(connection, logDiag, reg, target)
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readPlaDword(connection, reg)
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): current=0x${String.format("%08X", current)} write=$res readback=0x${readback?.let { String.format("%08X", it) } ?: "read failed"} target=0x${String.format("%08X", target)} ${if (ok) "OK" else "MISMATCH"}")
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
     * Mirrors the version-independent front half of the kernel's rtl_disable() (r8152.c):
     * stop accepting packets, gate RX at the FIFO, and wait for the RX/TX FIFOs to drain
     * before the MAC reset that follows. Quiescing the chip is a precondition for the reset
     * and the OOB handover in [rtlExitOob] to land on a settled FIFO rather than one still
     * being written into. A genuinely cold chip (FIFOs already empty, RCR/MISC_1 already at
     * their cleared values) no-ops through this.
     */
    private fun rtlDisable(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        val rcrOk = writeVerifyDwordBits(connection, logDiag, PLA_RCR, setMask = 0L, clearMask = RCR_ACCEPT_ALL.toLong(), label = "RTL8153 pre-disable PLA_RCR clear accept-all")
        val gateOk = writeVerifyBits(connection, logDiag, PLA_MISC_1, setMask = RXDY_GATED_EN, clearMask = 0, label = "RTL8153 pre-disable PLA_MISC_1 RXDY_GATED_EN set")

        var rxFifoEmpty = false
        for (attempt in 1..100) {
            val oob = readPlaByte(connection, PLA_OOB_CTRL)
            if (oob != null && (oob and FIFO_EMPTY) == FIFO_EMPTY) {
                rxFifoEmpty = true
                break
            }
            Thread.sleep(5)
        }
        var txEmpty = false
        for (attempt in 1..100) {
            val tcr0 = readPlaWord(connection, PLA_TCR0)
            if (tcr0 != null && (tcr0 and TCR0_TX_EMPTY) != 0) {
                txEmpty = true
                break
            }
            Thread.sleep(5)
        }
        logDiag("RTL8153 pre-disable drain: rcrClearOk=$rcrOk gateSetOk=$gateOk rxFifoEmpty=$rxFifoEmpty txEmpty=$txEmpty")
    }

    private fun hex(value: Int?, digits: Int) =
        value?.let { "0x" + String.format("%0${digits}X", it) } ?: "read-failed"

    /**
     * Kernel rtl8152_nic_reset(), default branch: assert PLA_CR's RST bit and poll until the
     * chip clears it. The alternate branch the kernel takes for RTL_TEST_01/RTL_VER_10/
     * RTL_VER_11 isn't replicated - those sub-revisions can't be told apart from USB VID/PID
     * alone, and mainstream RTL8153 takes this branch anyway.
     */
    private fun nicReset(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phase: String): Boolean {
        val issued = writePlaByte(connection, logDiag, PLA_CR, CR_RST)
        if (issued < 0) {
            logDiag("RTL8153 $phase MAC reset: write transfer failed ($issued)")
            return false
        }
        for (attempt in 1..20) {
            val read = readPlaByte(connection, PLA_CR)
            if (read != null && (read and CR_RST) == 0) {
                logDiag("RTL8153 $phase MAC reset cleared: true")
                return true
            }
            Thread.sleep(10)
        }
        logDiag("RTL8153 $phase MAC reset cleared: false")
        return false
    }

    /** Kernel wait_oob_link_list_ready(): poll PLA_OOB_CTRL for LINK_LIST_READY. */
    private fun waitOobLinkListReady(connection: UsbDeviceConnection): Boolean {
        for (attempt in 1..1000) {
            val oob = readPlaByte(connection, PLA_OOB_CTRL) ?: return false
            if ((oob and LINK_LIST_READY) != 0) return true
            Thread.sleep(1)
        }
        return false
    }

    /**
     * The back half of the kernel's r8153_first_init(): hand the RX path over from the chip's
     * own MCU to the host. This is the stage this driver was missing entirely, and it explains
     * the intermittent zero-packet capture that survived both the FIFO drain in [rtlDisable]
     * and gating RX until the bulk reader attaches.
     *
     * An RTL8153 with no host driver bound keeps its MAC alive in OOB (out-of-band) mode under
     * on-chip firmware, for management/wake traffic. On hardware, an adapter found in that
     * state reads back PLA_RCR = ...0E (the MCU's own accept-bits) with RXDY_GATED_EN already
     * clear, whereas one that is genuinely cold reads ...00 with the gate still set - and
     * across every captured session, warm-at-attach meant zero packets and cold-at-attach
     * meant capture worked. Nothing else in the two bring-ups differed by a single bit.
     *
     * The reason is that in OOB mode the RX FIFO's descriptor link list belongs to the MCU,
     * not to the host's bulk-IN DMA. Reconfiguring the MAC and ungating RX makes the chip
     * accept frames perfectly happily - every register verifies, the PHY reports 1000Mbps
     * full-duplex, EP0 keeps answering - but the accepted frames land in a FIFO whose link
     * list was never rebuilt for host ownership, so not one byte reaches the bulk pipe. Only
     * RE_INIT_LL rebuilds it, and it can only be issued once NOW_IS_OOB and MCU_BORW_EN are
     * cleared. Note the chip stays in OOB across a replug, since the MCU keeps running - which
     * is why unplugging the adapter repeatedly could fail many times in a row.
     */
    private fun rtlExitOob(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        // rtl_reset_bmu(): toggle the USB-side bulk IN/OUT DMA engines off and back on. Note
        // this one lives in USB register space, not PLA. Unconditional in the kernel for every
        // chip version, unlike the endpoint poking inside rtl8152_nic_reset.
        val bmuBefore = readUsbByte(connection, USB_BMU_RESET)
        if (bmuBefore == null) {
            logDiag("RTL8153 exit-OOB BMU reset: USB_BMU_RESET read failed, skipped")
        } else {
            val cleared = bmuBefore and (BMU_RESET_EP_IN or BMU_RESET_EP_OUT).inv()
            val restored = cleared or BMU_RESET_EP_IN or BMU_RESET_EP_OUT
            writeUsbByte(connection, logDiag, USB_BMU_RESET, cleared)
            writeUsbByte(connection, logDiag, USB_BMU_RESET, restored)
            logDiag("RTL8153 exit-OOB BMU reset: ${hex(bmuBefore, 2)} -> ${hex(cleared, 2)} -> ${hex(restored, 2)}")
        }

        // PLA_OOB_CTRL shares its byte with live status bits (FIFO_EMPTY, LINK_LIST_READY), and
        // PLA_SFF_STS_7's RE_INIT_LL is a self-clearing command bit, so neither can go through
        // writeVerifyBits: a readback differing from what was written is expected here, not a
        // failed write. NOW_IS_OOB's resulting state is logged instead.
        val oobBefore = readPlaByte(connection, PLA_OOB_CTRL)
        if (oobBefore != null) writePlaByte(connection, logDiag, PLA_OOB_CTRL, oobBefore and NOW_IS_OOB.inv())
        val oobAfter = readPlaByte(connection, PLA_OOB_CTRL)

        val sffBefore = readPlaWord(connection, PLA_SFF_STS_7)
        if (sffBefore != null) writePlaWord(connection, logDiag, PLA_SFF_STS_7, sffBefore and MCU_BORW_EN.inv())
        val readyAfterBorw = waitOobLinkListReady(connection)

        val sffForReinit = readPlaWord(connection, PLA_SFF_STS_7)
        if (sffForReinit != null) writePlaWord(connection, logDiag, PLA_SFF_STS_7, sffForReinit or RE_INIT_LL)
        val readyAfterReinit = waitOobLinkListReady(connection)

        val wasOob = oobBefore?.let { (it and NOW_IS_OOB) != 0 }
        val stillOob = oobAfter?.let { (it and NOW_IS_OOB) != 0 }
        logDiag(
            "RTL8153 exit-OOB: PLA_OOB_CTRL ${hex(oobBefore, 2)}->${hex(oobAfter, 2)} (nowIsOob $wasOob->$stillOob) " +
                "PLA_SFF_STS_7 ${hex(sffBefore, 4)} linkListReady(afterBorw=$readyAfterBorw, afterReInit=$readyAfterReinit)"
        )
    }

    /**
     * Tail of r8153_first_init(): switch the TX FIFO to auto mode, reset the MAC once more so
     * the new FIFO mode takes effect, then program the RX/TX share-FIFO credit thresholds. The
     * chip powers up with OOB-appropriate thresholds; the host-mode values are what the kernel
     * always writes before the bulk pipe is used.
     */
    private fun rtlInitFifo(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        val tcr0 = readPlaWord(connection, PLA_TCR0)
        if (tcr0 != null) writePlaWord(connection, logDiag, PLA_TCR0, tcr0 or TCR0_AUTO_FIFO)
        nicReset(connection, logDiag, "post-auto-fifo")
        writePlaDword(connection, logDiag, PLA_RXFIFO_CTRL0, RXFIFO_THR1_NORMAL)
        writePlaWord(connection, logDiag, PLA_RXFIFO_CTRL1, RXFIFO_THR2_NORMAL)
        writePlaWord(connection, logDiag, PLA_RXFIFO_CTRL2, RXFIFO_THR3_NORMAL)
        writePlaDword(connection, logDiag, PLA_TXFIFO_CTRL, TXFIFO_THR_NORMAL2)
        logDiag("RTL8153 FIFO init: PLA_TCR0 ${hex(tcr0, 4)} |= AUTO_FIFO, share-FIFO thresholds written")
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

        // 1.5. Stop acceptance and drain the FIFOs before resetting - see rtlDisable for
        // why this matters on a chip that was already running when we attached.
        rtlDisable(connection, logDiag)

        // 2. Reset the MAC (PLA_CR bit RST) and poll until the device clears it.
        if (!nicReset(connection, logDiag, "initial")) criticalFailures++

        // 2.5. Take the RX path away from the chip's own MCU and rebuild the RX FIFO link
        // list for host ownership - see rtlExitOob, this is the stage whose absence let a
        // fully-verified bring-up still deliver zero packets.
        rtlExitOob(connection, logDiag)

        // 3. Set Rx Max Packet Size (PLA_RMS) to 1536 bytes.
        val rmsOk = writeVerifyWord(connection, logDiag, PLA_RMS, 1536, "RTL8153 PLA_RMS (1536B)")
        if (!rmsOk) criticalFailures++

        // 3.5. Auto-FIFO mode plus host-mode FIFO thresholds. Ordered after PLA_RMS to match
        // the kernel, whose second MAC reset in here doesn't disturb the RMS just written.
        rtlInitFifo(connection, logDiag)

        // 4. Set Multicast Hash Table (PLA_MAR, 8 bytes) to accept all multicast.
        val mar0Ok = writeVerifyDword(connection, logDiag, PLA_MAR, 0xFFFFFFFFL, "RTL8153 PLA_MAR0")
        val mar4Ok = writeVerifyDword(connection, logDiag, PLA_MAR + 4, 0xFFFFFFFFL, "RTL8153 PLA_MAR4")
        if (!mar0Ok || !mar4Ok) criticalFailures++

        // 5. Configure PLA_RCR: AAP|APM|AM|AB = accept everything (promiscuous sniffing).
        // Read-modify-write, not a blind absolute value - see writeVerifyDwordBits for why.
        val rcrOk = writeVerifyDwordBits(connection, logDiag, PLA_RCR, setMask = RCR_ACCEPT_ALL.toLong(), clearMask = 0L, label = "RTL8153 PLA_RCR (accept-all 0x0F)")
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

        // 6.5. Leave RXDY_GATED_EN *set*, so the MAC is fully configured but still holding
        // frames off the FIFO; [startRx] clears it once the bulk reader is attached. This
        // mirrors the kernel, which ungates in rtl_enable() from the open path rather than
        // during init. The MAC resets above clear this bit, so re-assert it explicitly
        // rather than relying on the one rtlDisable set.
        val rxdyGateOk = writeVerifyBits(connection, logDiag, PLA_MISC_1, setMask = RXDY_GATED_EN, clearMask = 0, label = "RTL8153 PLA_MISC_1 RXDY_GATED_EN hold (RX released in startRx)")
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
            // Reported for status only - RX intentionally stays gated regardless of link
            // state until startRx runs.
            decodeAndLogLinkStatus(linkByte, logDiag)
        } else {
            logDiag("RTL8153 PHY Link Status (0xE908): read failed - link state unknown")
        }

        if (criticalFailures > 0) {
            logDiag("RTL8153 vendor bring-up had $criticalFailures critical register failure(s). Interface is likely not actually owned by us (kernel driver still bound, or wrong configuration active).")
        }
        return criticalFailures == 0
    }

    /**
     * The kernel's rtl_enable(): reset the packet filter, assert TE/RE, then release the
     * RX gate - in that order, with the ungate last. [bringUp] deliberately stops short of
     * the ungate, so this is what actually starts frames flowing.
     */
    private fun rtlEnableRx(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phase: String) {
        // PLA_CRWECR's readback doesn't reliably echo what was written (see bringUp) -
        // written and moved past without retrying, same as the attach-time bring-up.
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_CONFIG)
        val fmcClearOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = 0, clearMask = FMC_FCR_MCU_EN, label = "RTL8153 $phase PLA_FMC clear FCR_MCU_EN")
        val fmcSetOk = writeVerifyBits(connection, logDiag, PLA_FMC, setMask = FMC_FCR_MCU_EN, clearMask = 0, label = "RTL8153 $phase PLA_FMC set FCR_MCU_EN")
        val crOk = writeVerifyBits(connection, logDiag, PLA_CR, setMask = CR_TE or CR_RE, clearMask = 0, label = "RTL8153 $phase PLA_CR (Rx/Tx Enable)")
        val ungateOk = writeVerifyBits(connection, logDiag, PLA_MISC_1, setMask = 0, clearMask = RXDY_GATED_EN, label = "RTL8153 $phase PLA_MISC_1 RXDY_GATED_EN clear")
        writePlaByte(connection, logDiag, PLA_CRWECR, CRWECR_NORMAL)
        logDiag("RTL8153 $phase RX enable summary: FMC(clear=$fmcClearOk,set=$fmcSetOk) CR=$crOk ungate=$ungateOk")
    }

    /** Releases the RX gate [bringUp] left set, now that the bulk reader is attached. */
    override fun startRx(connection: UsbDeviceConnection, logDiag: (String) -> Unit) =
        rtlEnableRx(connection, logDiag, "start-rx")

    /**
     * Re-runs the same enable sequence on a confirmed down->up transition mid-capture. The
     * chip re-asserts RXDY_GATED_EN itself when the link drops, so a flap needs the ungate
     * repeated or RX silently stays blocked after the link returns. Safe to ungate here
     * (unlike during bring-up) because the bulk read loop is already running by this point.
     */
    override fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) =
        rtlEnableRx(connection, logDiag, "link-up")

    override fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean? {
        val phyByte = readPlaPhyStatus(connection) ?: return null
        return decodeAndLogLinkStatus(phyByte, logDiag).up
    }

    override fun readLinkStatus(connection: UsbDeviceConnection, logDiag: (String) -> Unit): LinkStatus? {
        val phyByte = readPlaPhyStatus(connection) ?: return null
        return decodeAndLogLinkStatus(phyByte, logDiag)
    }
}
