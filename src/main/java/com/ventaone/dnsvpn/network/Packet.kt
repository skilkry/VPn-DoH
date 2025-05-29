package com.ventaone.dnsvpn.network

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

object Protocol {
    const val UDP: UByte = 17u
}

data class IP4Header(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: UByte,
    val sourceAddress: Inet4Address,
    val destinationAddress: Inet4Address
)

data class UDPHeader(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int
)

object PacketParser {
    fun parseIP4Header(buffer: ByteBuffer): IP4Header {
        buffer.position(0)
        val versionAndIHL = buffer.get().toInt()
        val version = versionAndIHL shr 4
        val headerLength = (versionAndIHL and 0x0F) * 4
        buffer.position(2)
        val totalLength = buffer.getShort().toInt() and 0xFFFF
        buffer.position(9)
        val protocol = buffer.get().toUByte()
        val sourceAddressBytes = ByteArray(4)
        buffer.position(12)
        buffer.get(sourceAddressBytes)
        val destinationAddressBytes = ByteArray(4)
        buffer.position(16)
        buffer.get(destinationAddressBytes)
        buffer.position(headerLength)
        return IP4Header(
            version,
            headerLength,
            totalLength,
            protocol,
            InetAddress.getByAddress(sourceAddressBytes) as Inet4Address,
            InetAddress.getByAddress(destinationAddressBytes) as Inet4Address
        )
    }

    fun parseUDPHeader(buffer: ByteBuffer): UDPHeader {
        val sourcePort = buffer.getShort().toInt() and 0xFFFF
        val destinationPort = buffer.getShort().toInt() and 0xFFFF
        val length = buffer.getShort().toInt() and 0xFFFF
        buffer.position(buffer.position() + 2) // Skip checksum
        return UDPHeader(sourcePort, destinationPort, length)
    }
}