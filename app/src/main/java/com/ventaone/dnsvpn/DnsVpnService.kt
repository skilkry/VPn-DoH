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
import com.ventaone.dnsvpn.network.PacketParser
import com.ventaone.dnsvpn.network.Protocol
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class DnsVpnService : VpnService(), Runnable {

    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var client: OkHttpClient
    @Volatile private var isRunning = false
    private var thread: Thread? = null
    private var serverUrl = "https://cloudflare-dns.com/dns-query"
    private lateinit var certificateMonitor: CertificateMonitor

    companion object {
        @Volatile var isServiceRunning = false
        var isCertificatePinningFailed = false
        const val ACTION_RESTART_VPN = "com.ventaone.dnsvpn.ACTION_RESTART_VPN"
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "DnsVpnChannel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[DEBUG] DnsVpnService.onCreate")
        certificateMonitor = CertificateMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[DEBUG] DnsVpnService.onStartCommand. Action: ${intent?.action}")
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (thread == null || !isRunning) {
            Log.d(TAG, "[DEBUG] El hilo es nulo o no está corriendo, iniciando secuencia de arranque.")
            loadDnsServers()
            client = createProtectedHttpClient()

            if (isCertificatePinningFailed) {
                Log.e(TAG, "[DEBUG] Fallo de anclaje de certificado detectado ANTES de establecer. Deteniendo.")
                Toast.makeText(this, "Error de anclaje de certificado. Verifíquelo en la app.", Toast.LENGTH_LONG).show()
                stopVpn()
                return START_NOT_STICKY
            }

            vpnInterface = establish()
            if (vpnInterface != null) {
                Log.d(TAG, "[DEBUG] Interfaz VPN establecida correctamente.")
                isRunning = true
                thread = Thread(this, "DnsVpnThread")
                thread?.start()
                isServiceRunning = true
                showVpnNotification("VPN Activa (Solo DNS)", "El servicio DNS está protegiendo tus consultas.") // Título modificado
            } else {
                Log.e(TAG, "[DEBUG] La interfaz VPN no se pudo establecer. Deteniendo servicio.")
                stopSelf()
            }
        } else {
            Log.d(TAG, "[DEBUG] onStartCommand llamado pero el hilo ya existe y está corriendo.")
        }
        return START_STICKY
    }

    override fun run() {
        Log.d(TAG, "[DEBUG] RUN: Hilo de la VPN iniciado. Entrando en bucle de lectura.")
        // Es crucial que vpnInterface no sea null aquí.
        // La lógica en onStartCommand debería asegurar esto o detener el servicio.
        val currentVpnInterface = vpnInterface ?: run {
            Log.e(TAG, "[DEBUG] RUN: vpnInterface es null al inicio de run(). Deteniendo hilo.")
            isRunning = false
            return
        }
        val vpnInput = FileInputStream(currentVpnInterface.fileDescriptor)
        val vpnOutput = FileOutputStream(currentVpnInterface.fileDescriptor)
        val packetBuffer = ByteBuffer.allocate(32767)

        try {
            while (isRunning) {
                val size = vpnInput.read(packetBuffer.array())
                if (size > 0) {
                    Log.d(TAG, "[DEBUG] RUN: Leídos $size bytes del túnel VPN.")
                    packetBuffer.limit(size)

                    try {
                        val currentPacket = packetBuffer.duplicate()
                        val ipHeader = PacketParser.parseIP4Header(currentPacket)
                        Log.d(TAG, "[DEBUG] RUN: Paquete IPV4 parseado. Protocolo: ${ipHeader.protocol}, Origen: ${ipHeader.sourceAddress.hostAddress}, Destino: ${ipHeader.destinationAddress.hostAddress}")

                        if (ipHeader.protocol == Protocol.UDP) {
                            val udpPacketForDns = packetBuffer.duplicate()
                            val udpHeader = PacketParser.parseUDPHeader(udpPacketForDns)
                            Log.d(TAG, "[DEBUG] RUN: Paquete UDP parseado. Puerto Origen: ${udpHeader.sourcePort}, Puerto Destino: ${udpHeader.destinationPort}")

                            // Solo procesamos paquetes DNS dirigidos a nuestro servidor DNS virtual
                            if (udpHeader.destinationPort == 53 && ipHeader.destinationAddress.hostAddress == "10.0.0.1") {
                                Log.d(TAG, "[DEBUG] RUN: Paquete DNS para nuestro resolvedor detectado! Lanzando hilo.")
                                thread {
                                    handleDnsQuery(packetBuffer.duplicate(), vpnOutput)
                                }
                            } else {
                                // Si no es un paquete DNS para nuestro servidor virtual, lo ignoramos.
                                // El sistema operativo debería enrutarlo por la red física si no hay ruta global en la VPN.
                                Log.d(TAG, "[DEBUG] RUN: Paquete UDP (Destino: ${ipHeader.destinationAddress.hostAddress}:${udpHeader.destinationPort}) ignorado por el manejador DNS.")
                            }
                        } else {
                            // Ignoramos paquetes no UDP (ej. TCP). El sistema debería enrutarlos por la red física.
                            Log.d(TAG, "[DEBUG] RUN: Paquete NO UDP (Protocolo: ${ipHeader.protocol}) ignorado por el manejador DNS.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[DEBUG] RUN: Error al parsear paquete IP/UDP. Ignorando.", e)
                    }
                } else if (size == 0) {
                    // Continue
                } else {
                    Log.e(TAG, "[DEBUG] RUN: Error al leer del túnel VPN, read() devolvió $size. Saliendo del bucle.")
                    isRunning = false
                }
                packetBuffer.clear()
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "[DEBUG] RUN: IOException en el bucle de lectura (puede ser por cierre de interfaz). Error: ${e.message}", e)
            } else {
                Log.d(TAG, "[DEBUG] RUN: IOException (esperada por cierre) en el bucle de lectura.")
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "[DEBUG] RUN: Error crítico (no IOException) en el bucle de lectura.", e)
            }
        } finally {
            Log.d(TAG, "[DEBUG] RUN: Hilo finalizando. Saliendo del bucle de lectura.")
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "[DEBUG] stopVpn() llamado.")
        isRunning = false

        val oldThread = thread
        thread = null

        oldThread?.interrupt()

        try {
            vpnInterface?.close()
            Log.d(TAG, "[DEBUG] Interfaz VPN cerrada.")
        } catch (e: IOException) {
            Log.e(TAG, "[DEBUG] Error al cerrar vpnInterface", e)
        } finally {
            vpnInterface = null
            isServiceRunning = false
            stopForeground(true)
            stopSelf()
            Log.d(TAG, "[DEBUG] Servicio VPN y notificación detenidos. stopSelf() llamado.")
        }
    }

    private fun handleDnsQuery(queryPacket: ByteBuffer, vpnOutput: FileOutputStream) {
        Log.d(TAG, "[DEBUG] DNS_HANDLER: Iniciando manejo de consulta DNS.")
        try {
            val queryPacketCopy = queryPacket.duplicate() // Usar una copia para no afectar el original en otros hilos
            val (domain, _) = DNSPacketBuilder.extractDomainAndIdFromQuery(queryPacketCopy) ?: run { // queryPacketCopy se consume aquí
                Log.w(TAG, "[DEBUG] DNS_HANDLER: No se pudo extraer el dominio. Abortando.")
                return
            }
            Log.d(TAG, "[DEBUG] DNS_HANDLER: Dominio extraído: '$domain'. Realizando consulta DoH.")

            val ips = performDohQuery(domain)

            // Necesitamos el queryPacket original (o una copia intacta desde el inicio del IP)
            // para construir la respuesta completa.
            val originalPacketForResponse = queryPacket.duplicate()
            originalPacketForResponse.position(0) // Asegurar que está al inicio

            val responsePacket = if (ips.isNotEmpty()) {
                Log.d(TAG, "[DEBUG] DNS_HANDLER: Consulta DoH exitosa para '$domain'. IPs: $ips. Construyendo respuesta.")
                DNSPacketBuilder.buildDnsResponse(originalPacketForResponse, ips)
            } else {
                Log.w(TAG, "[DEBUG] DNS_HANDLER: Consulta DoH para '$domain' falló. Construyendo respuesta de error (SERVFAIL).")
                DNSPacketBuilder.buildDnsErrorResponse(originalPacketForResponse)
            }

            synchronized(vpnOutput) {
                vpnOutput.write(responsePacket.array(), 0, responsePacket.limit())
                Log.d(TAG, "[DEBUG] DNS_HANDLER: Respuesta DNS para '$domain' escrita en el túnel.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] DNS_HANDLER: Error al manejar la consulta DNS.", e)
        }
    }

    private fun performDohQuery(domain: String): List<String> {
        val queryData = DNSPacketBuilder.buildDnsQueryForDoH(domain)
        val requestBody = queryData.toRequestBody("application/dns-message".toMediaTypeOrNull())
        val request = Request.Builder().url(serverUrl).post(requestBody).addHeader("Accept", "application/dns-message").build()
        try {
            Log.d(TAG, "[DEBUG] DOH: Enviando petición para '$domain' a $serverUrl")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "[DEBUG] DOH: Respuesta exitosa para '$domain'")
                return DNSPacketBuilder.parseDnsResponseFromDoH(response.body?.bytes() ?: byteArrayOf())
            } else {
                Log.e(TAG, "[DEBUG] DOH: Respuesta NO exitosa para $domain: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] DOH: EXCEPCIÓN en la petición para '$domain'", e)
        }
        return emptyList()
    }

    private fun createProtectedHttpClient(): OkHttpClient {
        Log.d(TAG, "[DEBUG] Creando cliente HTTP protegido...")
        val protectedSocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket {
                val s = Socket()
                Log.d(TAG, "[DEBUG] PROTECT: Protegiendo socket (sin args) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: String?, port: Int): Socket {
                val s = Socket(host, port)
                Log.d(TAG, "[DEBUG] PROTECT: Protegiendo socket (host, port) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val s = Socket(host, port, localHost, localPort)
                Log.d(TAG, "[DEBUG] PROTECT: Protegiendo socket (host, port, localHost, localPort) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val s = Socket(host, port)
                Log.d(TAG, "[DEBUG] PROTECT: Protegiendo socket (InetAddress, port) $s")
                protect(s)
                return s
            }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                val s = Socket(address, port, localAddress, localPort)
                Log.d(TAG, "[DEBUG] PROTECT: Protegiendo socket (InetAddress, port, localAddress, localPort) $s")
                protect(s)
                return s
            }
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hostname = try { URL(serverUrl).host } catch (e: Exception) { "cloudflare-dns.com" }
        Log.d(TAG, "[DEBUG] Configurando anclaje de certificado para el hostname: '$hostname'")

        isCertificatePinningFailed = false

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                Log.d(TAG, "[DEBUG] TRUST_MANAGER: Verificando certificado del servidor para $hostname")
                val certificate = chain?.get(0) ?: throw CertificateException("Cadena de certificados vacía")
                if (!CertificateManager.verifyCertificatePin(certificate, hostname, preferences)) {
                    isCertificatePinningFailed = true
                    showCertificateWarningNotification()
                    Log.e(TAG, "[DEBUG] TRUST_MANAGER: ¡ANCLAJE DE CERTIFICADO FALLÓ!")
                    throw CertificateException("Anclaje de certificado falló para $hostname.")
                }
                Log.d(TAG, "[DEBUG] TRUST_MANAGER: Verificación de certificado exitosa.")
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

        return OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun loadDnsServers() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        serverUrl = preferences.getString("SERVER_URL", "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"
        Log.d(TAG, "[DEBUG] Cargada URL del servidor DoH: $serverUrl")
    }

    private fun establish(): ParcelFileDescriptor? {
        Log.d(TAG, "[DEBUG] establish() llamado...")
        return try {
            val builder = Builder()
            builder.setSession("DnsVpn")
            builder.addAddress("10.0.0.2", 32) // Dirección para la interfaz VPN
            builder.addDnsServer("10.0.0.1")   // Servidor DNS que esta VPN interceptará

            // --- IMPORTANTE: NO AÑADIR RUTA GLOBAL SI SOLO SE QUIERE MANEJAR DNS ---
            // builder.addRoute("0.0.0.0", 0) // Esta línea haría que TODO el tráfico pase por la VPN.
            // --- FIN DE LA MODIFICACIÓN IMPORTANTE ---

            builder.addDisallowedApplication(packageName) // Para que la propia app VPN no use el túnel para sus peticiones DoH.

            Log.d(TAG, "[DEBUG] Configuración de VpnService.Builder completada. Llamando a establish()...")
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Error en VpnService.Builder.establish()", e)
            null
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "[DEBUG] DnsVpnService.onDestroy")
        stopVpn()
        super.onDestroy()
    }

    private fun showVpnNotification(title: String, text: String) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "DNS VPN Service"
            val descriptionText = "Notificaciones del servicio DNS VPN"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showCertificateWarningNotification() {
        val intent = Intent(this, CertificateVerificationActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.cert_status_red)
            .setContentTitle("¡Error de Certificado!")
            .setContentText("El certificado del servidor no es de confianza. Pulsa para verificar.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}