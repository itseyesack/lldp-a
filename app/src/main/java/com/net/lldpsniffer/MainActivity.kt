package com.net.lldpsniffer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.net.lldpsniffer.service.PacketSnifferService
import com.net.lldpsniffer.ui.MainScreen
import com.net.lldpsniffer.ui.theme.AndroidLLDPSnifferTheme
import com.net.lldpsniffer.usb.UsbConnectionState
import com.net.lldpsniffer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.net.lldpsniffer.ACTION_USB_PERMISSION"
    }

    private val viewModel: MainViewModel by viewModels()

    private var packetService: PacketSnifferService? = null
    private var isServiceBound = false
    private var isPermissionPending = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PacketSnifferService.LocalBinder
            val srv = binder.getService()
            packetService = srv
            isServiceBound = true
            viewModel.bindService(srv)
            Log.i(TAG, "Service connected to MainActivity. Checking USB devices...")
            checkAndStartDeviceCapture()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            packetService = null
            viewModel.unbindService()
            Log.i(TAG, "Service disconnected from MainActivity")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "usbReceiver onReceive action: $action")

            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        isPermissionPending = false
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            packetService?.usbConnectionManager?.logDiag("USB Permission granted for device: ${device.deviceName}")
                            startSnifferService(device)
                        } else {
                            packetService?.usbConnectionManager?.logDiag("USB Permission denied for device: ${device?.deviceName}")
                            packetService?.usbConnectionManager?.updateState(UsbConnectionState.PermissionDenied)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    packetService?.usbConnectionManager?.logDiag("USB Device ATTACHED intent received")
                    checkAndStartDeviceCapture()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    packetService?.usbConnectionManager?.logDiag("USB Device DETACHED intent received")
                    packetService?.stopCapture()
                }
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, "Notification permission granted: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check Notification Permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Register USB BroadcastReceiver
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Start & Bind Foreground Service
        PacketSnifferService.startService(this)
        val serviceIntent = Intent(this, PacketSnifferService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            AndroidLLDPSnifferTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermission = { requestDevicePermission() },
                    onStartCapture = { checkAndStartDeviceCapture() },
                    onStopCapture = { packetService?.stopCapture() }
                )
            }
        }

        // Keep the screen on only while the adapter is actively connected AND this
        // Activity is in the foreground - repeatOnLifecycle(STARTED) auto-cancels the
        // collector (and re-evaluates from the current state on the next resume) when
        // the app backgrounds, so this never fights the service's separate capture
        // PARTIAL_WAKE_LOCK, which is CPU-only and intentionally survives backgrounding.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    if (state is UsbConnectionState.Connected) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Safety net: repeatOnLifecycle cancels the collector above on backgrounding,
        // but does not run its else-branch, so the flag could otherwise stay set.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun checkAndStartDeviceCapture() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val srv = packetService
        if (srv == null) {
            Log.w(TAG, "checkAndStartDeviceCapture: packetService is null")
            return
        }

        if (srv.connectionState.value is UsbConnectionState.Connected ||
            srv.connectionState.value is UsbConnectionState.Connecting) {
            Log.i(TAG, "checkAndStartDeviceCapture: Device already connected/connecting. Skipping re-discovery.")
            return
        }

        val device = srv.usbConnectionManager.findSupportedDevice()
        Log.i(TAG, "findSupportedDevice result: ${device?.deviceName ?: "null"} (VID: ${device?.vendorId}, PID: ${device?.productId})")

        if (device == null) {
            srv.usbConnectionManager.updateState(UsbConnectionState.Disconnected)
            return
        }

        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for device: ${device.deviceName}. Starting capture...")
            srv.startCaptureForDevice(device)
        } else {
            Log.i(TAG, "Device found: ${device.deviceName}. Requesting USB permission...")
            srv.usbConnectionManager.updateState(
                UsbConnectionState.DeviceDetected(
                    deviceName = device.deviceName ?: "USB Adapter",
                    vendorId = device.vendorId,
                    productId = device.productId
                )
            )
            requestUsbPermission(device)
        }
    }

    private fun requestDevicePermission() {
        val srv = packetService ?: return
        val device = srv.usbConnectionManager.findSupportedDevice()
        if (device != null) {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (isPermissionPending) {
            Log.d(TAG, "USB permission request already pending. Skipping duplicate request.")
            return
        }
        isPermissionPending = true
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        // Request code keyed to the device so requests for different devices
        // never collide on the same PendingIntent, and re-requests for the
        // same device correctly update (not duplicate) the pending one.
        val permissionIntent = PendingIntent.getBroadcast(
            this, device.deviceId, intent, flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun startSnifferService(device: UsbDevice) {
        PacketSnifferService.startService(this, device)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering usbReceiver", e)
        }
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
