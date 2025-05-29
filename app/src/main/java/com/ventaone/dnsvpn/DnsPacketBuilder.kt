package com.ventaone.dnsvpn

import android.util.Log
import com.ventaone.dnsvpn.network.IP4Header
import com.ventaone.dnsvpn.network.PacketParser
import com.ventaone.dnsvpn.network.Protocol
import com.ventaone.dnsvpn.network.UDPHeader
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object DNSPacketBuilder {
    private const val TAG = "DNSPacketBuilder"

    fun buildDnsErrorResponse(queryPacket: ByteBuffer): ByteBuffer {
        queryPacket.position(0)
        val originalIpHeader = PacketParser.parseIP4Header(queryPacket)
        val originalUdpHeader = PacketParser.parseUDPHeader(queryPacket)
        val queryDnsData = ByteArray(queryPacket.remaining())
        queryPacket.get(queryDnsData)
        val responseDnsBuffer = buildDnsResponsePayload(ByteBuffer.wrap(queryDnsData), emptyList(), 2)
        return packageUdpAndIp(responseDnsBuffer, originalIpHeader, originalUdpHeader)
    }

    fun buildDnsResponse(queryPacket: ByteBuffer, ips: List<String>): ByteBuffer {
        queryPacket.position(0)
        val originalIpHeader = PacketParser.parseIP4Header(queryPacket)
        val originalUdpHeader = PacketParser.parseUDPHeader(queryPacket)
        val queryDnsData = ByteArray(queryPacket.remaining())
        queryPacket.get(queryDnsData)
        val responseDnsBuffer = buildDnsResponsePayload(ByteBuffer.wrap(queryDnsData), ips, 0)
        return packageUdpAndIp(responseDnsBuffer, originalIpHeader, originalUdpHeader)
    }

    private fun buildDnsResponsePayload(queryPayload: ByteBuffer, ips: List<String>, rcode: Int): ByteBuffer {
        val responseBuffer = ByteBuffer.allocate(4096)
        val originalId = queryPayload.getShort(0)
        val flags = (0x8180 or rcode).toShort()

        responseBuffer.putShort(originalId)
        responseBuffer.putShort(flags)
        responseBuffer.putShort(1)
        responseBuffer.putShort(ips.size.toShort())
        responseBuffer.putShort(0)
        responseBuffer.putShort(0)

        val questionSectionOriginalOffset = 12
        queryPayload.position(questionSectionOriginalOffset)
        val questionStartPosition = queryPayload.position()
        var qnameEndPosition = -1
        while (queryPayload.hasRemaining()) {
            val len = queryPayload.get().toInt() and 0xFF
            if (len == 0) {
                qnameEndPosition = queryPayload.position()
                break
            }
            if (queryPayload.remaining() < len) {
                Log.e(TAG, "Paquete de consulta malformado, QNAME incompleto.")
                responseBuffer.flip(); return responseBuffer
            }
            queryPayload.position(queryPayload.position() + len)
        }
        if (qnameEndPosition == -1 || queryPayload.remaining() < 4) {
            Log.e(TAG, "Paquete de consulta malformado, sin QTYPE/QCLASS.")
            responseBuffer.flip(); return responseBuffer
        }
        val questionEndPosition = qnameEndPosition + 4
        val questionLength = questionEndPosition - questionStartPosition
        if (responseBuffer.remaining() < questionLength) {
            Log.e(TAG, "BufferOverflow, la pregunta es demasiado grande para el buffer.")
            responseBuffer.flip(); return responseBuffer
        }
        val questionBytes = ByteArray(questionLength)
        queryPayload.position(questionStartPosition)
        queryPayload.get(questionBytes)
        responseBuffer.put(questionBytes)

        if (rcode == 0) {
            ips.forEach { ip ->
                if (responseBuffer.remaining() < 16) {
                    Log.e(TAG, "BufferOverflow al intentar añadir la IP: $ip. No hay espacio.")
                    return@forEach
                }
                responseBuffer.put(0xC0.toByte())
                responseBuffer.put(questionSectionOriginalOffset.toByte())
                responseBuffer.putShort(1)
                responseBuffer.putShort(1)
                responseBuffer.putInt(60)
                responseBuffer.putShort(4)
                ip.split('.').forEach { part ->
                    responseBuffer.put((part.toInt() and 0xFF).toByte())
                }
            }
        }

        responseBuffer.flip()
        return responseBuffer
    }

    private fun packageUdpAndIp(dnsPayload: ByteBuffer, originalIpHeader: IP4Header, originalUdpHeader: UDPHeader): ByteBuffer {
        val udpPayloadSize = 8 + dnsPayload.limit()
        val udpBuffer = ByteBuffer.allocate(udpPayloadSize)
        udpBuffer.putShort(originalUdpHeader.destinationPort.toShort())
        udpBuffer.putShort(originalUdpHeader.sourcePort.toShort())
        udpBuffer.putShort(udpPayloadSize.toShort())
        udpBuffer.putShort(0)
        udpBuffer.put(dnsPayload)

        val ipPayloadSize = 20 + udpBuffer.limit()
        val ipBuffer = ByteBuffer.allocate(ipPayloadSize)
        ipBuffer.put(0x45.toByte())
        ipBuffer.put(0)
        ipBuffer.putShort(ipPayloadSize.toShort())
        ipBuffer.putShort(0)
        ipBuffer.putShort(0x4000.toShort())
        ipBuffer.put(64.toByte())
        ipBuffer.put(Protocol.UDP.toByte())
        ipBuffer.putShort(0)
        ipBuffer.put(originalIpHeader.destinationAddress.address)
        ipBuffer.put(originalIpHeader.sourceAddress.address)
        ipBuffer.putShort(10, calculateChecksum(ipBuffer, 0, 20))
        ipBuffer.put(udpBuffer.array())
        ipBuffer.flip()
        return ipBuffer
    }

    fun extractDomainAndIdFromQuery(queryPacket: ByteBuffer): Pair<String, Short>? {
        try {
            queryPacket.position(0)
            PacketParser.parseIP4Header(queryPacket)
            PacketParser.parseUDPHeader(queryPacket)
            val transactionId = queryPacket.getShort()
            queryPacket.position(queryPacket.position() + 10)
            return Pair(extractDomainName(queryPacket), transactionId)
        } catch (e: Exception) {
            // Este catch ahora atrapará los errores de paquetes malformados desde extractDomainName
            Log.w(TAG, "Paquete DNS ignorado (no se pudo extraer el dominio): ${e.message}")
            return null
        }
    }

    // --- INICIO DE LA CORRECCIÓN ---
    // Se ha hecho esta función más segura para que falle ante paquetes malformados.
    private fun extractDomainName(buffer: ByteBuffer): String {
        val domain = StringBuilder()
        while (true) {
            if (!buffer.hasRemaining()) {
                throw IOException("Paquete malformado: se alcanzó el final del buffer buscando la longitud de la etiqueta.")
            }
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) {
                break // Fin del QNAME
            }
            // Comprobación de seguridad clave:
            if (buffer.remaining() < len) {
                throw IOException("QNAME malformado: longitud declarada ($len) mayor que los bytes restantes (${buffer.remaining()})")
            }
            if (domain.isNotEmpty()) {
                domain.append('.')
            }
            val domainPartBytes = ByteArray(len)
            buffer.get(domainPartBytes)
            domain.append(String(domainPartBytes, StandardCharsets.UTF_8))
        }
        return domain.toString()
    }
    // --- FIN DE LA CORRECCIÓN ---

    fun buildDnsQueryForDoH(domain: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(kotlin.random.Random.nextInt(1, 65535).toShort())
        buffer.putShort(0x0100.toShort())
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        domain.split('.').forEach { part ->
            buffer.put(part.length.toByte())
            buffer.put(part.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0.toByte())
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.flip()
        val queryData = ByteArray(buffer.limit())
        buffer.get(queryData)
        return queryData
    }

    fun parseDnsResponseFromDoH(data: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(data)
        val ips = mutableListOf<String>()
        try {
            buffer.position(6)
            val answerCount = buffer.getShort().toInt()
            buffer.position(12)
            var qnameEnd = -1
            while(buffer.hasRemaining()) {
                val len = buffer.get().toInt() and 0xff
                if (len == 0) {
                    qnameEnd = buffer.position()
                    break
                }
                if (buffer.remaining() < len) { break }
                buffer.position(buffer.position() + len)
            }
            if(qnameEnd == -1) return emptyList()

            buffer.position(qnameEnd + 4)

            for (i in 0 until answerCount) {
                if (buffer.remaining() < 12) break
                val firstByte = buffer.get().toInt() and 0xFF
                if ((firstByte and 0xC0) == 0xC0) {
                    buffer.get()
                }
                val type = buffer.getShort().toInt()
                buffer.getShort()
                buffer.getInt()
                val dataLength = buffer.getShort().toInt()
                if (type == 1 && dataLength == 4) {
                    val ipBytes = ByteArray(4)
                    buffer.get(ipBytes)
                    ips.add(InetAddress.getByAddress(ipBytes).hostAddress)
                } else {
                    buffer.position(buffer.position() + dataLength)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear la respuesta DoH", e)
        }
        return ips
    }

    private fun calculateChecksum(buffer: ByteBuffer, offset: Int, length: Int): Short {
        var sum = 0
        val oldPos = buffer.position()
        buffer.position(offset)
        for (i in 0 until length / 2) {
            sum += buffer.getShort().toInt() and 0xFFFF
        }
        buffer.position(oldPos)
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }
}