package com.ventaone.dnsvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity(), CertificateMonitor.CertificateStatusListener {
    private val VPN_REQUEST_CODE = 100
    private lateinit var dropdownController: DropdownMenuController
    private lateinit var powerButton: ImageButton
    private lateinit var certStatusIcon: ImageView
    private lateinit var certStatusText: TextView
    private lateinit var certificateMonitor: CertificateMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar componentes UI
        powerButton = findViewById(R.id.powerButton)
        val powerIcon: ImageView = findViewById(R.id.powerIcon)
        certStatusIcon = findViewById(R.id.certStatusIcon)
        certStatusText = findViewById(R.id.certStatusText)

        // Inicializar el controlador del menú desplegable
        val gearButton: ImageButton = findViewById(R.id.btnServer)
        val dnsButton: Button = findViewById(R.id.dnsButton)
        val certsButton: Button = findViewById(R.id.certsButton)
        val serverButton: Button = findViewById(R.id.serverButton)

        dropdownController = DropdownMenuController(
            gearButton,
            dnsButton,
            certsButton,
            serverButton
        )

        // Configurar navegación a diferentes pantallas
        certsButton.setOnClickListener {
            val intent = Intent(this, CertificateVerificationActivity::class.java)
            startActivity(intent)
        }

        serverButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Inicializar el monitor de certificados
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(this)

        // Actualizar UI según el estado actual del servicio
        updateUI(DnsVpnService.isServiceRunning)

        // Botón principal de encendido/apagado
        powerButton.setOnClickListener {
            if (!DnsVpnService.isServiceRunning) {
                Log.d("MainActivity", "Intentando iniciar VPN")
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                } else {
                    startVpnService()
                }
            } else {
                Log.d("MainActivity", "Intentando detener VPN")
                stopVpnService()
            }
        }
    }

    private fun startVpnService() {
        try {
            Log.d("MainActivity", "Iniciando servicio VPN")
            val intent = Intent(this, DnsVpnService::class.java)
            startService(intent)
            updateUI(true)
            Toast.makeText(this, "Conectado correctamente", Toast.LENGTH_SHORT).show()

            // Iniciar el monitoreo de certificados cuando el servicio VPN se inicia
            certificateMonitor.startMonitoring()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar VPN", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnService() {
        try {
            Log.d("MainActivity", "Deteniendo servicio VPN")
            val intent = Intent(this, DnsVpnService::class.java)
            intent.action = "STOP_VPN"
            startService(intent)  // Usamos startService con acción STOP_VPN en lugar de stopService
            updateUI(false)
            Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show()

            // Detener el monitoreo de certificados cuando el servicio VPN se detiene
            certificateMonitor.stopMonitoring()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al detener VPN", e)
            Toast.makeText(this, "Error al detener: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(running: Boolean) {
        if (running) {
            powerButton.setBackgroundResource(R.drawable.button_background_red)
        } else {
            powerButton.setBackgroundResource(R.drawable.button_background_green)
        }

        // Si el servicio no está en ejecución, mostramos el estado de certificado como "Sin verificar"
        if (!running) {
            updateCertificateStatusUI(CertificateMonitor.CertificateStatus.NOT_VERIFIED)
        }
    }

    private fun updateCertificateStatusUI(status: CertificateMonitor.CertificateStatus) {
        // Actualizar el icono y el texto según el estado del certificado
        when (status) {
            CertificateMonitor.CertificateStatus.NOT_VERIFIED -> {
                certStatusIcon.setImageResource(R.drawable.cert_status_gray)
                certStatusText.text = "Estado: Sin verificar"
            }
            CertificateMonitor.CertificateStatus.VALID -> {
                certStatusIcon.setImageResource(R.drawable.cert_status_green)
                certStatusText.text = "Estado: Certificado válido"
            }
            CertificateMonitor.CertificateStatus.INVALID -> {
                certStatusIcon.setImageResource(R.drawable.cert_status_red)
                certStatusText.text = "Estado: Certificado inválido"
            }
            CertificateMonitor.CertificateStatus.WARNING -> {
                certStatusIcon.setImageResource(R.drawable.cert_status_yellow)
                certStatusText.text = "Estado: Advertencia"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Permiso de VPN rechazado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Actualizar UI al volver a la aplicación
        updateUI(DnsVpnService.isServiceRunning)

        // Si el servicio está en ejecución, verificamos el estado del certificado de nuevo
        if (DnsVpnService.isServiceRunning) {
            // Reanudar el monitoreo si estaba activo anteriormente
            certificateMonitor.startMonitoring()
            // Actualizar el UI inmediatamente con el último estado conocido
            updateCertificateStatusUI(certificateMonitor.getCurrentStatus())
        }
    }

    override fun onPause() {
        super.onPause()
        // No detenemos el monitoreo aquí para que siga funcionando en segundo plano
    }

    override fun onDestroy() {
        super.onDestroy()
        // Detener el monitoreo y eliminar este listener
        certificateMonitor.removeListener(this)
        certificateMonitor.stopMonitoring()
    }

    // Implementación de la interfaz CertificateStatusListener
    override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) {
        // Este método se llamará cuando cambie el estado del certificado
        runOnUiThread {
            updateCertificateStatusUI(status)
        }
    }
}