package com.embedded.rtlflasher.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.embedded.rtlflasher.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlasherScreen(viewModel: FlasherViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val config by viewModel.config.collectAsState()
    val isFlashing by viewModel.isFlashing.collectAsState()
    val overallProgress by viewModel.overallProgress.collectAsState()
    val transferSpeed by viewModel.transferSpeed.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var activeSlotForPicker by remember { mutableStateOf<Int?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && activeSlotForPicker != null) {
            viewModel.setSlotFile(activeSlotForPicker!!, uri)
        }
        activeSlotForPicker = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RTL8720DN Firmware Flasher") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    IconButton(onClick = { viewModel.usbManager.scanAndConnect() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh USB")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // USB Host Connection Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (connectionState.isConnected)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (connectionState.isConnected) Icons.Default.Usb else Icons.Default.UsbOff,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = if (connectionState.isConnected) "Connected: ${connectionState.chipType}" else "USB Not Connected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = connectionState.deviceName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Multi-Binary Slots (1 to 5)
            Text("Target Binaries (1 to 5)", style = MaterialTheme.typography.titleMedium)
            slots.forEach { slot ->
                BinarySlotRow(
                    slot = slot,
                    onToggleEnabled = { viewModel.toggleSlotEnabled(slot.id, it) },
                    onAddressChanged = { viewModel.updateSlotAddress(slot.id, it) },
                    onPickFile = {
                        activeSlotForPicker = slot.id
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
            }

            // Flashing Controls & Progress
            if (isFlashing) {
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text(
                    "Flashing: ${(overallProgress * 100).toInt()}% (${String.format("%.1f", transferSpeed)} KB/s)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { viewModel.cancelFlashing() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abort Flashing")
                }
            } else {
                Button(
                    onClick = { viewModel.startFlashing() },
                    enabled = connectionState.isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Flash to RTL8720DN")
                }
            }

            // Console Window
            TerminalLogView(logs = logs, onClear = { viewModel.clearLogs() })
        }
    }
}

@Composable
fun BinarySlotRow(
    slot: FlashSlot,
    onToggleEnabled: (Boolean) -> Unit,
    onAddressChanged: (String) -> Unit,
    onPickFile: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = slot.isEnabled, onCheckedChange = onToggleEnabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(slot.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text = slot.fileName ?: "No file selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (slot.fileName != null) Color(0xFF4CAF50) else Color.Gray
                )
            }
            OutlinedTextField(
                value = slot.addressHex,
                onValueChange = onAddressChanged,
                modifier = Modifier.width(130.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = onPickFile) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Choose File")
            }
        }
    }
}

@Composable
fun TerminalLogView(logs: List<LogMessage>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Terminal Output", color = Color.White, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.LightGray)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(logs) { log ->
                    Text(
                        text = "[${log.timestamp}] ${log.message}",
                        color = when (log.level) {
                            LogLevel.ERROR -> Color(0xFFFF5252)
                            LogLevel.WARN -> Color(0xFFFFD740)
                            LogLevel.SUCCESS -> Color(0xFF69F0AE)
                            LogLevel.TX -> Color(0xFF40C4FF)
                            LogLevel.RX -> Color(0xFFE040FB)
                            else -> Color(0xFFEEEEEE)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
