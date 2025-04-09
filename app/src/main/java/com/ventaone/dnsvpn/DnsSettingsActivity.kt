package com.ventaone.dnsvpn

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class DnsSettingsActivity : AppCompatActivity() {
    private val TAG = "DnsSettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_settings)

        val primaryDnsEditText: EditText = findViewById(R.id.primaryDnsEditText)
        val secondaryDnsEditText: EditText = findViewById(R.id.secondaryDnsEditText)
        val saveButton: Button = findViewById(R.id.saveButton)
        val cancelButton: Button = findViewById(R.id.cancelButton)

        // Cargar DNS guardados si existen
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val primaryDns = preferences.getString("PRIMARY_DNS", "1.1.1.1")
        val secondaryDns = preferences.getString("SECONDARY_DNS", "1.0.0.1")

        Log.d(TAG, "DNS cargados: Primario=$primaryDns, Secundario=$secondaryDns")

        primaryDnsEditText.setText(primaryDns)
        secondaryDnsEditText.setText(secondaryDns)

        // Configurar el botón de guardar
        saveButton.setOnClickListener {
            val newPrimaryDns = primaryDnsEditText.text.toString().trim()
            val newSecondaryDns = secondaryDnsEditText.text.toString().trim()

            // Validar formato de IP
            if (isValidIpAddress(newPrimaryDns) && isValidIpAddress(newSecondaryDns)) {
                // Guardar los nuevos valores de DNS
                val editor = preferences.edit()
                editor.putString("PRIMARY_DNS", newPrimaryDns)
                editor.putString("SECONDARY_DNS", newSecondaryDns)
                editor.apply()

                Log.d(TAG, "DNS guardados: Primario=$newPrimaryDns, Secundario=$newSecondaryDns")
                Toast.makeText(this, "DNS guardados correctamente", Toast.LENGTH_SHORT).show()

                // Si el servicio VPN está activo, notificar que debe reiniciarse
                if (DnsVpnService.isServiceRunning) {
                    Toast.makeText(this, "Reinicie la VPN para aplicar los cambios", Toast.LENGTH_LONG).show()
                }

                finish()
            } else {
                Log.e(TAG, "Formato de IP inválido: Primario=$newPrimaryDns, Secundario=$newSecondaryDns")
                Toast.makeText(this, "Formato de dirección IP no válido", Toast.LENGTH_SHORT).show()
            }
        }

        // Configurar el botón de cancelar
        cancelButton.setOnClickListener {
            Log.d(TAG, "Configuración DNS cancelada")
            finish()
        }
    }

    // Función para validar el formato de una dirección IP
    private fun isValidIpAddress(ip: String): Boolean {
        val pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\$"
        return ip.matches(pattern.toRegex())
    }
}