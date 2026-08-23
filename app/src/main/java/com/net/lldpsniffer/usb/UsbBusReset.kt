package com.net.lldpsniffer.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * Sends a real USB port reset (USBDEVFS_RESET) via a JNI ioctl on the connection's own file
 * descriptor. The fd is already permission-granted by UsbManager, so this needs no root - it's
 * a last-resort recovery step for when a plain close()+reopen() of a UsbDeviceConnection isn't
 * enough to unwedge a dead adapter, which has been observed on hardware where every register
 * write kept failing even immediately after reopening with no intervening physical replug.
 */
object UsbBusReset {
    private const val TAG = "UsbBusReset"

    private val nativeLibAvailable: Boolean = try {
        System.loadLibrary("usbreset")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "libusbreset.so unavailable - bus reset disabled, falling back to close+reopen only", e)
        false
    }

    @JvmStatic
    private external fun nativeReset(fd: Int): Int

    /** @return true if the port reset ioctl succeeded. */
    fun reset(connection: UsbDeviceConnection): Boolean {
        if (!nativeLibAvailable) return false
        val fd = connection.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "reset() called on a connection with no valid file descriptor")
            return false
        }
        val result = nativeReset(fd)
        if (result != 0) {
            Log.e(TAG, "USBDEVFS_RESET ioctl failed on fd $fd (errno=${-result})")
        }
        return result == 0
    }
}
