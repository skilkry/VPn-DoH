package com.ventaone.dnsvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val VPN_REQUEST_CODE = 100
    private lateinit var dropdownController: DropdownMenuController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val powerButton: ImageButton = findViewById(R.id.powerButton)
        val powerIcon: ImageView = findViewById(R.id.powerIcon)
        val startVpnButton: Button = findViewById(R.id.onButton)
        val stopVpnButton: Button = findViewById(R.id.offButton)

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

        // Eliminamos el click listener original del botón certsButton
        // ya que ahora está manejado por el DropdownMenuController

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

        // Botón ON
        startVpnButton.setOnClickListener {
            if (!DnsVpnService.isServiceRunning) {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                } else {
                    startVpnService()
                }
            }
        }

        // Botón OFF
        stopVpnButton.setOnClickListener {
            if (DnsVpnService.isServiceRunning) {
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
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al detener VPN", e)
            Toast.makeText(this, "Error al detener: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(running: Boolean) {
        val powerButton: ImageButton = findViewById(R.id.powerButton)
        val startVpnButton: Button = findViewById(R.id.onButton)
        val stopVpnButton: Button = findViewById(R.id.offButton)

        if (running) {
            powerButton.setBackgroundResource(R.drawable.button_background_red)
            startVpnButton.visibility = Button.GONE
            stopVpnButton.visibility = Button.VISIBLE
        } else {
            powerButton.setBackgroundResource(R.drawable.button_background_green)
            startVpnButton.visibility = Button.VISIBLE
            stopVpnButton.visibility = Button.GONE
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
    }
}