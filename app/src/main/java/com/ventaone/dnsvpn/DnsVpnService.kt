package com.ventaone.dnsvpn

import android.app.Notification
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
import androidx.preference.PreferenceManager // Importar PreferenceManager
import okhttp3.*
// Mantener las importaciones si se usan en CertificateMonitor o OkHttpClient
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit // Necesario para OkHttpClient timeouts si los añades

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    // Estas variables ahora almacenan los servidores DNS REALMENTE SELECCIONADOS para la conexión
    private var selectedPrimaryDnsServer = "1.1.1.1"
    private var selectedSecondaryDnsServer = "1.0.0.1"

    private var client: OkHttpClient // Mantener si se usa para CertificateMonitor o pruebas
    private var isRunning = false // Indica si la VPN está activa a nivel lógico del servicio
    private var serverUrl = "https://cloudflare-dns.com" // Mantener si se usa para CertificateMonitor/URL base DoH

    // Mantener CertificateMonitor si valida el serverUrl
    private lateinit var certificateMonitor: CertificateMonitor
    // DnsLeakProtection y DNSPacketBuilder se mantienen si no tienen dependencias fuertes
    // en el procesamiento de paquetes IP/UDP/DNS directo. Podrían ser removidos si solo hacen cosas de bajo nivel.
    private lateinit var dnsLeakProtection: DnsLeakProtection
    private lateinit var dnsPacketBuilder: DNSPacketBuilder


    companion object {
        var isServiceRunning = false // Indica si el servicio VpnService está en ejecución
        private const val TAG = "DnsVpnService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_CHANNEL_ID = "dns_vpn_certificate_channel"
        private const val CERTIFICATE_NOTIFICATION_ID = 1001

        // Añadimos una acción para reiniciar el servicio desde fuera
        const val ACTION_RESTART_VPN = "com.ventaone.dnsvpn.RESTART_VPN"
    }

    init {
        client = createOkHttpClient() // Inicializar OkHttpClient (para CertificateMonitor)
    }

    private fun createOkHttpClient(): OkHttpClient {
        // Aquí podrías leer la preferencia de CONNECTION_TIMEOUT si decides añadirla
        // val connectTimeoutSeconds = getConnectTimeout() // Necesitarías implementar getConnectTimeout()
        return OkHttpClient.Builder()
            // .connectTimeout(connectTimeoutSeconds.toLong(), TimeUnit.SECONDS) // Ejemplo de uso del timeout
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")

        // SELECCIONAR los DNS adecuados basándose en las preferencias (incluyendo switches)
        selectDnsServersBasedOnPreferences()

        // Cargar URL del servidor personalizado (para CertificateMonitor si aplica)
        loadCustomServerUrl()

        // Inicializar componentes (mantener si no dependen fuertemente del manejo de paquetes)
        // Podrías decidir si estos son necesarios en la versión simplificada
        dnsLeakProtection = DnsLeakProtection(this)
        dnsPacketBuilder = DNSPacketBuilder()

        // Inicializar monitor de certificados (mantener si valida serverUrl)
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(object : CertificateMonitor.CertificateStatusListener {
            override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) {
                if (status == CertificateMonitor.CertificateStatus.INVALID && isServiceRunning) {
                    Log.w(TAG, "¡Certificado inválido detectado mientras el servicio está en ejecución!")
                    showCertificateWarningNotification()
                }
            }
        })

        createNotificationChannel()
        createCertificateNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand llamado con acción: ${intent?.action}")

        when (intent?.action) {
            "STOP_VPN" -> {
                Log.d(TAG, "Acción STOP_VPN recibida")
                stopVpn()
                return START_NOT_STICKY
            }
            "UPDATE_NOTIFICATION" -> {
                if (isServiceRunning) {
                    val notification = createNotification()
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
                return START_STICKY
            }
            // Manejamos la acción de reinicio enviada desde la Activity de Preferencias
            ACTION_RESTART_VPN -> {
                Log.d(TAG, "Acción RESTART_VPN recibida")
                // No necesitamos verificar isServiceRunning aquí, stopVpn ya maneja si no está corriendo
                Log.d(TAG, "Reiniciando VPN para aplicar nueva configuración...")
                stopVpn() // Detiene el servicio actual y limpia
                // startVpn() será llamado implícitamente después de que stopSelf() se complete
                // si el servicio está configurado como START_STICKY y no se detiene por una razón fatal.
                // Sin embargo, una forma más explícita y controlada sería iniciar el servicio
                // de nuevo con startService() *después* de que stopVpn() haya hecho lo suficiente.
                // Para este patrón simple stop/start, la llamada secuencial suele funcionar.
                startVpn() // Volvemos a llamar a startVpn para iniciar con la nueva config

                return START_STICKY // Asegura que el servicio intente re-iniciarse si es terminado por el sistema
            }
            else -> {
                // Lógica para iniciar la VPN si no está corriendo (acción por defecto o primer inicio)
                if (!isServiceRunning) {
                    Log.d(TAG, "Iniciando VPN (acción por defecto o primer inicio)")
                    // Recargar y SELECCIONAR DNS por si las preferencias se actualizaron recientemente
                    selectDnsServersBasedOnPreferences()
                    loadCustomServerUrl() // Recargar URL del servidor (para CertificateMonitor si aplica)

                    startForegroundWithNotification()
                    startVpn() // Llamar a la lógica de establecimiento de la VPN
                } else {
                    Log.d(TAG, "VPN ya está en ejecución")
                }
                return START_STICKY
            }
        }
    }

    /**
     * Carga los servidores DNS personalizados definidos en la preferencia (si aplica).
     * NOTA: Esta función solo carga la preferencia, no decide cuál usar.
     */
    private fun loadCustomDnsPreference(): Pair<String, String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val customPrimary = preferences.getString("PRIMARY_DNS", "1.1.1.1") ?: "1.1.1.1"
        val customSecondary = preferences.getString("SECONDARY_DNS", "1.0.0.1") ?: "1.0.0.1"
        Log.d(TAG, "Preferencias de DNS personalizadas cargadas: $customPrimary, $customSecondary")
        return Pair(customPrimary, customSecondary)
    }


    /**
     * Lee las preferencias de bloqueo y DNS personalizado y SELECCIONA
     * los servidores DNS (IPs) que se usarán para la conexión VPN.
     * Actualiza selectedPrimaryDnsServer y selectedSecondaryDnsServer.
     */
    private fun selectDnsServersBasedOnPreferences() {
        Log.d(TAG, "Iniciando selectDnsServersBasedOnPreferences") // <-- Añade este log al inicio

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val blockAds = preferences.getBoolean("AD_BLOCKER", false)
        val blockMalware = preferences.getBoolean("MALWARE_BLOCKER", true) // Valor por defecto true para protección por defecto
        val useCustomDns = preferences.getBoolean("USE_CUSTOM_DNS", false) // Asumiendo una preferencia para activar DNS personalizado

        Log.d(TAG, "Preferencias leídas: blockAds=$blockAds, blockMalware=$blockMalware, useCustomDns=$useCustomDns") // <-- Añade este log

        when {
            useCustomDns -> {
                val customDns = loadCustomDnsPreference() // Cargar los IPs personalizados
                selectedPrimaryDnsServer = customDns.first
                selectedSecondaryDnsServer = customDns.second
                Log.d(TAG, "DNS seleccionados (Caso: Custom DNS): ${selectedPrimaryDnsServer}") // <-- Añade este log
            }
            blockMalware && blockAds -> {
                selectedPrimaryDnsServer = "1.1.1.3" // Cloudflare Malware + Ads
                selectedSecondaryDnsServer = "1.0.0.3"
                Log.d(TAG, "DNS seleccionados (Caso: Malware + Ads): ${selectedPrimaryDnsServer}") // <-- Añade este log
            }
            blockMalware -> {
                selectedPrimaryDnsServer = "1.1.1.2" // Cloudflare Malware Only
                selectedSecondaryDnsServer = "1.0.0.2"
                Log.d(TAG, "DNS seleccionados (Caso: Solo Malware): ${selectedPrimaryDnsServer}") // <-- Añade este log
            }
            else -> {
                // Si no se usa DNS personalizado y no hay bloqueo activo, usar Cloudflare normal
                selectedPrimaryDnsServer = "1.1.1.1" // Cloudflare Normal
                selectedSecondaryDnsServer = "1.0.0.1"
                Log.d(TAG, "DNS seleccionados (Caso: Sin Bloqueo): ${selectedPrimaryDnsServer}") // <-- Añade este log
            }
        }
        Log.d(TAG, "selectDnsServersBasedOnPreferences finalizado. IPs seleccionadas: Primario=${selectedPrimaryDnsServer}, Secundario=${selectedSecondaryDnsServer}") // <-- Añade este log
    }

    /**
     * Carga la URL del servidor personalizada desde las preferencias (para CertificateMonitor si aplica).
     */
    private fun loadCustomServerUrl() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val customServer = preferences.getString("SERVER_URL", null)
        if (!customServer.isNullOrEmpty()) {
            serverUrl = if (!customServer.startsWith("https://")) {
                "https://$customServer"
            } else {
                customServer
            }
            Log.d(TAG, "URL del servidor personalizada cargada: $serverUrl")

            // Recreate client with the new server settings (Mantener si se usa para CertificateMonitor/probar conexión DoH)
            client = createOkHttpClient()
        } else {
            // Si no hay URL personalizada, usar la por defecto de Cloudflare para el monitor
            serverUrl = "https://cloudflare-dns.com" // Endpoint DoH por defecto
            client = createOkHttpClient()
            Log.d(TAG, "URL del servidor por defecto usada: $serverUrl")
        }
    }


    /**
     * Establece la conexión VPN directamente usando VpnService.Builder.
     * Esta versión NO captura todo el tráfico, solo configura los servidores DNS para el túnel
     * basándose en los servidores SELECCIONADOS (con/sin bloqueo/personalizados).
     */
    private fun startVpn() {
        Log.d(TAG, "Iniciando proceso de establecimiento de VPN...")
        try {
            // Antes de construir, asegurarnos de que los servidores DNS seleccionados estén actualizados
            selectDnsServersBasedOnPreferences()

            val builder = Builder()
                .setSession("VentaOne DNS Configurado") // Nombre de la sesión VPN
                // Necesitas al menos una dirección IP virtual y una ruta mínima para establecer la interfaz
                // 10.1.10.1/24 es una elección común para un túnel mínimo.
                .addAddress("10.1.10.1", 24)

                // IMPORTANTE: NO añadimos addRoute("0.0.0.0", 0) para evitar capturar todo el tráfico.

                // Configurar DNS - Esto le dice al sistema operativo qué servidores DNS usar
                // CUANDO el tráfico pase por esta interfaz VPN.
                // La efectividad de addDnsServer sin addRoute("0.0.0.0", 0) para forzar
                // TODO el tráfico DNS del sistema a pasar por aquí depende de cómo el sistema
                // gestione el resolver DNS. Android 9+ con DNS Privado es la forma recomendada
                // para DoH a nivel global sin VpnService. Este método influye en los DNS
                // asociados a la interfaz VPN creada.
                .addDnsServer(selectedPrimaryDnsServer)
                .addDnsServer(selectedSecondaryDnsServer)

            // Puedes añadir MTU (Maximum Transmission Unit) si lo deseas
            // .setMtu(1500)


            // Establecer el fileDescriptor de la VPN
            Log.d(TAG, "Llamando a builder.establish()...")
            vpnInterface = builder.establish()
            Log.d(TAG, "Llamada a builder.establish() completada.")


            if (vpnInterface != null) {
                Log.d(TAG, "Interfaz VPN establecida con éxito.")
                isRunning = true // Bandera interna de lógica del servicio
                isServiceRunning = true // Bandera estática para acceso externo

                // Iniciar monitoreo de certificados (si relevante para serverUrl)
                if (::certificateMonitor.isInitialized) { // Verificar si ha sido inicializado en onCreate
                    certificateMonitor.startMonitoring() // Asegúrate de que esto no bloquee o use recursos excesivos
                    Log.d(TAG, "Monitoreo de certificados iniciado.")
                }


                Log.d(TAG, "VPN iniciada con DNS: ${selectedPrimaryDnsServer}")
                Toast.makeText(this, "VPN conectada (DNS: ${selectedPrimaryDnsServer})", Toast.LENGTH_SHORT).show()

                // NOTA: En esta configuración, tu app ya no lee ni procesa paquetes
                // de la interfaz VPN. El FileDescriptor está establecido, pero no se usa para I/O.

            } else {
                Log.e(TAG, "Error al establecer la interfaz VPN: builder.establish() devolvió null")
                Toast.makeText(this, "Error al establecer la conexión VPN", Toast.LENGTH_SHORT).show()
                stopSelf() // Detener el servicio si falla al establecer
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al iniciar VPN", e)
            Toast.makeText(this, "Error al iniciar VPN: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf() // Detener el servicio si hay una excepción
        }
    }

    /**
     * Detiene la conexión VPN cerrando el FileDescriptor y detiene el servicio.
     */
    private fun stopVpn() {
        Log.d(TAG, "Iniciando proceso de detención de VPN...")
        isRunning = false
        isServiceRunning = false

        // Detener monitoreo de certificados
        if (::certificateMonitor.isInitialized) { // Verificar si ha sido inicializado
            certificateMonitor.stopMonitoring()
            Log.d(TAG, "Monitoreo de certificados detenido.")
        }


        try {
            // Cerrar el ParcelFileDescriptor
            vpnInterface?.close()
            vpnInterface = null // Limpiar la referencia
            Log.d(TAG, "Interfaz VPN cerrada.")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cerrar interfaz VPN", e)
        } finally {
            // Asegurarse de parar el servicio en primer plano
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Detener completamente el servicio
            Log.d(TAG, "Llamando a stopSelf().")
            stopSelf()
            Log.d(TAG, "Proceso de detención de VPN finalizado.")
        }
    }

    // --- Funciones de Notificación y Canal ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VPN Service"
            val descriptionText = "Notificaciones del servicio VPN"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createCertificateNotificationChannel() {
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

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java), // Asume que tienes una MainActivity
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Activo (DNS)")
            .setContentText("Configurando DNS via VentaOne DNSVPN") // Puedes hacer este texto dinámico
            .setSmallIcon(R.drawable.ic_vpn) // Asegúrate de tener este icono
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showCertificateWarningNotification() {
        val intent = Intent(this, CertificateVerificationActivity::class.java) // Asume que tienes esta Activity
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.cert_status_red) // Asegúrate de tener este icono
            .setContentTitle("¡Alerta de Seguridad!")
            .setContentText("El certificado del servidor DNS ha cambiado y podría no ser seguro.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false) // Que no se pueda descartar fácilmente
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CERTIFICATE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy llamado")
        // Asegurarse de que stopVpn se llama para limpiar antes de que el sistema mate el proceso
        stopVpn()
        super.onDestroy()
    }
}