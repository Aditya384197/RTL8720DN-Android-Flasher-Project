package com.embedded.rtlflasher.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.embedded.rtlflasher.flasher.Rtl8720dnFlasher
import com.embedded.rtlflasher.model.FlashConfig
import com.embedded.rtlflasher.model.FlashSlot
import com.embedded.rtlflasher.model.LogLevel
import com.embedded.rtlflasher.model.LogMessage
import com.embedded.rtlflasher.model.SlotStatus
import com.embedded.rtlflasher.model.UsbConnectionState
import com.embedded.rtlflasher.usb.UsbSerialManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FlasherViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager = UsbSerialManager(application)
    private val flasher = Rtl8720dnFlasher(usbManager, application.contentResolver)

    val connectionState: StateFlow<UsbConnectionState> = usbManager.connectionState

    private val _slots = MutableStateFlow(
        listOf(
            FlashSlot(id = 1, name = "KM0 Bootloader", addressHex = "0x08000000"),
            FlashSlot(id = 2, name = "KM4 Bootloader", addressHex = "0x08004000"),
            FlashSlot(id = 3, name = "Application Firmware", addressHex = "0x08006000"),
            FlashSlot(id = 4, name = "System Config", addressHex = "0x0810C000"),
            FlashSlot(id = 5, name = "User Data", addressHex = "0x081FC000", isEnabled = false)
        )
    )
    val slots: StateFlow<List<FlashSlot>> = _slots.asStateFlow()

    private val _config = MutableStateFlow(FlashConfig())
    val config: StateFlow<FlashConfig> = _config.asStateFlow()

    private val _isFlashing = MutableStateFlow(false)
    val isFlashing: StateFlow<Boolean> = _isFlashing.asStateFlow()

    private val _overallProgress = MutableStateFlow(0f)
    val overallProgress: StateFlow<Float> = _overallProgress.asStateFlow()

    private val _transferSpeed = MutableStateFlow(0f)
    val transferSpeed: StateFlow<Float> = _transferSpeed.asStateFlow()

    private val _logs = MutableStateFlow<List<LogMessage>>(emptyList())
    val logs: StateFlow<List<LogMessage>> = _logs.asStateFlow()

    private var flashingJob: Job? = null

    init {
        usbManager.scanAndConnect()
        addLog(LogLevel.INFO, "RTL8720DN Mobile Flasher initialized.")
    }

    fun updateConfig(updater: (FlashConfig) -> FlashConfig) {
        _config.update(updater)
    }

    fun setSlotFile(slotId: Int, uri: Uri) {
        val (fileName, fileSize) = queryFileInfo(uri)
        _slots.update { list ->
            list.map { slot ->
                if (slot.id == slotId) {
                    slot.copy(uri = uri, fileName = fileName, fileSize = fileSize, status = SlotStatus.IDLE)
                } else slot
            }
        }
        addLog(LogLevel.INFO, "Selected file '$fileName' ($fileSize bytes) for slot #$slotId")
    }

    fun updateSlotAddress(slotId: Int, newAddress: String) {
        _slots.update { list ->
            list.map { if (it.id == slotId) it.copy(addressHex = newAddress) else it }
        }
    }

    fun toggleSlotEnabled(slotId: Int, enabled: Boolean) {
        _slots.update { list ->
            list.map { if (it.id == slotId) it.copy(isEnabled = enabled) else it }
        }
    }

    fun startFlashing() {
        if (_isFlashing.value) return
        flashingJob = viewModelScope.launch {
            _isFlashing.value = true
            _overallProgress.value = 0f

            flasher.flashBinaries(
                slots = _slots.value,
                config = _config.value,
                listener = object : Rtl8720dnFlasher.FlashingListener {
                    override fun onLog(level: LogLevel, message: String, hexDump: String?) {
                        addLog(level, message, hexDump)
                    }

                    override fun onOverallProgress(progress: Float, writtenBytes: Long, totalBytes: Long, speedKbps: Float) {
                        _overallProgress.value = progress
                        _transferSpeed.value = speedKbps
                    }

                    override fun onSlotProgress(slotId: Int, progress: Float, status: SlotStatus, error: String?) {
                        _slots.update { list ->
                            list.map { slot ->
                                if (slot.id == slotId) slot.copy(progress = progress, status = status, error = error) else slot
                            }
                        }
                    }
                }
            )

            _isFlashing.value = false
        }
    }

    fun cancelFlashing() {
        flashingJob?.cancel()
        _isFlashing.value = false
        addLog(LogLevel.WARN, "Flashing cancelled by user.")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(level: LogLevel, message: String, hexDump: String? = null) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogMessage(time, level, message, hexDump)
        _logs.update { it + entry }
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "firmware.bin"
        var size = 0L
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    override fun onCleared() {
        super.onCleared()
        usbManager.onDestroy()
    }
}
