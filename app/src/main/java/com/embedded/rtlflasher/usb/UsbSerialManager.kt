package com.embedded.rtlflasher.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.embedded.rtlflasher.model.UsbConnectionState
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.embedded.rtlflasher.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbSerialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null

    private val _connectionState = MutableStateFlow(UsbConnectionState())
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _rxDataFlow = MutableSharedFlow<ByteArray>(replay = 0)
    val rxDataFlow: SharedFlow<ByteArray> = _rxDataFlow.asSharedFlow()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    openDevice(device)
                } else {
                    Log.w(TAG, "USB Permission denied for device: ${device?.deviceName}")
                }
            } else if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnect()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun scanAndConnect() {
        // Default prober already covers CDC, FTDI, CP210x, CH34x and Prolific.
        val prober = UsbSerialProber.getDefaultProber()
        val availableDrivers = prober.findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            _connectionState.value = UsbConnectionState(false, "No compatible USB-UART bridge detected")
            return
        }

        val driver = availableDrivers.first()
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            usbManager.requestPermission(device, intent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(device) ?: return

        usbConnection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB Device connection")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(usbConnection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort = port

            val chipDesc = identifyChip(device.vendorId, device.productId)
            _connectionState.value = UsbConnectionState(
                isConnected = true,
                deviceName = "${device.productName ?: "USB Serial Device"} (${device.deviceName})",
                chipType = chipDesc,
                vendorId = device.vendorId,
                productId = device.productId
            )
        } catch (e: IOException) {
            Log.e(TAG, "Error configuring serial port", e)
            disconnect()
        }
    }

    private fun identifyChip(vid: Int, pid: Int): String {
        return when (vid) {
            0x10C4 -> "Silicon Labs CP210x"
            0x1A86 -> "WCH CH340 / CH341"
            0x0403 -> "FTDI FT232R"
            0x067B -> "Prolific PL2303"
            else -> "CDC-ACM Standard"
        }
    }

    fun setBaudRate(baudRate: Int) {
        try {
            usbSerialPort?.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.i(TAG, "Baud rate switched to $baudRate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set baud rate", e)
        }
    }

    suspend fun write(data: ByteArray, timeoutMs: Int = 1000) = withContext(Dispatchers.IO) {
        usbSerialPort?.write(data, timeoutMs)
    }

    suspend fun read(buffer: ByteArray, timeoutMs: Int = 1000): Int = withContext(Dispatchers.IO) {
        return@withContext usbSerialPort?.read(buffer, timeoutMs) ?: -1
    }

    suspend fun pulseDtrRtsReset() = withContext(Dispatchers.IO) {
        // Pulse DTR (Puts BOOT PA08 pin to GND) and RTS (Resets CHIP_EN)
        usbSerialPort?.let { port ->
            port.dtr = true
            port.rts = true
            kotlinx.coroutines.delay(100)
            port.rts = false
            kotlinx.coroutines.delay(50)
            port.dtr = false
        }
    }

    fun disconnect() {
        try {
            usbSerialPort?.close()
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial port", e)
        } finally {
            usbSerialPort = null
            usbConnection = null
            _connectionState.value = UsbConnectionState(false, "Disconnected")
        }
    }

    fun onDestroy() {
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {}
        disconnect()
    }
}
