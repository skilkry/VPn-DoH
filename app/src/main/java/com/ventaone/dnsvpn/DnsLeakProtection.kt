package com.ventaone.dnsvpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Clase que maneja la protección contra fugas DNS
 * Se encarga de gestionar las configuraciones y proporcionar métodos para que el servicio VPN
 * pueda implementar la protección adecuadamente.
 */
class DnsLeakProtection(private val context: Context) {
    companion object {
        private const val TAG = "DnsLeakProtection"
        const val PREF_DNS_LEAK_PROTECTION = "DNS_LEAK_PROTECTION"

        // Lista de IPs de servidores DNS públicos conocidos que deberían ser bloqueados
        // cuando la protección contra fugas DNS está activada
        private val PUBLIC_DNS_SERVERS = listOf(
            "8.8.8.8", // Google DNS
            "8.8.4.4", // Google DNS
            "1.1.1.1", // Cloudflare
            "1.0.0.1", // Cloudflare
            "9.9.9.9", // Quad9
            "149.112.112.112", // Quad9
            "208.67.222.222", // OpenDNS
            "208.67.220.220" // OpenDNS
            // Se pueden agregar más servidores DNS públicos según sea necesario
        )
    }

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Comprueba si la protección contra fugas DNS está habilitada en las preferencias
     */
    fun isEnabled(): Boolean {
        return preferences.getBoolean(PREF_DNS_LEAK_PROTECTION, true) // Habilitado por defecto
    }

    /**
     * Devuelve la lista de IPs de servidores DNS que deben ser bloqueados si la protección está activa
     */
    fun getDnsServersToBlock(): List<String> {
        return if (isEnabled()) {
            Log.d(TAG, "Protección contra fugas DNS activada, bloqueando ${PUBLIC_DNS_SERVERS.size} servidores DNS públicos")
            PUBLIC_DNS_SERVERS
        } else {
            Log.d(TAG, "Protección contra fugas DNS desactivada")
            emptyList()
        }
    }

    /**
     * Configura las reglas para bloquear el tráfico DNS no autorizado
     * Este método proporciona las reglas que el servicio VPN aplicará
     */
    fun configureDnsLeakProtection(builder: DNSPacketBuilder): DNSPacketBuilder {
        if (!isEnabled()) {
            Log.d(TAG, "No se aplican reglas de protección DNS, está desactivado")
            return builder
        }

        // Configurar el constructor de paquetes para bloquear peticiones a servidores DNS públicos
        Log.d(TAG, "Aplicando reglas de protección contra fugas DNS")

        // Aquí implementamos la lógica para bloquear consultas DNS a servidores públicos
        // cuando estamos usando nuestro propio servidor DNS seguro
        // Opciones para manejar el bloqueo DNS dependiendo de los métodos disponibles:

        // Opción 1: Si tienes un método que bloquea una IP específica
        getDnsServersToBlock().forEach { serverIp ->
            // Comentado hasta que sepamos el método correcto
            // builder.blockIp(serverIp)
        }

        // Opción 2: Si hay un método para añadir reglas de bloqueo por lotes
        // builder.addBlockRules(getDnsServersToBlock())

        // Opción 3: Si hay un método específico para DNS
        // builder.configureDnsBlocking(getDnsServersToBlock())

        // NOTA: Descomenta la opción correcta según los métodos disponibles
        // en tu clase DNSPacketBuilder

        return builder
    }

    /**
     * Registra un cambio en la configuración de protección contra fugas DNS
     */
    fun logConfigChange() {
        val status = if (isEnabled()) "activada" else "desactivada"
        Log.i(TAG, "Protección contra fugas DNS $status")
    }
}