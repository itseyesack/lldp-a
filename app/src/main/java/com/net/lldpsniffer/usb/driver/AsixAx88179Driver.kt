package com.net.lldpsniffer.usb.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * ASIX AX88179/AX88178A vendor-mode bring-up. Unlike the RTL8153, this chip has no
 * CDC-ECM configuration at all (entirely Vendor Specific Class at both device and
 * interface level), so it always goes through the vendor bring-up path.
 *
 * Register map and init sequence per the Linux kernel driver
 * (drivers/net/usb/ax88179_178a.c, functions __ax88179_read_cmd/__ax88179_write_cmd and
 * ax88179_reset): control transfer is bRequest=cmd, wValue=value, wIndex=index,
 * direction USB_DIR_IN|OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE. For AX_ACCESS_MAC,
 * value=register address, index=size in bytes. For AX_ACCESS_PHY, value=PHY id,
 * index=PHY register number. Multi-byte fields are little-endian.
 */
class AsixAx88179Driver : VendorAdapterDriver {

    companion object {
        const val ASIX_VID = 0x0B95
        const val AX88179_PID = 0x1790
        const val AX88178A_PID = 0x178A

        private const val REQUEST_TYPE_VENDOR_OUT = 0x40
        private const val REQUEST_TYPE_VENDOR_IN = 0xC0

        private const val AX_ACCESS_MAC = 0x01
        private const val AX_ACCESS_PHY = 0x02

        private const val AX88179_PHY_ID = 0x03

        private const val AX_RX_CTL = 0x0b
        private const val AX_RX_CTL_DROPCRCERR = 0x0100
        private const val AX_RX_CTL_IPE = 0x0200
        private const val AX_RX_CTL_START = 0x0080
        private const val AX_RX_CTL_AP = 0x0020
        private const val AX_RX_CTL_AM = 0x0010
        private const val AX_RX_CTL_AB = 0x0008
        private const val AX_RX_CTL_AMALL = 0x0002

        private const val AX_MEDIUM_STATUS_MODE = 0x22
        private const val AX_MEDIUM_GIGAMODE = 0x01
        private const val AX_MEDIUM_FULL_DUPLEX = 0x02
        private const val AX_MEDIUM_RXFLOW_CTRLEN = 0x10
        private const val AX_MEDIUM_TXFLOW_CTRLEN = 0x20
        private const val AX_MEDIUM_RECEIVE_EN = 0x100

        private const val AX_PHYPWR_RSTCTL = 0x26
        private const val AX_PHYPWR_RSTCTL_IPRL = 0x0020

        private const val AX_RX_BULKIN_QCTRL = 0x2e
        private const val AX_CLK_SELECT = 0x33
        private const val AX_CLK_SELECT_BCS = 0x01
        private const val AX_CLK_SELECT_ACS = 0x02

        private const val AX_RXCOE_CTL = 0x34
        private const val AX_TXCOE_CTL = 0x35
        private const val AX_COE_IP = 0x01
        private const val AX_COE_TCP = 0x02
        private const val AX_COE_UDP = 0x04
        private const val AX_COE_TCPV6 = 0x20
        private const val AX_COE_UDPV6 = 0x40

        private const val AX_PAUSE_WATERLVL_LOW = 0x54
        private const val AX_PAUSE_WATERLVL_HIGH = 0x55

        // {ctrl, timer_l, timer_h, size, ifg} - default row from AX88179_BULKIN_SIZE[0]
        private val RX_BULKIN_QCTRL_DEFAULT = byteArrayOf(7, 0x4f, 0, 0x12, 0xff.toByte())

        private const val GMII_PHY_PHYSR = 0x11
        private const val GMII_PHY_PHYSR_GIGA = 0x8000
        private const val GMII_PHY_PHYSR_100 = 0x4000
        private const val GMII_PHY_PHYSR_FULL = 0x2000
        private const val GMII_PHY_PHYSR_LINK = 0x400

        private const val MII_BMCR = 0x00
        private const val BMCR_ANRESTART = 0x0200
        private const val BMCR_ANENABLE = 0x1000
    }

    override val name = "ASIX AX88179/AX88178A"

    override val maxLinkMbps = 1000

    override fun matches(device: UsbDevice): Boolean {
        return device.vendorId == ASIX_VID &&
            device.productId in setOf(AX88179_PID, AX88178A_PID)
    }

    private fun readCmd(connection: UsbDeviceConnection, logDiag: (String) -> Unit, cmd: Int, value: Int, index: Int, size: Int): ByteArray? {
        val buf = ByteArray(size)
        val n = try {
            connection.controlTransfer(REQUEST_TYPE_VENDOR_IN, cmd, value, index, buf, size, 1000)
        } catch (e: Exception) {
            logDiag("AX88179 read cmd=0x${String.format("%02X", cmd)} value=0x${String.format("%04X", value)} index=0x${String.format("%04X", index)} threw: ${e.message}")
            -1
        }
        return if (n >= size) buf else null
    }

