package com.embedded.rtlflasher.model

import android.net.Uri

data class FlashSlot(
    val id: Int,
    val name: String,
    val addressHex: String = "0x08000000",
    val uri: Uri? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val isEnabled: Boolean = true,
    val progress: Float = 0f, // 0.0f to 1.0f
    val status: SlotStatus = SlotStatus.IDLE,
    val error: String? = null
) {
    val addressLong: Long
        get() {
            val clean = addressHex.trim().removePrefix("0x").removePrefix("0X")
            return clean.toLongOrNull(16) ?: 0x08000000L
        }
}

enum class SlotStatus {
    IDLE,
    ERASING,
    FLASHING,
    VERIFYING,
    SUCCESS,
    ERROR
}

data class FlashConfig(
    val baudRate: Int = 1500000,
    val flashMode: String = "UART_DOWNLOAD",
    val flashSize: String = "4MB",
    val eraseMode: EraseMode = EraseMode.REGION,
    val packetSize: Int = 2048,
    val throttleMs: Long = 5L,
    val verifyAfterFlash: Boolean = true,
    val autoResetDtrRts: Boolean = true
)

enum class EraseMode {
    NONE,
    REGION,
    FULL_CHIP
}

data class UsbConnectionState(
    val isConnected: Boolean = false,
    val deviceName: String = "No Device Connected",
    val chipType: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0
)

data class LogMessage(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val hexDump: String? = null
)

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR,
    TX,
    RX
}
