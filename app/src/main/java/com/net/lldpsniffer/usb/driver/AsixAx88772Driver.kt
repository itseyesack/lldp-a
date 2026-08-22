package com.net.lldpsniffer.usb.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * ASIX AX88772/AX88772A/AX88772B vendor-mode bring-up. This is the chip inside Apple's
 * A1277 "Apple USB Ethernet Adapter" (rebranded under Apple's own VID/PID), confirmed via
 * `journalctl -k`: `asix 1-12:1.0 ... PHY driver [Asix Electronics AX88772A]`. Same
 * fully-vendor-class USB descriptor shape as the AX88179 (no CDC-ECM fallback).
 *
 * Register map and init sequence per the Linux kernel driver
 * (drivers/net/usb/asix.h, asix_common.c, asix_devices.c's ax88772a_hw_reset()):
 * control transfer is bRequest=cmd, wValue=value, wIndex=index, direction
 * USB_DIR_IN|OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE - same convention as AX88179, but a
 * much older/simpler command set (single flat register space, no AX_ACCESS_MAC/PHY
 * indirection). PHY register access instead goes through a software-MII handshake:
 * SET_SW_MII, poll STATMNGSTS_REG for AX_HOST_EN, then READ/WRITE_MII_REG, then
 * SET_HW_MII to hand control back to the chip's own hardware MII state machine.
 */
class AsixAx88772Driver : VendorAdapterDriver {

    companion object {
        // Real (non-rebranded) ASIX VID/PID for the AX88772 family.
        const val ASIX_VID = 0x0B95
        const val AX88772_PID = 0x1720

        // Apple's rebrand of this chip inside the A1277 "Apple USB Ethernet Adapter".
        const val APPLE_VID = 0x05AC
        const val APPLE_A1277_PID = 0x1402

        private const val REQUEST_TYPE_VENDOR_OUT = 0x40
        private const val REQUEST_TYPE_VENDOR_IN = 0xC0

        private const val AX_CMD_SET_SW_MII = 0x06
        private const val AX_CMD_READ_MII_REG = 0x07
        private const val AX_CMD_WRITE_MII_REG = 0x08
        private const val AX_CMD_STATMNGSTS_REG = 0x09
        private const val AX_CMD_SET_HW_MII = 0x0a
        private const val AX_CMD_WRITE_GPIOS = 0x1f
        private const val AX_CMD_READ_RX_CTL = 0x0f
        private const val AX_CMD_WRITE_RX_CTL = 0x10
        private const val AX_CMD_WRITE_IPG0 = 0x12
        private const val AX_CMD_READ_PHY_ID = 0x19
        private const val AX_CMD_READ_MEDIUM_STATUS = 0x1a
        private const val AX_CMD_WRITE_MEDIUM_MODE = 0x1b
        private const val AX_CMD_SW_RESET = 0x20
        private const val AX_CMD_SW_PHY_SELECT = 0x22

        private const val AX_HOST_EN = 0x01
        private const val HOST_EN_RETRIES = 30

        private const val AX_GPIO_RSE = 0x80

        // priv->embd_phy(=1) | AX_PHYSEL_SSEN - this device always has an embedded PHY.
        private const val AX_PHY_SELECT_EMBEDDED_SSEN = 0x11

        private const val AX_SWRESET_CLEAR = 0x00
        private const val AX_SWRESET_IPRL = 0x20
        private const val AX_SWRESET_IPPD = 0x40

        private const val AX88772_IPG0_DEFAULT = 0x15
        private const val AX88772_IPG1_DEFAULT = 0x0c
        private const val AX88772_IPG2_DEFAULT = 0x12

        private const val AX_RX_CTL_SO = 0x0080
        private const val AX_RX_CTL_AMALL = 0x0002
        private const val AX_RX_CTL_AB = 0x0008
        private const val AX_RX_CTL_PRO = 0x0001

        private const val AX_MEDIUM_FD = 0x0002
        private const val AX_MEDIUM_AC = 0x0004
        private const val AX_MEDIUM_RE = 0x0100
        private const val AX_MEDIUM_PS = 0x0200

        private const val MII_BMCR = 0x00
        private const val BMCR_ANRESTART = 0x0200
        private const val BMCR_ANENABLE = 0x1000

        private const val MII_BMSR = 0x01
        private const val BMSR_LSTATUS = 0x0004
    }

    override val name = "ASIX AX88772/AX88772A/AX88772B"

    override fun matches(device: UsbDevice): Boolean {
        return (device.vendorId == ASIX_VID && device.productId == AX88772_PID) ||
            (device.vendorId == APPLE_VID && device.productId == APPLE_A1277_PID)
    }

    private fun readCmd(connection: UsbDeviceConnection, logDiag: (String) -> Unit, cmd: Int, value: Int, index: Int, size: Int): ByteArray? {
        val buf = ByteArray(size)
        val n = try {
            connection.controlTransfer(REQUEST_TYPE_VENDOR_IN, cmd, value, index, buf, size, 1000)
        } catch (e: Exception) {
            logDiag("AX88772 read cmd=0x${String.format("%02X", cmd)} value=0x${String.format("%04X", value)} index=0x${String.format("%04X", index)} threw: ${e.message}")
            -1
        }
        return if (n >= size) buf else null
    }

    private fun writeCmd(connection: UsbDeviceConnection, logDiag: (String) -> Unit, cmd: Int, value: Int, index: Int, data: ByteArray = ByteArray(0)): Int {
        return try {
            connection.controlTransfer(REQUEST_TYPE_VENDOR_OUT, cmd, value, index, data, data.size, 1000)
        } catch (e: Exception) {
            logDiag("AX88772 write cmd=0x${String.format("%02X", cmd)} value=0x${String.format("%04X", value)} index=0x${String.format("%04X", index)} threw: ${e.message}")
            -1
        }
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun toLe16(bytes: ByteArray) = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)

    private fun swReset(connection: UsbDeviceConnection, logDiag: (String) -> Unit, flags: Int): Int =
        writeCmd(connection, logDiag, AX_CMD_SW_RESET, flags, 0)

    /** Software-MII handshake: hand MDIO control to the host and wait for AX_HOST_EN. */
    private fun checkHostEnable(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean {
        repeat(HOST_EN_RETRIES) {
            writeCmd(connection, logDiag, AX_CMD_SET_SW_MII, 0, 0)
            Thread.sleep(1)
            val smsr = readCmd(connection, logDiag, AX_CMD_STATMNGSTS_REG, 0, 0, 1)
            if (smsr != null && (smsr[0].toInt() and AX_HOST_EN) != 0) return true
        }
        return false
    }

    private fun mdioRead(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phyId: Int, reg: Int): Int? {
        if (!checkHostEnable(connection, logDiag)) {
            logDiag("AX88772 MDIO: host-enable handshake timed out")
            return null
        }
        val result = readCmd(connection, logDiag, AX_CMD_READ_MII_REG, phyId, reg, 2)?.let { toLe16(it) }
        writeCmd(connection, logDiag, AX_CMD_SET_HW_MII, 0, 0)
        return result
    }

    private fun mdioWrite(connection: UsbDeviceConnection, logDiag: (String) -> Unit, phyId: Int, reg: Int, value: Int): Boolean {
        if (!checkHostEnable(connection, logDiag)) {
            logDiag("AX88772 MDIO: host-enable handshake timed out")
            return false
        }
        val ok = writeCmd(connection, logDiag, AX_CMD_WRITE_MII_REG, phyId, reg, le16(value)) >= 0
        writeCmd(connection, logDiag, AX_CMD_SET_HW_MII, 0, 0)
        return ok
    }

    private fun embeddedPhyId(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Int {
        val buf = readCmd(connection, logDiag, AX_CMD_READ_PHY_ID, 0, 0, 2)
        val phyId = buf?.get(1)?.toInt()?.and(0x1f) ?: 0x10
        logDiag("AX88772 embedded PHY id: 0x${String.format("%02X", phyId)}")
        return phyId
    }

    /**
     * Init sequence per ax88772a_hw_reset(): GPIO reload, select embedded PHY, staged
     * software reset (power-down -> reload -> clear -> reload), inter-packet-gap
     * defaults, then RX_CTL/medium-mode bring-up and autonegotiation restart. The
     * PHY-register default-value restore step (AX88772A_PHY14H/15H/16H) is a cosmetic
     * EEE/WoL tuning fixup and is skipped - it doesn't affect whether frames reach the
     * host. MAC address rewrite is also skipped: this is a passive receive-only sniffer,
     * so the device's outgoing source MAC is irrelevant.
     */
    override fun bringUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean {
        var criticalFailures = 0

        logDiag("Configuring AX88772 Vendor Registers...")

        val gpio = writeCmd(connection, logDiag, AX_CMD_WRITE_GPIOS, AX_GPIO_RSE, 0)
        logDiag("AX88772 GPIO reload (RSE): $gpio")
        Thread.sleep(5)

        val phyId = embeddedPhyId(connection, logDiag)

        val phySelect = writeCmd(connection, logDiag, AX_CMD_SW_PHY_SELECT, AX_PHY_SELECT_EMBEDDED_SSEN, 0)
        logDiag("AX88772 SW_PHY_SELECT (embedded): $phySelect")
        if (phySelect < 0) criticalFailures++
        Thread.sleep(10)

        swReset(connection, logDiag, AX_SWRESET_IPPD or AX_SWRESET_IPRL)
        Thread.sleep(10)
        swReset(connection, logDiag, AX_SWRESET_IPRL)
        Thread.sleep(160)
        swReset(connection, logDiag, AX_SWRESET_CLEAR)
        val reloadRes = swReset(connection, logDiag, AX_SWRESET_IPRL)
        logDiag("AX88772 staged SW_RESET sequence complete: $reloadRes")
        if (reloadRes < 0) criticalFailures++
        Thread.sleep(200)

        val ipgValue = AX88772_IPG0_DEFAULT or AX88772_IPG1_DEFAULT
        val ipgRes = writeCmd(connection, logDiag, AX_CMD_WRITE_IPG0, ipgValue, AX88772_IPG2_DEFAULT)
        logDiag("AX88772 IPG0/1/2 defaults: $ipgRes")

        val rxCtlValue = AX_RX_CTL_SO or AX_RX_CTL_PRO or AX_RX_CTL_AMALL or AX_RX_CTL_AB
        val rxCtlRes = writeCmd(connection, logDiag, AX_CMD_WRITE_RX_CTL, rxCtlValue, 0)
        val rxCtlReadback = readCmd(connection, logDiag, AX_CMD_READ_RX_CTL, 0, 0, 2)?.let { toLe16(it) }
        logDiag("AX88772 RX_CTL (start RX, accept-all): write=$rxCtlRes readback=0x${rxCtlReadback?.let { String.format("%04X", it) } ?: "read failed"}")
        if (rxCtlRes < 0) criticalFailures++

        val mediumValue = AX_MEDIUM_FD or AX_MEDIUM_PS or AX_MEDIUM_AC or AX_MEDIUM_RE
        val mediumRes = writeCmd(connection, logDiag, AX_CMD_WRITE_MEDIUM_MODE, mediumValue, 0)
        val mediumReadback = readCmd(connection, logDiag, AX_CMD_READ_MEDIUM_STATUS, 0, 0, 2)?.let { toLe16(it) }
        logDiag("AX88772 Medium Mode (full-duplex, receive-enable): write=$mediumRes readback=0x${mediumReadback?.let { String.format("%04X", it) } ?: "read failed"}")
        if (mediumRes < 0) criticalFailures++

        val bmcr = mdioRead(connection, logDiag, phyId, MII_BMCR) ?: 0
        val nwayOk = mdioWrite(connection, logDiag, phyId, MII_BMCR, bmcr or BMCR_ANENABLE or BMCR_ANRESTART)
        logDiag("AX88772 MII BMCR restart autoneg (from 0x${String.format("%04X", bmcr)}): $nwayOk")

        var linkStatus: Boolean? = null
        for (attempt in 1..30) {
            linkStatus = pollLinkUp(connection, logDiag)
            if (linkStatus == true) break
            Thread.sleep(100)
        }
        if (linkStatus == null) {
            logDiag("AX88772 MII BMSR: read failed - link state unknown")
        }

        if (criticalFailures > 0) {
            logDiag("AX88772 vendor bring-up had $criticalFailures critical register failure(s). Interface is likely not actually owned by us.")
        }
        return criticalFailures == 0
    }

    /**
     * Mirrors AsixAx88179Driver's approach: re-assert receive-enable rather than
     * repeating the whole staged reset sequence on every link flap.
     */
    override fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {
        val medium = readCmd(connection, logDiag, AX_CMD_READ_MEDIUM_STATUS, 0, 0, 2)?.let { toLe16(it) } ?: return
        if (medium and AX_MEDIUM_RE == 0) {
            val res = writeCmd(connection, logDiag, AX_CMD_WRITE_MEDIUM_MODE, medium or AX_MEDIUM_RE, 0)
            logDiag("AX88772 link-up re-assert RE: $res")
        }
    }

    override fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean? {
        val phyId = embeddedPhyId(connection, logDiag)
        // MII_BMSR's link-status bit is latched low on a down transition - read twice so
        // the second read reflects the current state, per standard MII semantics.
        mdioRead(connection, logDiag, phyId, MII_BMSR)
        val bmsr = mdioRead(connection, logDiag, phyId, MII_BMSR) ?: return null
        val linkUp = (bmsr and BMSR_LSTATUS) != 0
        logDiag("AX88772 MII BMSR: 0x${String.format("%04X", bmsr)} (Link up: $linkUp)")
        return linkUp
    }
}
