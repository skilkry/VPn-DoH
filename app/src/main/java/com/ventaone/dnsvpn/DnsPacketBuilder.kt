package com.ventaone.dnsvpn

import android.util.Log
import java.nio.ByteBuffer
import java.util.*

/**
 * Clase para construir y analizar paquetes DNS.
 * Utilizada para la protección contra fugas DNS y el manejo de consultas DNS.
 */
class DNSPacketBuilder {
    companion object {
        private const val TAG = "DNSPacketBuilder"
    }

    private var defaultDnsServer = "1.1.1.1"
    private val blockedDnsServers = mutableSetOf<String>()

    init {
        // Inicializar servidores DNS públicos comunes para bloquear
        blockedDnsServers.addAll(
            listOf(
                "8.8.8.8", "8.8.4.4",      // Google
                "9.9.9.9", "149.112.112.112", // Quad9
                "208.67.222.222", "208.67.220.220" // OpenDNS
                // Podemos añadir más servidores DNS públicos según sea necesario
            )
        )
    }

    /**
     * Establece el servidor DNS predeterminado
     */
    fun setDefaultDnsServer(server: String) {
        this.defaultDnsServer = server
        Log.d(TAG, "Servidor DNS predeterminado configurado: $server")
    }

    /**
     * Obtiene la lista de servidores DNS bloqueados
     */
    fun getBlockedDnsServers(): Set<String> {
        return blockedDnsServers
    }

    /**
     * Añade un servidor DNS a la lista de bloqueados
     */
    fun addBlockedDnsServer(server: String) {
        blockedDnsServers.add(server)
        Log.d(TAG, "Añadido servidor DNS a la lista de bloqueados: $server")
    }

    /**
     * Construye un paquete de consulta DNS
     */
    fun buildDnsQuery(domain: String, queryType: Short = 1): ByteBuffer {
        try {
            val buffer = ByteBuffer.allocate(512) // Tamaño estándar para consultas DNS

            // Header DNS
            val id = Random().nextInt(Short.MAX_VALUE.toInt()).toShort()
            buffer.putShort(id) // Transaction ID
            buffer.putShort(0x0100) // Flags: consulta estándar recursiva
            buffer.putShort(1) // QDCOUNT: 1 pregunta
            buffer.putShort(0) // ANCOUNT: 0 respuestas
            buffer.putShort(0) // NSCOUNT: 0 registros NS
            buffer.putShort(0) // ARCOUNT: 0 registros adicionales

            // Consulta
            val labels = domain.split(".")
            for (label in labels) {
                buffer.put(label.length.toByte())
                label.forEach { buffer.put(it.code.toByte()) }
            }
            buffer.put(0) // Terminador de nombre de dominio

            buffer.putShort(queryType) // QTYPE (1 = A record)
            buffer.putShort(1) // QCLASS (1 = IN)

            buffer.flip()
            return buffer
        } catch (e: Exception) {
            Log.e(TAG, "Error al construir paquete DNS", e)
            throw e
        }
    }

    /**
     * Analiza una respuesta DNS para extraer las IPs
     */
    fun parseDnsResponse(response: ByteBuffer): List<String> {
        val ips = mutableListOf<String>()
        try {
            // Saltar header DNS (12 bytes)
            response.position(12)

            // Saltar la consulta original
            skipDomainName(response)
            response.position(response.position() + 4) // Saltar QTYPE y QCLASS

            // Procesar respuestas
            val header = response.duplicate()
            header.position(0)
            val answerCount = header.getShort(6).toInt() and 0xFFFF

            for (i in 0 until answerCount) {
                skipDomainName(response)
                val type = response.getShort() // QTYPE
                response.position(response.position() + 6) // Saltar CLASS, TTL
                val dataLength = response.getShort().toInt() and 0xFFFF

                if (type.toInt() == 1) { // A record
                    val b1 = response.get().toInt() and 0xFF
                    val b2 = response.get().toInt() and 0xFF
                    val b3 = response.get().toInt() and 0xFF
                    val b4 = response.get().toInt() and 0xFF
                    ips.add("$b1.$b2.$b3.$b4")
                } else {
                    response.position(response.position() + dataLength)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear respuesta DNS", e)
        }
        return ips
    }

    /**
     * Salta un nombre de dominio codificado en el paquete DNS
     */
    private fun skipDomainName(buffer: ByteBuffer) {
        while (true) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) break // Fin del nombre
            if ((len and 0xC0) == 0xC0) { // Compresión de dominio
                buffer.position(buffer.position() + 1) // Saltar el segundo byte de la referencia
                break
            }
            buffer.position(buffer.position() + len) // Saltar el label
        }
    }
}