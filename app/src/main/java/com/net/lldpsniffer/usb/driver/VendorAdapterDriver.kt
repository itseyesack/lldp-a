package com.net.lldpsniffer.usb.driver

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Chip-specific bring-up for a USB Ethernet adapter running in vendor mode (i.e. no
 * CDC-ECM configuration to fall back on). Each chipset gets its own register map and
 * framing quirks; UsbConnectionManager only ever depends on this interface so a new
 * adapter model is added by writing one new implementation and registering it, without
 * touching the capture/ingestion pipeline.
 */
/** Live link details beyond up/down, for display in an adapter/link info panel. */
data class LinkStatus(
    val up: Boolean,
    val speedMbps: Int? = null,
    val duplex: String? = null
)

interface VendorAdapterDriver {
    val name: String

    /** Highest link speed this chipset family can negotiate, for an adapter info panel. */
    val maxLinkMbps: Int

    /** True if this driver knows how to bring up the given device (matched by VID/PID). */
    fun matches(device: UsbDevice): Boolean

    /**
     * Runs the vendor register sequence needed to start the RX path. Returns false if a
     * load-bearing register write failed (device likely still owned by the kernel driver,
     * or the wrong configuration is active).
     */
    fun bringUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean

    /**
     * Unblocks the chip's RX path, called immediately before the bulk IN read loop starts.
     *
     * [bringUp] deliberately leaves RX blocked: it can spend seconds waiting for PHY
     * autonegotiation, and on a chip whose link is already up, frames accepted during that
     * window pile into an RX FIFO nothing is draining yet. Overrunning it wedges the RX DMA
     * on real hardware - registers still verify, the link still reports up, control
     * transfers still work, but no frame ever reaches the host until the device is
     * physically replugged. Enabling RX only once a reader is attached keeps that window at
     * microseconds instead of seconds, matching how the kernel only ungates RX on the
     * carrier-on path alongside submitting its RX URBs.
     */
    fun startRx(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {}

    /** Re-run whatever subset of [bringUp] needs to repeat after a down->up link transition. */
    fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {}

    /**
     * Polls current link state, logging a chip-specific descriptive line via [logDiag].
     * Returns null if the read failed (state unknown), true/false for up/down otherwise.
     */
    fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean?

    /**
     * Polls current link state plus negotiated speed/duplex where the chipset exposes it.
     * Default implementation falls back to [pollLinkUp] with no speed/duplex detail.
     */
    fun readLinkStatus(connection: UsbDeviceConnection, logDiag: (String) -> Unit): LinkStatus? {
        return pollLinkUp(connection, logDiag)?.let { LinkStatus(up = it) }
    }
}
