package com.ventaone.dnsvpn

import android.content.SharedPreferences
import android.util.Log
import java.security.cert.X509Certificate
import java.security.MessageDigest

/**
 * Clase para manejar el almacenamiento y verificación de certificados.
 */
object CertificateManager {
    private const val TAG = "CertificateManager"
    private const val CERT_PIN_PREFIX = "CERT_PIN_"

    /**
     * Guarda el pin de un certificado para un hostname específico.
     *
     * @param certificate El certificado X509 a guardar
     * @param hostname El nombre del host al que pertenece el certificado
     * @param preferences Las preferencias compartidas donde guardar el pin
     */
    fun saveCertificatePin(certificate: X509Certificate, hostname: String, preferences: SharedPreferences) {
        try {
            // Calcular la huella digital SHA-256 del certificado
            val fingerprint = calculateSha256Fingerprint(certificate)

            // Guardar la huella digital en preferencias
            val key = "$CERT_PIN_PREFIX$hostname"
            preferences.edit().putString(key, fingerprint).apply()

            Log.d(TAG, "Certificado guardado para $hostname: $fingerprint")
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar el certificado para $hostname", e)
        }
    }

    /**
     * Verifica si un certificado coincide con el pin guardado para un hostname.
     *
     * @param certificate El certificado a verificar
     * @param hostname El hostname para el que verificar
     * @param preferences Las preferencias compartidas donde está almacenado el pin
     * @return true si el certificado coincide con el pin o no hay pin, false en caso contrario
     */
    fun verifyCertificatePin(certificate: X509Certificate, hostname: String, preferences: SharedPreferences): Boolean {
        val key = "$CERT_PIN_PREFIX$hostname"
        val savedFingerprint = preferences.getString(key, null) ?: return true // Si no hay pin, se acepta cualquier certificado

        val currentFingerprint = calculateSha256Fingerprint(certificate)
        val isMatch = savedFingerprint == currentFingerprint

        if (!isMatch) {
            Log.w(TAG, "Verificación de certificado fallida para $hostname")
            Log.w(TAG, "Guardado: $savedFingerprint")
            Log.w(TAG, "Actual: $currentFingerprint")
        }

        return isMatch
    }

    /**
     * Calcula la huella digital SHA-256 de un certificado.
     *
     * @param certificate El certificado del que calcular la huella
     * @return String con la huella en formato hexadecimal
     */
    private fun calculateSha256Fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(certificate.encoded)
        return hashBytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Elimina el pin de certificado para un hostname específico.
     *
     * @param hostname El hostname para el que eliminar el pin
     * @param preferences Las preferencias compartidas donde está almacenado el pin
     */
    fun removeCertificatePin(hostname: String, preferences: SharedPreferences) {
        val key = "$CERT_PIN_PREFIX$hostname"
        preferences.edit().remove(key).apply()
        Log.d(TAG, "Pin de certificado eliminado para $hostname")
    }
}