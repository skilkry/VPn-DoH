package com.ventaone.dnsvpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.*
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.concurrent.thread

class CertificateMonitor(private val context: Context) {

    interface CertificateStatusListener {
        fun onCertificateStatus(status: CertificateStatus)
    }

    enum class CertificateStatus {
        NOT_VERIFIED,  // Gris - No verificado
        VALID,         // Verde - Certificado válido
        INVALID,       // Rojo - Certificado inválido/caducado/peligroso
        WARNING        // Amarillo - Advertencia (ej. próximo a expirar)
    }

    private val TAG = "CertificateMonitor"
    private val VERIFICATION_INTERVAL = 60000L  // 1 minuto
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var listeners = mutableListOf<CertificateStatusListener>()
    private var lastStatus: CertificateStatus = CertificateStatus.NOT_VERIFIED
    private var client: OkHttpClient? = null

    init {
        // Crear cliente HTTP
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()
    }

    fun addListener(listener: CertificateStatusListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: CertificateStatusListener) {
        listeners.remove(listener)
    }

    fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true

        // Realizar verificación inicial
        verifyCertificateStatus()

        // Programar verificaciones periódicas
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    verifyCertificateStatus()
                    handler.postDelayed(this, VERIFICATION_INTERVAL)
                }
            }
        }, VERIFICATION_INTERVAL)
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun verifyCertificateStatus() {
        // Obtener URL del servidor desde preferencias
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        var serverUrl = preferences.getString("SERVER_URL", "cloudflare-dns.com") ?: "cloudflare-dns.com"

        // Asegurar que la URL tenga prefijo https://
        if (!serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
            serverUrl = "https://$serverUrl"
        }

        // Extraer hostname para la verificación del pin
        val hostname = try {
            serverUrl.replace("https://", "").replace("http://", "").split("/")[0]
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo hostname de $serverUrl", e)
            serverUrl
        }

        // Verificar en segundo plano
        thread {
            try {
                val request = Request.Builder()
                    .url(serverUrl)
                    .build()

                client?.newCall(request)?.execute()?.use { response ->
                    // Obtener la cadena de certificados
                    val certificateChain = response.handshake?.peerCertificates

                    if (certificateChain.isNullOrEmpty()) {
                        updateStatus(CertificateStatus.INVALID)
                        return@use
                    }

                    // Obtener el certificado del servidor (primero en la cadena)
                    val serverCert = certificateChain[0] as X509Certificate

                    // Verificar validez del certificado
                    val isPinValid = CertificateManager.verifyCertificatePin(serverCert, hostname, preferences)
                    val isExpired = isCertificateExpired(serverCert)
                    val isExpiringSoon = isCertificateExpiringSoon(serverCert)

                    // Determinar el estado según los criterios especificados
                    val status = when {
                        isExpired || !isPinValid -> CertificateStatus.INVALID  // Rojo - Caducado o pin inválido
                        isExpiringSoon -> CertificateStatus.WARNING           // Amarillo - Próximo a caducar
                        else -> CertificateStatus.VALID                       // Verde - Todo correcto
                    }

                    updateStatus(status)
                }
            } catch (e: SSLPeerUnverifiedException) {
                Log.e(TAG, "SSL verification failed", e)
                updateStatus(CertificateStatus.INVALID)  // Rojo - Error de verificación SSL
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                updateStatus(CertificateStatus.WARNING)  // Amarillo - Error de conexión (potencialmente peligroso)
            }
        }
    }

    private fun isCertificateExpired(certificate: X509Certificate): Boolean {
        val now = System.currentTimeMillis()
        try {
            certificate.checkValidity()
            return false
        } catch (e: Exception) {
            return true
        }
    }

    private fun isCertificateExpiringSoon(certificate: X509Certificate): Boolean {
        val now = System.currentTimeMillis()
        val expiry = certificate.notAfter.time
        val thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000

        // Si ya expiró, no está "próximo a expirar", simplemente expiró
        if (now > expiry) return false

        return (expiry - now) < thirtyDaysInMillis
    }

    private fun updateStatus(status: CertificateStatus) {
        if (status != lastStatus) {
            lastStatus = status

            // Notificar en el hilo principal
            handler.post {
                listeners.forEach { it.onCertificateStatus(status) }
            }
        }
    }

    fun getCurrentStatus(): CertificateStatus {
        return lastStatus
    }
}