package com.ventaone.dnsvpn
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import okhttp3.*
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

class CertificateVerificationActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var certInfoTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var verifyButton: Button
    private lateinit var configServerButton: Button
    private lateinit var pinCertButton: Button

    // Variable para almacenar el certificado actual
    private var currentCertificate: X509Certificate? = null

    private val TAG = "CertVerification"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_certificate_verification)

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView)
        certInfoTextView = findViewById(R.id.certInfoTextView)
        progressBar = findViewById(R.id.progressBar)
        verifyButton = findViewById(R.id.verifyButton)
        configServerButton = findViewById(R.id.configServerButton)
        pinCertButton = findViewById(R.id.pinCertButton)

        // Setup verify button
        verifyButton.setOnClickListener {
            verifyCertificate()
        }

        // Setup config server button to open Settings
        configServerButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            // Opcionalmente podemos añadir un extra para indicar que queremos ir a la sección de servidor
            intent.putExtra("OPEN_SECTION", "SERVER")
            startActivity(intent)
        }

        // Setup pin certificate button
        pinCertButton.setOnClickListener {
            pinCurrentCertificate()
        }

        // Initial verification
        verifyCertificate()
    }

    private fun verifyCertificate() {
        // Show progress and hide info
        progressBar.visibility = View.VISIBLE
        statusTextView.text = "Estado: Verificando..."
        certInfoTextView.text = ""

        // Get server URL from preferences
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        var serverUrl = preferences.getString("SERVER_URL", "cloudflare-dns.com") ?: "cloudflare-dns.com"

        // Ensure URL has https:// prefix
        if (!serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
            serverUrl = "https://$serverUrl"
        }

        // Create a client with a short timeout
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .build()

        // Run verification in background thread
        thread {
            try {
                val request = Request.Builder()
                    .url(serverUrl)
                    .build()

                client.newCall(request).execute().use { response ->
                    // Get the certificate chain
                    val certificateChain = response.handshake?.peerCertificates

                    if (certificateChain.isNullOrEmpty()) {
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            statusTextView.text = "Estado: Error - No se pudo obtener el certificado"
                            certInfoTextView.text = "No se pudo obtener información del certificado para $serverUrl"
                        }
                        return@thread
                    }

                    // Get the server's certificate (first in the chain)
                    val serverCert = certificateChain[0] as X509Certificate
                    currentCertificate = serverCert // Store the certificate

                    // Format certificate information
                    val certInfo = buildCertificateInfo(serverCert, serverUrl)

                    // Update UI on main thread
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        statusTextView.text = "Estado: Verificado correctamente"
                        certInfoTextView.text = certInfo

                        // Enable the pin button
                        pinCertButton.isEnabled = true
                    }
                }
            } catch (e: SSLPeerUnverifiedException) {
                Log.e(TAG, "SSL verification failed", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    statusTextView.text = "Estado: Error - Verificación SSL fallida"
                    certInfoTextView.text = "Error en la verificación del certificado: ${e.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    statusTextView.text = "Estado: Error de conexión"
                    certInfoTextView.text = "Error al conectar con el servidor: ${e.message}"
                }
            }
        }
    }

    private fun buildCertificateInfo(cert: X509Certificate, serverUrl: String): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val subject = cert.subjectX500Principal
        val issuer = cert.issuerX500Principal
        val validFrom = dateFormat.format(cert.notBefore)
        val validTo = dateFormat.format(cert.notAfter)
        val fingerprint = cert.encoded.joinToString(":") { "%02X".format(it) }

        return "Información del certificado:\n" +
                "- Servidor: $serverUrl\n" +
                "- Sujeto: ${extractCN(subject)}\n" +
                "- Expedido por: ${extractCN(issuer)}\n" +
                "- Válido desde: $validFrom\n" +
                "- Válido hasta: $validTo\n" +
                "- Huella digital (SHA-1): ${fingerprint.take(59)}..."
    }

    private fun extractCN(principal: X500Principal): String {
        val dn = principal.name
        val cnPattern = "CN=([^,]+)".toRegex()
        val match = cnPattern.find(dn)
        return match?.groupValues?.get(1) ?: dn
    }

    // Add method to pin the current certificate
    // ... (resto de la clase CertificateVerificationActivity)

    private fun pinCurrentCertificate() {
        if (currentCertificate == null) {
            Toast.makeText(this, "No hay certificado para guardar. Verifique primero.", Toast.LENGTH_SHORT).show()
            return
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        // OBTENER LA URL COMPLETA
        val fullServerUrl = preferences.getString("DOH_SERVER_URL", "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"

        // Extraer hostname de la URL completa
        val hostname = try {
            URL(fullServerUrl).host
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo hostname de $fullServerUrl", e)
            // Plan B si la URL no es válida
            fullServerUrl.replace("https://", "").split("/")[0]
        }

        // Guardar el pin del certificado
        CertificateManager.saveCertificatePin(currentCertificate!!, hostname, preferences)

        // PASO 3: Limpiar el estado de error
        DnsVpnService.isCertificatePinningFailed = false

        // Notificar al usuario y actualizar UI
        Toast.makeText(this, "Certificado guardado para $hostname. Error solucionado.", Toast.LENGTH_LONG).show()

        // Simular una actualización de estado para que la MainActivity reaccione si está abierta
        // (Esto es opcional pero mejora la experiencia)
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(mainActivityIntent)

        finish() // Cerrar esta actividad
    }

}