package com.embedded.rtlflasher.flasher

import java.util.zip.CRC32

object AmebaProtocol {
    // ROM Bootloader Sync Sequences
    const val SYNC_BYTE: Byte = 0x55
    val SYNC_SEQUENCE = byteArrayOf(0x55, 0x55, 0x55, 0x55)
    val SYNC_ACK = byteArrayOf(0x55, 0xAA.toByte(), 0x00, 0x07)

    // Commands
    const val CMD_SYNC: Byte = 0x05
    const val CMD_BAUDRATE_SWITCH: Byte = 0x06
    const val CMD_CHIP_ERASE: Byte = 0x07
    const val CMD_WRITE_BLOCK: Byte = 0x08
    const val CMD_VERIFY_CRC: Byte = 0x09
    const val CMD_SYSTEM_RESET: Byte = 0x0A

    const val ACK_BYTE: Byte = 0x06
    const val NAK_BYTE: Byte = 0x15

    // IEEE 802.3 CRC32 calculation
    fun computeCrc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    // Packet builder for block flashing
    fun buildWritePacket(targetAddress: Long, chunk: ByteArray): ByteArray {
        val packet = ByteArray(1 + 4 + 2 + chunk.size + 2)
        packet[0] = CMD_WRITE_BLOCK
        
        // 4 bytes address (Little Endian)
        packet[1] = (targetAddress and 0xFF).toByte()
        packet[2] = ((targetAddress shr 8) and 0xFF).toByte()
        packet[3] = ((targetAddress shr 16) and 0xFF).toByte()
        packet[4] = ((targetAddress shr 24) and 0xFF).toByte()

        // 2 bytes length
        val len = chunk.size
        packet[5] = (len and 0xFF).toByte()
        packet[6] = ((len shr 8) and 0xFF).toByte()

        // Data payload
        System.arraycopy(chunk, 0, packet, 7, chunk.size)

        // 2 bytes simple Fletcher or CRC16 checksum
        val checksum = (chunk.sumOf { it.toInt() and 0xFF }) and 0xFFFF
        packet[7 + chunk.size] = (checksum and 0xFF).toByte()
        packet[7 + chunk.size + 1] = ((checksum shr 8) and 0xFF).toByte()

        return packet
    }
}