    private fun writeCmd(connection: UsbDeviceConnection, logDiag: (String) -> Unit, cmd: Int, value: Int, index: Int, data: ByteArray): Int {
        return try {
            connection.controlTransfer(REQUEST_TYPE_VENDOR_OUT, cmd, value, index, data, data.size, 1000)
        } catch (e: Exception) {
            logDiag("AX88179 write cmd=0x${String.format("%02X", cmd)} value=0x${String.format("%04X", value)} index=0x${String.format("%04X", index)} threw: ${e.message}")
            -1
        }
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun toLe16(bytes: ByteArray) = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)

    private fun writeMac(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, data: ByteArray): Int =
        writeCmd(connection, logDiag, AX_ACCESS_MAC, reg, data.size, data)

    private fun readMac(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, size: Int): ByteArray? =
        readCmd(connection, logDiag, AX_ACCESS_MAC, reg, size, size)

    private fun writePhy(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phyReg: Int, value: Int): Int =
        writeCmd(connection, logDiag, AX_ACCESS_PHY, AX88179_PHY_ID, phyReg, le16(value))

    private fun readPhy(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phyReg: Int): Int? =
        readCmd(connection, logDiag, AX_ACCESS_PHY, AX88179_PHY_ID, phyReg, 2)?.let { toLe16(it) }

    /**
     * Writes a 16-bit MAC register and reads it back to confirm the chip actually latched it -
     * mirrors the same fix applied to RealtekRtl8153Driver: a successful controlTransfer only
     * proves the USB host controller ACK'd the OUT packet, not that the chip applied it. Only
     * used for RX_CTL/MEDIUM_STATUS_MODE, the two registers that gate whether the MAC actually
     * delivers frames; the earlier PHY-power/clock/queue-tuning steps have no separate
     * "did it take" signal worth polling here.
     *
     * A hard transfer failure (the write itself erroring, not just a value mismatch) means
     * the device isn't responding right now - retrying immediately just adds traffic to an
     * already-unresponsive bus, so that case fails fast instead of exhausting all attempts.
     */
    private fun writeVerifyMacValue16(connection: UsbDeviceConnection, logDiag: (String) -> Unit, reg: Int, value: Int, label: String, maxAttempts: Int = 3): Boolean {
        val target = value and 0xFFFF
        for (attempt in 1..maxAttempts) {
            val res = writeMac(connection, logDiag, reg, le16(target))
            if (res < 0) {
                logDiag("$label (attempt $attempt/$maxAttempts): write transfer failed (res=$res) - device unresponsive, not retrying")
                return false
            }
            val readback = readMac(connection, logDiag, reg, 2)?.let { toLe16(it) }
            val ok = readback == target
            logDiag("$label (attempt $attempt/$maxAttempts): write=$res readback=${readback?.let { "0x" + String.format("%04X", it) } ?: "read failed"} target=0x${String.format("%04X", target)} ${if (ok) "OK" else "MISMATCH"}")
            if (ok) return true
            if (attempt < maxAttempts) Thread.sleep(10)
        }
        return false
    }

