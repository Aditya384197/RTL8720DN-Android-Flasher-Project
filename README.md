# Realtek RTL8720DN (BW16) Mobile Firmware Flasher

Production Android application targeting Android 10 (API level 29) to Android 15 (API level 35).
Allows direct OTG firmware flashing of Realtek AmebaD microcontrollers (RTL8720DN / RTL8722DM / BW16 development boards) from an Android smartphone or tablet without requiring a PC.

---

### Hardware Wiring (BW16 Board to USB-UART Bridge)

| BW16 Pin | Function | Connect to USB-UART Bridge (CP2102/CH340) |
|---|---|---|
| **PB01** | LOG_TX | **RXD** of USB-UART adapter |
| **PB02** | LOG_RX | **TXD** of USB-UART adapter |
| **GND**  | Ground | **GND** |
| **3V3/5V** | Power | **3.3V or 5V** (depending on board regulator) |
| **PA08** | Burn / Boot Pin | **GND** during reset to enter ROM Bootloader mode |
| **CHIP_EN** / Reset | CPU Reset | Connect to RTS pin for automated reset into bootloader |

---

### RTL8720DN Memory Offsets

- `0x08000000`: `km0_boot_all.bin` (KM0 Low-power Cortex-M23 Bootloader)
- `0x08004000`: `km4_boot_all.bin` (KM4 High-performance Cortex-M33 Bootloader)
- `0x08006000`: `km0_km4_image2.bin` (Application Image 2)
- `0x0810C000`: `system_data.bin` (Wi-Fi MAC, Calibration & Flash Config)
- `0x081FC000`: `user_data.bin` (User persistent storage)

---

### Building in Android Studio
1. Open this project directory in Android Studio Hedgehog / Iguana / Jellyfish (2023.2+).
2. Sync Gradle with project files.
3. Connect your Android device via USB debugging or install directly via APK.
4. Plug an OTG adapter with your USB-UART bridge (CP2102, CH340, FTDI). Android will prompt:
   *"Open RTL8720DN Flasher when this USB device is connected?"*
5. Tap OK to grant USB Host permissions.
