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
interface VendorAdapterDriver {
    val name: String

    /** True if this driver knows how to bring up the given device (matched by VID/PID). */
    fun matches(device: UsbDevice): Boolean

    /**
     * Runs the vendor register sequence needed to start the RX path. Returns false if a
     * load-bearing register write failed (device likely still owned by the kernel driver,
     * or the wrong configuration is active).
     */
    fun bringUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean

    /** Re-run whatever subset of [bringUp] needs to repeat after a down->up link transition. */
    fun onLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit) {}

    /**
     * Polls current link state, logging a chip-specific descriptive line via [logDiag].
     * Returns null if the read failed (state unknown), true/false for up/down otherwise.
     */
    fun pollLinkUp(connection: UsbDeviceConnection, logDiag: (String) -> Unit): Boolean?
}
