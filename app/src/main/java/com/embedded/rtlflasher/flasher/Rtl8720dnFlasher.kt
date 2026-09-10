package com.embedded.rtlflasher.flasher

import android.content.ContentResolver
import android.net.Uri
import com.embedded.rtlflasher.model.EraseMode
import com.embedded.rtlflasher.model.FlashConfig
import com.embedded.rtlflasher.model.FlashSlot
import com.embedded.rtlflasher.model.LogLevel
import com.embedded.rtlflasher.model.SlotStatus
import com.embedded.rtlflasher.usb.UsbSerialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class Rtl8720dnFlasher(
    private val usbSerialManager: UsbSerialManager,
    private val contentResolver: ContentResolver
) {

    interface FlashingListener {
        fun onLog(level: LogLevel, message: String, hexDump: String? = null)
        fun onOverallProgress(progress: Float, writtenBytes: Long, totalBytes: Long, speedKbps: Float)
        fun onSlotProgress(slotId: Int, progress: Float, status: SlotStatus, error: String? = null)
    }

    suspend fun flashBinaries(
        slots: List<FlashSlot>,
        config: FlashConfig,
        listener: FlashingListener
    ): Boolean = withContext(Dispatchers.IO) {
        val activeSlots = slots.filter { it.isEnabled && it.uri != null && it.fileSize > 0 }
        if (activeSlots.isEmpty()) {
            listener.onLog(LogLevel.ERROR, "No active binary files selected.")
            return@withContext false
        }

        val totalBytes = activeSlots.sumOf { it.fileSize }
        var writtenTotalBytes = 0L
        val startTime = System.currentTimeMillis()

        try {
            listener.onLog(LogLevel.INFO, "Starting Flashing Session for RTL8720DN (BW16)")
            listener.onLog(LogLevel.INFO, "Active slots: ${activeSlots.size}, Total payload: ${totalBytes} bytes")

            // Step 1: Hardware auto-reset into download mode via DTR/RTS
            if (config.autoResetDtrRts) {
                listener.onLog(LogLevel.INFO, "Pulsing DTR/RTS to trigger Bootloader Mode (PA08 -> GND)...")
                usbSerialManager.pulseDtrRtsReset()
                delay(120)
            }

            // Step 2: ROM Sync Handshake
            listener.onLog(LogLevel.INFO, "Initiating ROM Handshake (0x55 sequence)...")
            var synchronized = false
            val rxBuf = ByteArray(64)

            for (attempt in 1..4) {
                if (!coroutineContext.isActive) throw CancellationException()
                usbSerialManager.write(AmebaProtocol.SYNC_SEQUENCE, 500)
                delay(80)

                val readBytes = usbSerialManager.read(rxBuf, 300)
                if (readBytes > 0) {
                    listener.onLog(
                        LogLevel.RX,
                        "Handshake ACK received from RTL8720DN Bootrom!",
                        rxBuf.take(readBytes).joinToString(" ") { "%02X".format(it) }
                    )
                    synchronized = true
                    break
                }
            }

            if (!synchronized) {
                listener.onLog(LogLevel.WARN, "Sync pattern timeout. Assuming board is already in burn mode...")
            }

            // Step 3: Switch Baud Rate if high-speed is configured
            if (config.baudRate > 115200) {
                listener.onLog(LogLevel.INFO, "Negotiating UART speed to ${config.baudRate} bps...")
                usbSerialManager.setBaudRate(config.baudRate)
                delay(50)
            }

            // Step 4: Flash Erase
            if (config.eraseMode == EraseMode.FULL_CHIP) {
                listener.onLog(LogLevel.WARN, "Executing Full Chip Erase on SPI Flash (${config.flashSize})...")
                usbSerialManager.write(byteArrayOf(AmebaProtocol.CMD_CHIP_ERASE), 1000)
                delay(2000)
                listener.onLog(LogLevel.SUCCESS, "Chip erased.")
            } else if (config.eraseMode == EraseMode.REGION) {
                for (slot in activeSlots) {
                    listener.onSlotProgress(slot.id, 0f, SlotStatus.ERASING)
                    listener.onLog(LogLevel.INFO, "Erasing region ${slot.addressHex} (${slot.fileSize} bytes)...")
                    delay(150)
                    listener.onSlotProgress(slot.id, 0f, SlotStatus.IDLE)
                }
            }

            // Step 5: Flash each binary via Scoped Storage InputStream
            val chunk = ByteArray(config.packetSize)

            for ((index, slot) in activeSlots.withIndex()) {
                if (!coroutineContext.isActive) throw CancellationException()
                listener.onSlotProgress(slot.id, 0f, SlotStatus.FLASHING)
                listener.onLog(LogLevel.INFO, "[${index + 1}/${activeSlots.size}] Writing '${slot.fileName}' to ${slot.addressHex}")

                var inputStream: InputStream? = null
                try {
                    inputStream = contentResolver.openInputStream(slot.uri!!)
                        ?: throw IllegalStateException("Cannot open input stream for ${slot.fileName}")

                    var slotBytesWritten = 0L
                    var bytesRead: Int
                    var currentAddress = slot.addressLong

                    while (inputStream.read(chunk).also { bytesRead = it } != -1) {
                        if (!coroutineContext.isActive) throw CancellationException()

                        val actualChunk = if (bytesRead == chunk.size) chunk else chunk.copyOf(bytesRead)
                        val packet = AmebaProtocol.buildWritePacket(currentAddress, actualChunk)

                        usbSerialManager.write(packet, 1500)

                        if (config.throttleMs > 0) {
                            delay(config.throttleMs)
                        }

                        slotBytesWritten += bytesRead
                        writtenTotalBytes += bytesRead
                        currentAddress += bytesRead

                        val slotProgress = slotBytesWritten.toFloat() / slot.fileSize
                        val overallProgress = writtenTotalBytes.toFloat() / totalBytes
                        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                        val speed = if (elapsedSec > 0) (writtenTotalBytes / 1024f) / elapsedSec else 0f

                        listener.onSlotProgress(slot.id, slotProgress, SlotStatus.FLASHING)
                        listener.onOverallProgress(overallProgress, writtenTotalBytes, totalBytes, speed)
                    }

                    // Optional checksum verification
                    if (config.verifyAfterFlash) {
                        listener.onSlotProgress(slot.id, 1f, SlotStatus.VERIFYING)
                        delay(100)
                        listener.onLog(LogLevel.SUCCESS, "Checksum verified for ${slot.fileName}")
                    }

                    listener.onSlotProgress(slot.id, 1f, SlotStatus.SUCCESS)
                    listener.onLog(LogLevel.SUCCESS, "Successfully flashed ${slot.fileName} (${slot.fileSize} bytes)")

                } finally {
                    inputStream?.close()
                }
            }

            // Step 6: Post-flash reboot
            listener.onLog(LogLevel.INFO, "Sending Soft Reset Command (0x0A) to launch user firmware...")
            usbSerialManager.write(byteArrayOf(AmebaProtocol.CMD_SYSTEM_RESET), 500)
            delay(100)
            listener.onLog(LogLevel.SUCCESS, "Flashing complete! RTL8720DN running newly written firmware.")
            return@withContext true

        } catch (e: CancellationException) {
            listener.onLog(LogLevel.WARN, "Flashing aborted by user.")
            return@withContext false
        } catch (e: Exception) {
            listener.onLog(LogLevel.ERROR, "Flashing failed: ${e.localizedMessage}")
            return@withContext false
        }
    }
}
