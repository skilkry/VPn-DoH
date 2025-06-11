/**
 * DnsVpnService - Clase que lleva el flujo de la aplicación.
 *
 * @authors skilkry (Daniel Sardina)  ¢ Daniel Enriquez Cayuelas
 * @since 2025-04-01
 * Copyright (c) 2025 skilkry. All rights reserved.
 * Licenciado bajo la Licencia MIT.
 */

package com.ventaone.dnsvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.VoiceInteractor
// import android.content.ContentValues.TAG // Ya no se usa TAG aquí, Log usa su propio TAG
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle // AÑADIDO: Para los extras del intent
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.ventaone.dnsvpn.network.PacketParser
import com.ventaone.dnsvpn.network.Protocol
// import com.ventaone.dnsvpn.network.UDPHeader // Asegúrate de que UDPHeader esté importado si PacketParser lo devuelve directamente
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
    @Volatile private var isRunning = false // Estado interno del hilo de procesamiento
    private var thread: Thread? = null
    private var serverUrl = "https://cloudflare-dns.com/dns-query"
    private lateinit var certificateMonitor: CertificateMonitor // Aunque no se usa activamente en este archivo

    private val TAG_SERVICE = "DnsVpnService" // TAG para logging

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "my_notification_channel_id"
        const val ACTION_VPN_CONNECTING = "com.ventaone.dnsvpn.VPN_CONNECTING"
        const val ACTION_VPN_CONNECTED = "com.ventaone.dnsvpn.VPN_CONNECTED"
        const val ACTION_VPN_DISCONNECTED = "com.ventaone.dnsvpn.VPN_DISCONNECTED"
        const val ACTION_VPN_ERROR = "com.ventaone.dnsvpn.VPN_ERROR"
        const val EXTRA_ERROR_MESSAGE = "com.ventaone.dnsvpn.EXTRA_ERROR_MESSAGE"
        const val ACTION_STOP_VPN = "com.ventaone.dnsvpn.ACTION_STOP_VPN"
        const val ACTION_RELOAD_CONFIG_AND_RESTART = "com.ventaone.dnsvpn.ACTION_RELOAD_CONFIG_AND_RESTART"

        @Volatile var isServiceRunning: Boolean = false // Estado global del servicio VPN (conectado/desconectado)
        @Volatile var isCertificatePinningFailed: Boolean = false

        // MODIFICADO: Helper para enviar broadcasts, ahora acepta extras
        fun sendAction(context: Context, action: String, extras: Bundle? = null) {
            val intent = Intent(action)
            extras?.let { intent.putExtras(it) }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.d("DnsVpnService_Companion", "[BROADCAST] Enviando acción: $action")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG_SERVICE, "onCreate")
        certificateMonitor = CertificateMonitor(this) // Inicializar si se usa
        // Inicialmente el servicio no está corriendo en el sentido de VPN activa
        isServiceRunning = false
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG_SERVICE, "onStartCommand. Action: ${intent?.action}, Hilo actual: $thread, isRunning: $isRunning")

        if (intent?.action == ACTION_STOP_VPN) { // MODIFICADO: Usar la constante
            Log.d(TAG_SERVICE, "Acción STOP_VPN recibida.")
            stopVpn()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RELOAD_CONFIG_AND_RESTART) {
            Log.d(TAG_SERVICE, "Acción RELOAD_CONFIG_AND_RESTART recibida.")
            // Detener primero si está corriendo para asegurar limpieza
            if (isRunning || isServiceRunning) {
                stopVpn() // Esto enviará DISCONNECTED. Esperar un poco antes de reiniciar.
            }
            // Programar un reinicio después de una breve pausa para permitir que stopVpn complete
            // Esto es una simplificación; en un escenario real, manejarías la secuencia más cuidadosamente.
            thread {
                Thread.sleep(1000) // Dar tiempo a que se detenga completamente
                Log.d(TAG_SERVICE, "Intentando reiniciar VPN después de reload.")
                startVpnLogic()
            }.start()
            return START_STICKY
        }


        // Solo iniciar si no está ya corriendo o intentando correr
        if (thread == null || !isRunning) {
            Log.d(TAG_SERVICE, "Iniciando secuencia de arranque de VPN.")
            startVpnLogic()
        } else {
            Log.d(TAG_SERVICE, "onStartCommand llamado pero el hilo ya existe y/o está corriendo. isRunning: $isRunning")
            // Si ya está corriendo (conectado), reenviar el estado actual por si la UI lo perdió
            if (isServiceRunning) {
                sendAction(this, ACTION_VPN_CONNECTED)
            } else if (isRunning) { // Si el hilo isRunning pero isServiceRunning es false, está conectando
                sendAction(this, ACTION_VPN_CONNECTING)
            }
        }
        return START_STICKY
    }

    private fun startVpnLogic() {
        // AÑADIDO: Notificar que se está intentando conectar
        sendAction(this, ACTION_VPN_CONNECTING)
        isRunning = true // Marcamos que el proceso de conexión/operación ha comenzado

        loadDnsServers() // Cargar configuración
        // Recrear cliente HTTP en cada intento de conexión para asegurar que usa la config más reciente
        // y para manejar el pinning de certificado correctamente.
        client = createProtectedHttpClient() // Esto puede lanzar excepción si el pinning falla

        if (isCertificatePinningFailed) {
            Log.e(TAG_SERVICE, "Fallo de anclaje de certificado detectado. Deteniendo.")
            Toast.makeText(this, "Error de anclaje de certificado. Verifíquelo en la app.", Toast.LENGTH_LONG).show()
            val errorBundle = Bundle().apply { putString(EXTRA_ERROR_MESSAGE, "Error de anclaje de certificado.") }
            sendAction(this, ACTION_VPN_ERROR, errorBundle)
            // stopVpn() se llamará desde el catch o después, asegurando que isRunning y isServiceRunning se pongan a false
            // y se envíe DISCONNECTED si no se envió ERROR.
            // Para asegurar que no intente más:
            isRunning = false // Detener lógica de este intento
            isServiceRunning = false // No está conectado
            sendAction(this, ACTION_VPN_DISCONNECTED) // Opcional si ERROR ya implica desconexión para la UI
            stopSelf() // Detener el servicio si el pinning falla al inicio.
            return
        }

        vpnInterface = establish()
        if (vpnInterface != null) {
            Log.d(TAG_SERVICE, "Interfaz VPN establecida correctamente.")
            // 'isRunning' ya es true. El hilo se crea aquí.
            thread = Thread(this, "DnsVpnThread")
            thread?.start()
            // AÑADIDO: Una vez que el hilo está listo para empezar y la interfaz está OK:
            isServiceRunning = true // Marcar como conectado y operativo
            sendAction(this, ACTION_VPN_CONNECTED)
            showVpnNotification("VPN Activa (Solo DNS)", "El servicio DNS está protegiendo tus consultas.")
        } else {
            Log.e(TAG_SERVICE, "La interfaz VPN no se pudo establecer.")
            val errorBundle = Bundle().apply { putString(EXTRA_ERROR_MESSAGE, "No se pudo establecer la interfaz VPN.") }
            sendAction(this, ACTION_VPN_ERROR, errorBundle)
            isRunning = false
            isServiceRunning = false
            stopSelf() // Detener servicio si la interfaz no se crea
        }
    }


    override fun run() {
        Log.d(TAG_SERVICE, "RUN: Hilo de la VPN iniciado. Entrando en bucle de lectura.")
        val currentVpnInterface = vpnInterface ?: run {
            Log.e(TAG_SERVICE, "RUN: vpnInterface es null al inicio de run(). Deteniendo hilo.")
            if (isRunning) { // Si se suponía que debía estar corriendo
                val errorBundle = Bundle().apply { putString(EXTRA_ERROR_MESSAGE, "Error interno: Interfaz VPN no disponible.") }
                sendAction(this, ACTION_VPN_ERROR, errorBundle)
            }
            isRunning = false
            isServiceRunning = false // Asegurar estado
            return
        }
        // ... el resto de tu método run() sin cambios ...
        // Solo considera el bloque finally y los catch

        try {
            // ... tu bucle while (isRunning) ...
            while (isRunning) {
                // ... tu lógica de lectura y procesamiento de paquetes ...
                val vpnInput = FileInputStream(currentVpnInterface.fileDescriptor) // Mover dentro del try
                val vpnOutput = FileOutputStream(currentVpnInterface.fileDescriptor) // Mover dentro del try
                val packetBuffer = ByteBuffer.allocate(32767) // Mover dentro del try

                val size = vpnInput.read(packetBuffer.array())
                if (size > 0) {
                    Log.d(TAG_SERVICE, "[DEBUG] RUN: Leídos $size bytes del túnel VPN.")
                    packetBuffer.limit(size)

                    val packetToForwardCopy = ByteBuffer.allocate(size)
                    packetToForwardCopy.put(packetBuffer.array(), 0, size)
                    packetToForwardCopy.flip()
                    packetBuffer.position(0)

                    var handledByDnsLogic = false
                    try {
                        val ipHeader = PacketParser.parseIP4Header(packetBuffer)
                        // Log.d(TAG_SERVICE, "[DEBUG] RUN: Paquete IPV4 parseado. Proto: ${ipHeader.protocol}, Origen: ${ipHeader.sourceAddress.hostAddress}, Dst: ${ipHeader.destinationAddress.hostAddress}")

                        if (ipHeader.protocol == Protocol.UDP) {
                            val udpHeader = PacketParser.parseUDPHeader(packetBuffer)
                            // Log.d(TAG_SERVICE, "[DEBUG] RUN: Paquete UDP parseado. SrcPort: ${udpHeader.sourcePort}, DstPort: ${udpHeader.destinationPort}")

                            if (udpHeader.destinationPort == 53 && ipHeader.destinationAddress.hostAddress == "10.0.0.1") {
                                // Log.d(TAG_SERVICE, "[DEBUG] RUN: Paquete DNS para nuestro resolvedor detectado! Procesando.")
                                val originalIpPacketForDnsHandler = packetToForwardCopy.duplicate()
                                thread {
                                    handleDnsQuery(originalIpPacketForDnsHandler, vpnOutput)
                                }
                                handledByDnsLogic = true
                            }
                        }

                        if (!handledByDnsLogic) {
                            var destPortInfo = "N/A"
                            //  if (ipHeader.protocol == Protocol.UDP) {
                            //      val tempCopyForUdpPort = packetToForwardCopy.duplicate()
                            //      PacketParser.parseIP4Header(tempCopyForUdpPort)
                            //      destPortInfo = PacketParser.parseUDPHeader(tempCopyForUdpPort).destinationPort.toString()
                            //  }
                            //  Log.d(TAG_SERVICE, "[DEBUG] RUN: Paquete NO DNS (Proto: ${ipHeader.protocol}, Dst: ${ipHeader.destinationAddress.hostAddress}:${destPortInfo}). Escribiendo a vpnOutput.")
                            synchronized(vpnOutput) {
                                vpnOutput.write(packetToForwardCopy.array(), 0, packetToForwardCopy.limit())
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG_SERVICE, "[DEBUG] RUN: Error al parsear/procesar. Reescribiendo original.", e)
                        synchronized(vpnOutput) {
                            vpnOutput.write(packetToForwardCopy.array(), 0, packetToForwardCopy.limit())
                        }
                    }
                } else if (size == 0) {
                    // Continue
                } else { // size < 0
                    Log.e(TAG_SERVICE, "RUN: Error al leer del túnel VPN, read() devolvió $size. Saliendo.")
                    isRunning = false // Provocará la salida del bucle
                }
                packetBuffer.clear()
            } // fin while
        } catch (e: IOException) {
            if (isRunning) { // Si el error no fue por un cierre intencional
                Log.e(TAG_SERVICE, "RUN: IOException en el bucle (puede ser por cierre de interfaz si isRunning se puso a false). Error: ${e.message}", e)
                // AÑADIDO: Notificar error si la VPN se suponía que estaba activa
                val errorBundle = Bundle().apply { putString(EXTRA_ERROR_MESSAGE, "Error de Red VPN: ${e.localizedMessage}") }
                sendAction(this, ACTION_VPN_ERROR, errorBundle)
            } else {
                Log.d(TAG_SERVICE,"RUN: IOException (esperada por cierre) en el bucle de lectura.")
            }
        } catch (e: Exception) {
            if (isRunning) { // Si el error no fue por un cierre intencional
                Log.e(TAG_SERVICE, "RUN: Error crítico (no IOException) en el bucle de lectura.", e)
                // AÑADIDO: Notificar error
                val errorBundle = Bundle().apply { putString(EXTRA_ERROR_MESSAGE, "Error Interno VPN: ${e.localizedMessage}") }
                sendAction(this, ACTION_VPN_ERROR, errorBundle)
            }
        } finally {
            Log.d(TAG_SERVICE, "RUN: Hilo finalizando. Saliendo del bucle de lectura.")
            // Asegurar que el estado global refleje que el servicio ya no está procesando activamente
            // La llamada a stopVpn() o una lógica similar se encargará de enviar DISCONNECTED
            // si 'isRunning' se puso a false debido a un error.
            if (isRunning) { // Si salió del bucle pero isRunning seguía true (no debería pasar)
                isRunning = false; // Forzar
            }
            if (isServiceRunning) { // Si el servicio creía que estaba conectado
                // Esto significa que el hilo murió inesperadamente.
                // stopVpn() debería ser llamado o su lógica replicada para limpiar y notificar.
                Log.w(TAG_SERVICE, "RUN: Hilo finalizó pero isServiceRunning era true. Forzando limpieza y notificación.")
                // En lugar de llamar a stopVpn directamente que puede ser complejo desde aquí,
                // aseguramos el estado y notificamos.
                isServiceRunning = false
                sendAction(this, ACTION_VPN_DISCONNECTED) // O ACTION_VPN_ERROR si es apropiado
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        Log.d(TAG_SERVICE, "stopVpn() llamado. isRunning: $isRunning, isServiceRunning: $isServiceRunning")
        val wasPreviouslyRunning = isServiceRunning // Capturar estado antes de modificarlo

        isRunning = false // Detener el bucle en run()
        isServiceRunning = false // Marcar que el servicio ya no está conectado/operativo

        thread?.interrupt() // Interrumpir el hilo si existe
        thread = null

        try {
            vpnInterface?.close()
            Log.d(TAG_SERVICE, "Interfaz VPN cerrada.")
        } catch (e: IOException) {
            Log.e(TAG_SERVICE, "Error al cerrar vpnInterface", e)
        } finally {
            vpnInterface = null
            // AÑADIDO: Notificar que se ha desconectado, solo si realmente estaba corriendo o intentando.
            if (wasPreviouslyRunning) { // O si queremos notificar siempre que se llama a stop
                sendAction(this, ACTION_VPN_DISCONNECTED)
            }
            stopForeground(true)
            stopSelf() // Detener el servicio
            Log.d(TAG_SERVICE, "Servicio VPN y notificación detenidos. stopSelf() llamado.")
        }
    }

    // ... handleDnsQuery, performDohQuery, createProtectedHttpClient, loadDnsServers, establish ...
    // Asegúrate que createProtectedHttpClient SÍ lance CertificateException si el pinning falla,
    // para que startVpnLogic() pueda capturarlo y enviar ACTION_VPN_ERROR.
    // Tu código actual ya hace esto con isCertificatePinningFailed.

    override fun onDestroy() {
        Log.d(TAG_SERVICE, "onDestroy")
        // stopVpn() ya debería haber sido llamado si el servicio se detiene limpiamente.
        // Si se destruye abruptamente, asegurar el estado.
        if (isServiceRunning || isRunning) { //
            stopVpn() //
        }
        super.onDestroy()
    }

    // ... tus métodos de notificación ...
    // Considera modificar showVpnNotification para que acepte el estado y muestre mensajes diferentes
    // por ejemplo "Conectando..." etc.
    private fun showVpnNotification(title: String, text: String) { //
        createNotificationChannel() //
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID) //
            .setSmallIcon(R.drawable.ic_notification_vpn_icon) // REEMPLAZA con tu icono de notificación
            .setContentTitle(title) //
            .setContentText(text) //
            .setOngoing(true) // Para que no se pueda descartar fácilmente
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) //
            .build() //
        startForeground(NOTIFICATION_ID, notification) //
    }

    private fun createNotificationChannel() { //
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { //
            val name = "DNS VPN Service" //
            val descriptionText = "Notificaciones del servicio DNS VPN" //
            val importance = NotificationManager.IMPORTANCE_DEFAULT //
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply { //
                description = descriptionText //
            } //
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager //
            notificationManager.createNotificationChannel(channel) //
        }
    }

    private fun showCertificateWarningNotification() { //
        val intent = Intent(this, CertificateVerificationActivity::class.java) //
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) //
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID) //
            .setSmallIcon(R.drawable.cert_status_red) //
            .setContentTitle("¡Error de Certificado!") //
            .setContentText("El certificado del servidor no es de confianza. Pulsa para verificar.") //
            .setPriority(NotificationCompat.PRIORITY_HIGH) //
            .setContentIntent(pendingIntent) //
            .setAutoCancel(true) //
            .build() //
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager //
        notificationManager.notify(NOTIFICATION_ID + 1, notification) //
    }
    // Asegúrate que tus métodos handleDnsQuery, performDohQuery, createProtectedHttpClient,
    // loadDnsServers y establish están como en tu archivo original o como los necesites.
    // La lógica principal de los broadcasts está en onStartCommand, startVpnLogic, run (finally/catch) y stopVpn.
    private fun handleDnsQuery(queryIpPacket: ByteBuffer, vpnOutput: FileOutputStream) {
        Log.d(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Iniciando manejo de consulta DNS.")
        try {
            val (domain, _) = DNSPacketBuilder.extractDomainAndIdFromQuery(queryIpPacket.duplicate()) ?: run {
                Log.w(TAG_SERVICE, "[DEBUG] DNS_HANDLER: No se pudo extraer el dominio. Abortando.")
                return
            }
            Log.d(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Dominio extraído: '$domain'. Realizando consulta DoH.")

            val ips = performDohQuery(domain)

            val originalPacketForResponse = queryIpPacket.duplicate()
            originalPacketForResponse.position(0)

            val responsePacket = if (ips.isNotEmpty()) {
                Log.d(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Consulta DoH exitosa para '$domain'. IPs: $ips. Construyendo respuesta.")
                DNSPacketBuilder.buildDnsResponse(originalPacketForResponse, ips)
            } else {
                Log.w(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Consulta DoH para '$domain' falló. Construyendo respuesta de error (SERVFAIL).")
                DNSPacketBuilder.buildDnsErrorResponse(originalPacketForResponse)
            }

            synchronized(vpnOutput) {
                vpnOutput.write(responsePacket.array(), 0, responsePacket.limit())
                Log.d(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Respuesta DNS para '$domain' escrita en el túnel.")
            }
        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "[DEBUG] DNS_HANDLER: Error al manejar la consulta DNS.", e)
        }
    }

    private fun performDohQuery(domain: String): List<String> {
        val queryData = DNSPacketBuilder.buildDnsQueryForDoH(domain)
        val requestBody = queryData.toRequestBody("application/dns-message".toMediaTypeOrNull())
        val request = VoiceInteractor.Request.Builder().url(serverUrl).post(requestBody).addHeader("Accept", "application/dns-message").build()
        try {
            Log.d(TAG_SERVICE, "[DEBUG] DOH: Enviando petición para '$domain' a $serverUrl")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG_SERVICE, "[DEBUG] DOH: Respuesta exitosa para '$domain'")
                return DNSPacketBuilder.parseDnsResponseFromDoH(response.body?.bytes() ?: byteArrayOf())
            } else {
                Log.e(TAG_SERVICE, "[DEBUG] DOH: Respuesta NO exitosa para $domain: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "[DEBUG] DOH: EXCEPCIÓN en la petición para '$domain'", e)
        }
        return emptyList()
    }

    private fun createProtectedHttpClient(): OkHttpClient {
        Log.d(TAG_SERVICE, "[DEBUG] Creando cliente HTTP protegido...")
        val protectedSocketFactory = object : SocketFactory() { /*... tu código ...*/
            override fun createSocket(): Socket {
                val s = Socket()
                Log.d(TAG_SERVICE, "[DEBUG] PROTECT: Protegiendo socket (sin args) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: String?, port: Int): Socket {
                val s = Socket(host, port)
                Log.d(TAG_SERVICE, "[DEBUG] PROTECT: Protegiendo socket (host, port) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val s = Socket(host, port, localHost, localPort)
                Log.d(TAG_SERVICE, "[DEBUG] PROTECT: Protegiendo socket (host, port, localHost, localPort) $s")
                protect(s)
                return s
            }
            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val s = Socket(host, port)
                Log.d(TAG_SERVICE, "[DEBUG] PROTECT: Protegiendo socket (InetAddress, port) $s")
                protect(s)
                return s
            }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                val s = Socket(address, port, localAddress, localPort)
                Log.d(TAG_SERVICE, "[DEBUG] PROTECT: Protegiendo socket (InetAddress, port, localAddress, localPort) $s")
                protect(s)
                return s
            }
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hostname = try { URL(serverUrl).host } catch (e: Exception) { "cloudflare-dns.com" } // Default hostname
        Log.d(TAG_SERVICE, "[DEBUG] Configurando anclaje de certificado para el hostname: '$hostname'")

        isCertificatePinningFailed = false // Resetear antes de cada intento de creación de cliente

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                Log.d(TAG_SERVICE, "[DEBUG] TRUST_MANAGER: Verificando certificado del servidor para $hostname")
                val certificate = chain?.get(0) ?: throw CertificateException("Cadena de certificados vacía")
                if (!CertificateManager.verifyCertificatePin(certificate, hostname, preferences)) {
                    isCertificatePinningFailed = true
                    showCertificateWarningNotification()
                    Log.e(TAG_SERVICE, "[DEBUG] TRUST_MANAGER: ¡ANCLAJE DE CERTIFICADO FALLÓ!")
                    throw CertificateException("Anclaje de certificado falló para $hostname.") // Esto será atrapado por quien llame a una petición HTTP
                }
                Log.d(TAG_SERVICE, "[DEBUG] TRUST_MANAGER: Verificación de certificado exitosa.")
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

        return OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // .hostnameVerifier { _, _ -> true } // Considera si necesitas un HostnameVerifier específico. Si el cert es para el host correcto, no suele ser necesario.
            .build()
    }


    private fun loadDnsServers() { //
        val preferences = PreferenceManager.getDefaultSharedPreferences(this) //
        serverUrl = preferences.getString("SERVER_URL", "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query" //
        Log.d(TAG_SERVICE, "[DEBUG] Cargada URL del servidor DoH: $serverUrl") //
    }

    private fun establish(): ParcelFileDescriptor? { //
        Log.d(TAG_SERVICE, "[DEBUG] establish() llamado...") //
        return try { //
            val builder = Builder() //
            builder.setSession("DnsVpn") //
            builder.addAddress("10.0.0.2", 32) //
            builder.addDnsServer("10.0.0.1") //
            builder.addRoute("10.0.0.1", 32) //
            builder.addDisallowedApplication(packageName) //
            Log.d(TAG_SERVICE, "[DEBUG] Configuración de VpnService.Builder completada. Llamando a establish()...") //
            builder.establish() //
        } catch (e: Exception) { //
            Log.e(TAG_SERVICE, "[DEBUG] Error en VpnService.Builder.establish()", e) //
            null //
        }
    }
}