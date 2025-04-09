package com.ventaone.dnsvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import okhttp3.*
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DnsVpnService : VpnService(), Runnable {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var primaryDnsServer = "1.1.1.1"  // Valor por defecto
    private var secondaryDnsServer = "1.0.0.1" // Valor por defecto
    private var client: OkHttpClient
    private var isRunning = false
    private var thread: Thread? = null
    private var serverUrl = "https://cloudflare-dns.com"
    private lateinit var certificateMonitor: CertificateMonitor

    companion object {
        var isServiceRunning = false
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "dns_vpn_certificate_channel"
    }

    init {
        // Initialize OkHttpClient with certificate validation
        client = createOkHttpClient()
    }

    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")
        // Cargar DNS personalizados de las preferencias
        loadCustomDns()
        // Cargar URL del servidor personalizado
        loadCustomServer()

        // Inicializar monitor de certificados
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(object : CertificateMonitor.CertificateStatusListener {
            override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) {
                if (status == CertificateMonitor.CertificateStatus.INVALID && isServiceRunning) {
                    Log.w(TAG, "¡Certificado inválido detectado mientras el servicio está en ejecución!")
                    // Notificar al usuario con una notificación persistente
                    showCertificateWarningNotification()
                }
            }
        })

        // Crear canal de notificaciones para Android 8.0+
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand llamado")

        if (intent?.action == "STOP_VPN") {
            Log.d(TAG, "Acción STOP_VPN recibida")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isServiceRunning) {
            Log.d(TAG, "Iniciando VPN")
            loadCustomDns() // Recargar DNS por si se actualizaron
            loadCustomServer() // Recargar URL del servidor
            startVpn()
        } else {
            Log.d(TAG, "VPN ya está en ejecución")
        }

        return START_STICKY
    }

    /**
     * Carga los servidores DNS personalizados desde las preferencias
     */
    private fun loadCustomDns() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        primaryDnsServer = preferences.getString("PRIMARY_DNS", "1.1.1.1") ?: "1.1.1.1"
        secondaryDnsServer = preferences.getString("SECONDARY_DNS", "1.0.0.1") ?: "1.0.0.1"
        Log.d(TAG, "DNS cargados: Primario=$primaryDnsServer, Secundario=$secondaryDnsServer")
    }

    /**
     * Carga la URL del servidor personalizado desde las preferencias
     */
    private fun loadCustomServer() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val customServer = preferences.getString("SERVER_URL", null)
        if (!customServer.isNullOrEmpty()) {
            serverUrl = if (!customServer.startsWith("https://")) {
                "https://$customServer"
            } else {
                customServer
            }
            Log.d(TAG, "URL del servidor cargada: $serverUrl")

            // Recreate client with the new server settings
            client = createOkHttpClient()
        }
    }

    private fun startVpn() {
        // Configura el builder para la VPN
        val builder = Builder()
        builder.addAddress("10.0.0.2", 32)
        // Usar los DNS personalizados
        builder.addDnsServer(primaryDnsServer)
        builder.addDnsServer(secondaryDnsServer)
        builder.setSession("DnsVpnService")

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pendingIntent)

        try {
            // Establece la interfaz VPN
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Error al establecer la interfaz VPN")
                Toast.makeText(this, "Error al establecer la conexión VPN", Toast.LENGTH_SHORT).show()
                stopSelf()
                return
            }

            isRunning = true
            isServiceRunning = true
            thread = Thread(this)
            thread?.start()

            // Iniciar monitoreo de certificados
            certificateMonitor.startMonitoring()

            Log.d(TAG, "VPN iniciada correctamente con DNS: $primaryDnsServer, $secondaryDnsServer")
            Toast.makeText(this, "VPN conectada usando DNS: $primaryDnsServer", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar VPN", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Deteniendo VPN")
        isRunning = false
        isServiceRunning = false

        // Detener monitoreo de certificados
        certificateMonitor.stopMonitoring()

        try {
            thread?.interrupt()
            thread = null

            vpnInterface?.close()
            vpnInterface = null

            Log.d(TAG, "Interfaz VPN cerrada")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cerrar VPN", e)
        }
    }

    override fun run() {
        Log.d(TAG, "Hilo VPN iniciado")
        while (isRunning) {
            try {
                // Usar DNS over HTTPS con el servidor configurado
                val dnsQuery = "example.com" // Domain to query as a test
                val request = Request.Builder()
                    .url("$serverUrl/dns-query?name=$dnsQuery&type=A")
                    .header("Accept", "application/dns-message")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error en solicitud DNS", e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (isRunning && response.isSuccessful) {
                            response.body?.let {
                                Log.d(TAG, "Respuesta DNS recibida: ${it.contentLength()} bytes")
                            }
                        }
                    }
                })

                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                Log.d(TAG, "Hilo VPN interrumpido")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error en hilo VPN", e)
                if (!isRunning) break
            }
        }
        Log.d(TAG, "Hilo VPN finalizado")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Estado del Certificado DNS"
            val descriptionText = "Notificaciones sobre el estado del certificado del servidor DNS"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showCertificateWarningNotification() {
        val intent = Intent(this, CertificateVerificationActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.cert_status_red)
            .setContentTitle("¡Alerta de Seguridad!")
            .setContentText("El certificado del servidor DNS ha cambiado y podría no ser seguro.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy llamado")
        stopVpn()
        super.onDestroy()
    }
}