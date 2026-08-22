package com.net.lldpsniffer.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.net.lldpsniffer.MainActivity
import com.net.lldpsniffer.R
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.usb.AdapterInfo
import com.net.lldpsniffer.usb.PeerDevice
import com.net.lldpsniffer.usb.UsbConnectionManager
import com.net.lldpsniffer.usb.UsbConnectionState
import com.net.lldpsniffer.usb.driver.LinkStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class PacketSnifferService : Service() {

    companion object {
        private const val TAG = "PacketSnifferService"
        private const val CHANNEL_ID = "lldp_sniffer_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.net.lldpsniffer.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.net.lldpsniffer.action.STOP_CAPTURE"
        const val EXTRA_DEVICE = "extra_usb_device"

        fun startService(context: Context, device: UsbDevice? = null) {
            val intent = Intent(context, PacketSnifferService::class.java).apply {
                action = ACTION_START_CAPTURE
                device?.let { putExtra(EXTRA_DEVICE, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PacketSnifferService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PacketSnifferService = this@PacketSnifferService
    }

    private val binder = LocalBinder()
    lateinit var usbConnectionManager: UsbConnectionManager
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    val connectionState: StateFlow<UsbConnectionState>
        get() = usbConnectionManager.connectionState

    val capturedPackets: SharedFlow<CapturedPacket>
        get() = usbConnectionManager.capturedPackets

    val diagnosticLogs: StateFlow<List<String>>
        get() = usbConnectionManager.diagnosticLogs

    val linkState: StateFlow<Boolean?>
        get() = usbConnectionManager.linkState

    val linkStatus: StateFlow<LinkStatus?>
        get() = usbConnectionManager.linkStatus

    val adapterInfo: StateFlow<AdapterInfo?>
        get() = usbConnectionManager.adapterInfo

    val peerDevices: StateFlow<List<PeerDevice>>
        get() = usbConnectionManager.peerDevices

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PacketSnifferService onCreate")
        usbConnectionManager = UsbConnectionManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_CAPTURE -> {
                acquireWakeLock()
                startForegroundNotification("Initializing USB packet capture...")

                if (usbConnectionManager.connectionState.value is UsbConnectionState.Connected) {
                    Log.i(TAG, "Service already connected and capturing. Skipping intent start.")
                    return START_STICKY
                }

                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DEVICE)
                } ?: usbConnectionManager.findSupportedDevice()

                if (device != null) {
                    if (usbConnectionManager.hasPermission(device)) {
                        usbConnectionManager.startCapture(device)
                        updateNotification("Sniffing LLDP/CDP frames on ${device.deviceName}...")
                    } else {
                        usbConnectionManager.updateState(UsbConnectionState.PermissionRequested)
                        updateNotification("Awaiting USB permission...")
                    }
                } else {
                    usbConnectionManager.updateState(UsbConnectionState.Disconnected)
                    updateNotification("No compatible USB Ethernet adapter detected.")
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_STICKY
    }

    fun startCaptureForDevice(device: UsbDevice) {
        acquireWakeLock()
        startForegroundNotification("Sniffing packets on ${device.deviceName}...")
        usbConnectionManager.startCapture(device)
    }

    fun stopCapture() {
        Log.i(TAG, "stopCapture called")
        usbConnectionManager.stopCapture()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "L2PacketSniffer::CaptureWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L /*10 minutes timeout safety*/)
            }
            Log.d(TAG, "Acquired WakeLock")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Released WakeLock")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Layer 2 Packet Sniffer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {

        stopCapture()
        super.onDestroy()
    }
}
