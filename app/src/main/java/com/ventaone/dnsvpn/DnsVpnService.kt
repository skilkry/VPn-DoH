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
    private var isRunning = false
    private var thread: Thread? = null
    private var serverUrl = "https://cloudflare-dns.com/dns-query" // Default value
    private lateinit var certificateMonitor: CertificateMonitor
    private lateinit var dnsLeakProtection: DnsLeakProtection
    private var blockedDnsServers: List<String> = emptyList()

    companion object {
        var isServiceRunning = false
        var isCertificatePinningFailed = false
        const val ACTION_RESTART_VPN = "com.ventaone.dnsvpn.ACTION_RESTART_VPN"
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "DnsVpnChannel"
    }

    override fun onCreate() {
        super.onCreate()
        certificateMonitor = CertificateMonitor(this)
        dnsLeakProtection = DnsLeakProtection(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (thread == null) {
            loadDnsServers()
            loadLeakProtectionConfig()
            client = createProtectedHttpClient()

            if (isCertificatePinningFailed) {
                Toast.makeText(this, "Error de anclaje de certificado. Verifíquelo en la app.", Toast.LENGTH_LONG).show()
                stopVpn()
                return START_NOT_STICKY
            }

            vpnInterface = establish()
            if (vpnInterface != null) {
                isRunning = true
                thread = Thread(this, "DnsVpnThread")
                thread?.start()
                isServiceRunning = true
                showVpnNotification("VPN Activa", "El servicio DNS está protegiendo tu red.")
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun run() {
        Log.d(TAG, "Hilo de la VPN iniciado.")
        val vpnInput = FileInputStream(vpnInterface?.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface?.fileDescriptor)
        val packetBuffer = ByteBuffer.allocate(32767)

        try {
            while (isRunning) {
                val size = vpnInput.read(packetBuffer.array())
                if (size > 0) {
                    packetBuffer.limit(size)
                    val ipHeader = PacketParser.parseIP4Header(packetBuffer)

                    if (ipHeader.protocol == Protocol.UDP) {
                        val udpHeader = PacketParser.parseUDPHeader(packetBuffer)
                        if (udpHeader.destinationPort == 53) {
                            val destIp = ipHeader.destinationAddress.hostAddress
                            if (destIp in blockedDnsServers) {
                                Log.w(TAG, "Paquete DNS a servidor bloqueado ($destIp) descartado.")
                            } else {
                                thread {
                                    handleDnsQuery(packetBuffer.duplicate(), vpnOutput)
                                }
                            }
                        }
                    }
                }
                packetBuffer.clear()
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Error en el hilo de la VPN", e)
        } finally {
            stopVpn()
        }
    }

    private fun handleDnsQuery(queryPacket: ByteBuffer, vpnOutput: FileOutputStream) {
        try {
            val (domain, _) = DNSPacketBuilder.extractDomainAndIdFromQuery(queryPacket) ?: return
            val ips = performDohQuery(domain)

            val responsePacket = if (ips.isNotEmpty()) {
                DNSPacketBuilder.buildDnsResponse(queryPacket, ips)
            } else {
                Log.w(TAG, "La consulta DoH para '$domain' falló o no devolvió IPs. Enviando SERVFAIL.")
                DNSPacketBuilder.buildDnsErrorResponse(queryPacket)
            }

            synchronized(vpnOutput) {
                vpnOutput.write(responsePacket.array(), 0, responsePacket.limit())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al manejar la consulta DNS", e)
        }
    }

    private fun performDohQuery(domain: String): List<String> {
        val queryData = DNSPacketBuilder.buildDnsQueryForDoH(domain)
        val requestBody = queryData.toRequestBody("application/dns-message".toMediaTypeOrNull())
        val request = Request.Builder().url(serverUrl).post(requestBody).addHeader("Accept", "application/dns-message").build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return DNSPacketBuilder.parseDnsResponseFromDoH(response.body?.bytes() ?: byteArrayOf())
            } else {
                Log.e(TAG, "Respuesta DoH no exitosa para $domain: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "La petición DoH falló para el dominio $domain", e)
        }
        return emptyList()
    }

    private fun createProtectedHttpClient(): OkHttpClient {
        val protectedSocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = Socket().also { protect(it) }
            override fun createSocket(host: String?, port: Int): Socket = Socket(host, port).also { protect(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = Socket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket = Socket(host, port).also { protect(it) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = Socket(address, port, localAddress, localPort).also { protect(it) }
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hostname = try { URL(serverUrl).host } catch (e: Exception) {
            Log.e(TAG, "URL del servidor inválida: $serverUrl. Usando 'cloudflare-dns.com' como fallback.")
            "cloudflare-dns.com"
        }

        isCertificatePinningFailed = false

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.get(0) ?: throw CertificateException("Cadena de certificados vacía")
                if (!CertificateManager.verifyCertificatePin(certificate, hostname, preferences)) {
                    isCertificatePinningFailed = true
                    showCertificateWarningNotification()
                    throw CertificateException("Anclaje de certificado falló para $hostname.")
                }
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

        return OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun loadLeakProtectionConfig() {
        blockedDnsServers = dnsLeakProtection.getDnsServersToBlock()
    }

    private fun loadDnsServers() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        serverUrl = preferences.getString("SERVER_URL", "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"
    }

    private fun establish(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
            builder.setSession("DnsVpn")
            builder.addAddress("10.0.0.2", 32)

            // --- INICIO DE LA CORRECCIÓN DEFINITIVA ---
            // Usar una IP privada que no será bloqueada por la protección de fugas.
            builder.addDnsServer("10.0.0.1")
            // --- FIN DE LA CORRECCIÓN DEFINITIVA ---

            builder.addRoute("0.0.0.0", 0)
            builder.addDisallowedApplication(packageName)
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Error al establecer el túnel VPN", e)
            null
        }
    }

    private fun stopVpn() {
        try {
            thread?.interrupt()
            thread = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error al cerrar vpnInterface", e)
        } finally {
            isRunning = false
            isServiceRunning = false
            stopForeground(true)
            stopSelf()
            Log.d(TAG, "Servicio VPN detenido.")
        }
    }

    override fun onDestroy() {
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