    /**
     * Init sequence per ax88179_reset(): PHY power cycle, clock select, RX bulk-in queue
     * tuning, pause watermarks, checksum offload, start RX, set medium mode, restart
     * autonegotiation. LED/EEE/monitor-mode steps are cosmetic/WoL-only and are skipped -
     * they don't affect whether frames reach the host.
     */
    override fun bringUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean {
        var criticalFailures = 0

        logDiag("Configuring AX88179 Vendor Registers...")

        val phyPwrOff = writeMac(connection, logDiag, AX_PHYPWR_RSTCTL, le16(0))
        logDiag("AX88179 PHYPWR_RSTCTL clear: $phyPwrOff")

        val phyPwrOn = writeMac(connection, logDiag, AX_PHYPWR_RSTCTL, le16(AX_PHYPWR_RSTCTL_IPRL))
        logDiag("AX88179 PHYPWR_RSTCTL IPRL (power up PHY): $phyPwrOn")
        if (phyPwrOn < 0) criticalFailures++
        Thread.sleep(500)

        val clkSelect = writeMac(connection, logDiag, AX_CLK_SELECT, byteArrayOf((AX_CLK_SELECT_ACS or AX_CLK_SELECT_BCS).toByte()))
        logDiag("AX88179 CLK_SELECT: $clkSelect")
        Thread.sleep(200)

        val bulkinQctrl = writeMac(connection, logDiag, AX_RX_BULKIN_QCTRL, RX_BULKIN_QCTRL_DEFAULT)
        logDiag("AX88179 RX_BULKIN_QCTRL: $bulkinQctrl")

        val pauseLow = writeMac(connection, logDiag, AX_PAUSE_WATERLVL_LOW, byteArrayOf(0x34))
        val pauseHigh = writeMac(connection, logDiag, AX_PAUSE_WATERLVL_HIGH, byteArrayOf(0x52))
        logDiag("AX88179 pause watermarks: low=$pauseLow high=$pauseHigh")

        val coeMask = (AX_COE_IP or AX_COE_TCP or AX_COE_UDP or AX_COE_TCPV6 or AX_COE_UDPV6).toByte()
        val rxCoe = writeMac(connection, logDiag, AX_RXCOE_CTL, byteArrayOf(coeMask))
        val txCoe = writeMac(connection, logDiag, AX_TXCOE_CTL, byteArrayOf(coeMask))
        logDiag("AX88179 checksum offload enable: rx=$rxCoe tx=$txCoe")

        val rxCtlValue = AX_RX_CTL_DROPCRCERR or AX_RX_CTL_IPE or AX_RX_CTL_START or
            AX_RX_CTL_AP or AX_RX_CTL_AMALL or AX_RX_CTL_AB
        val rxCtlOk = writeVerifyMacValue16(connection, logDiag, AX_RX_CTL, rxCtlValue, "AX88179 RX_CTL (start RX, accept-all)")
        if (!rxCtlOk) criticalFailures++

        val mediumValue = AX_MEDIUM_RECEIVE_EN or AX_MEDIUM_TXFLOW_CTRLEN or
            AX_MEDIUM_RXFLOW_CTRLEN or AX_MEDIUM_FULL_DUPLEX or AX_MEDIUM_GIGAMODE
        val mediumOk = writeVerifyMacValue16(connection, logDiag, AX_MEDIUM_STATUS_MODE, mediumValue, "AX88179 MEDIUM_STATUS_MODE (enable receive/giga/full-duplex)")
        if (!mediumOk) criticalFailures++

        // Restart autonegotiation: standard MII BMCR, read-modify-write ANENABLE|ANRESTART.
        val bmcr = readPhy(connection, logDiag, MII_BMCR) ?: 0
        val nwayRes = writePhy(connection, logDiag, MII_BMCR, bmcr or BMCR_ANENABLE or BMCR_ANRESTART)
        logDiag("AX88179 MII BMCR restart autoneg (from 0x${String.format("%04X", bmcr)}): $nwayRes")

        var linkStatus: Boolean? = null
        for (attempt in 1..30) {
            linkStatus = pollLinkUp(connection, logDiag)
            if (linkStatus == true) break
            Thread.sleep(100)
        }
        if (linkStatus == null) {
            logDiag("AX88179 GMII_PHY_PHYSR: read failed - link state unknown")
        }

        if (criticalFailures > 0) {
            logDiag("AX88179 vendor bring-up had $criticalFailures critical register failure(s). Interface is likely not actually owned by us.")
        }
        return criticalFailures == 0
    }

    /**
     * The kernel re-checks GMII_PHY_PHYSR and re-asserts AX_MEDIUM_RECEIVE_EN on
     * link-reset (ax88179_net_reset) rather than re-running the whole bring-up sequence -
     * mirror that here instead of repeating PHY power-cycle/clock-select on every flap.
     */
    override fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        val medium = readMac(connection, logDiag, AX_MEDIUM_STATUS_MODE, 2)?.let { toLe16(it) } ?: return
        if (medium and AX_MEDIUM_RECEIVE_EN == 0) {
            writeVerifyMacValue16(connection, logDiag, AX_MEDIUM_STATUS_MODE, medium or AX_MEDIUM_RECEIVE_EN, "AX88179 link-up re-assert RECEIVE_EN")
        }
    }

    private fun decodeAndLogLinkStatus(physr: Int, logDiag: (String) -> Unit): LinkStatus {
        val linkUp = (physr and GMII_PHY_PHYSR_LINK) != 0
        val speedMbps = when {
            physr and GMII_PHY_PHYSR_GIGA != 0 -> 1000
            physr and GMII_PHY_PHYSR_100 != 0 -> 100
            linkUp -> 10
            else -> null
        }
        val duplex = if (physr and GMII_PHY_PHYSR_FULL != 0) "Full" else "Half"
        logDiag("AX88179 GMII_PHY_PHYSR: 0x${String.format("%04X", physr)} (Link up: $linkUp, ${speedMbps ?: "unknown"}Mbps, $duplex-duplex)")
        return LinkStatus(up = linkUp, speedMbps = speedMbps, duplex = duplex)
    }

    override fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean? {
        val physr = readPhy(connection, logDiag, GMII_PHY_PHYSR) ?: return null
        return decodeAndLogLinkStatus(physr, logDiag).up
    }

    override fun readLinkStatus(connection: UsbDeviceConnection, logDiag: (String) -> Unit): LinkStatus? {
        val physr = readPhy(connection, logDiag, GMII_PHY_PHYSR) ?: return null
        return decodeAndLogLinkStatus(physr, logDiag)
    }
}
