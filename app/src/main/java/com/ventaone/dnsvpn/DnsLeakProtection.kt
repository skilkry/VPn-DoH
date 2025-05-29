package com.ventaone.dnsvpn

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Clase que gestiona la configuración de protección contra fugas de DNS.
 */
class DnsLeakProtection(context: Context) {
    companion object {
        private const val TAG = "DnsLeakProtection"
        // Esta clave debe coincidir con la 'key' de tu SwitchPreference en el XML de ajustes
        const val PREF_DNS_LEAK_PROTECTION = "dns_leak_protection"

        private val PUBLIC_DNS_SERVERS = listOf(
            "8.8.8.8", "8.8.4.4",         // Google DNS
            "1.1.1.1", "1.0.0.1",         // Cloudflare
            "9.9.9.9", "149.112.112.112", // Quad9
            "208.67.222.222", "208.67.220.220" // OpenDNS
        )
    }

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Comprueba si la protección contra fugas DNS está habilitada en las preferencias.
     * El valor por defecto es 'true' (activado).
     */
    fun isEnabled(): Boolean {
        return preferences.getBoolean(PREF_DNS_LEAK_PROTECTION, true)
    }

    /**
     * Devuelve la lista de IPs de servidores DNS que deben ser bloqueados.
     */
    fun getDnsServersToBlock(): List<String> {
        return if (isEnabled()) {
            Log.d(TAG, "Protección contra fugas DNS está ACTIVA. Bloqueando ${PUBLIC_DNS_SERVERS.size} servidores.")
            PUBLIC_DNS_SERVERS
        } else {
            Log.d(TAG, "Protección contra fugas DNS está INACTIVA.")
            emptyList()
        }
    }
}