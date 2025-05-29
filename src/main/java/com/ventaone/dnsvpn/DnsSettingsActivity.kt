package com.ventaone.dnsvpn

import android.content.Intent // Import Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText // Keep if needed for TextInputEditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText
import androidx.appcompat.widget.Toolbar // Import Toolbar if you add one

class DnsSettingsActivity : AppCompatActivity() {
    private val TAG = "DnsSettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_settings) // Ensure this layout exists

        // Set up the Toolbar (assuming you have one in activity_dns_settings.xml)
        // val toolbar: Toolbar = findViewById(R.id.toolbar_dns_settings) // Use the correct ID from your layout
        // setSupportActionBar(toolbar)
        // supportActionBar?.setDisplayHomeAsUpEnabled(true) // Show the back button
        // supportActionBar?.setDisplayShowTitleEnabled(true) // Show the title
        // supportActionBar?.title = getString(R.string.dns_settings_title) // Define dns_settings_title in strings.xml


        val primaryDnsInput: TextInputEditText = findViewById(R.id.primaryDnsInput) // Use the correct ID from your layout
        val secondaryDnsInput: TextInputEditText = findViewById(R.id.secondaryDnsInput) // Use the correct ID from your layout

        val saveButton: Button = findViewById(R.id.saveButton) // Use the correct ID from your layout
        val cancelButton: Button = findViewById(R.id.cancelButton) // Use the correct ID from your layout

        // Load saved DNS and the state of USE_CUSTOM_DNS
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val primaryDns = preferences.getString("PRIMARY_DNS", "1.1.1.1")
        val secondaryDns = preferences.getString("SECONDARY_DNS", "1.0.0.1")
        // No necesitamos leer useCustomDnsEnabled aquí para mostrar los IPs guardados,
        // solo para depuración si se desea.
        // val useCustomDnsEnabled = preferences.getBoolean("USE_CUSTOM_DNS", false)
        // Log.d(TAG, "DNS cargados: Primario=$primaryDns, Secundario=$secondaryDns. Usar personalizado (estado actual del switch): $useCustomDnsEnabled")


        // Display the currently saved DNS IPs
        primaryDnsInput.setText(primaryDns)
        secondaryDnsInput.setText(secondaryDns)

        // Configure the save button
        saveButton.setOnClickListener {
            val newPrimaryDns = primaryDnsInput.text.toString().trim()
            val newSecondaryDns = secondaryDnsInput.text.toString().trim()

            // Validar IP format (basic check)
            if (isValidIpAddress(newPrimaryDns) && isValidIpAddress(newSecondaryDns)) {
                // Guardar los nuevos valores de DNS
                val editor = preferences.edit()
                editor.putString("PRIMARY_DNS", newPrimaryDns)
                editor.putString("SECONDARY_DNS", newSecondaryDns)
                // !!! ELIMINADA LA LÍNEA QUE ACTIVABA USE_CUSTOM_DNS !!!
                // editor.putBoolean("USE_CUSTOM_DNS", true) // <-- ¡Esta línea se elimina!
                editor.apply()

                Log.d(TAG, "DNS personalizados guardados: Primario=$newPrimaryDns, Secundario=$newSecondaryDns.")
                Toast.makeText(this, "DNS guardados correctamente", Toast.LENGTH_SHORT).show()

                // Si el servicio VPN está activo, notificar MainActivity para que gestione el reinicio
                // MainActivity está escuchando los cambios en PRIMARY_DNS y SECONDARY_DNS
                if (DnsVpnService.isServiceRunning) {
                    Toast.makeText(this, "Reiniciando VPN para aplicar los cambios...", Toast.LENGTH_LONG).show()
                    // La lógica de reinicio está en MainActivity al detectar el cambio de PRIMARY_DNS/SECONDARY_DNS
                } else {
                    Toast.makeText(this, "Cambios guardados. Se aplicarán al iniciar la VPN.", Toast.LENGTH_SHORT).show()
                }

                finish() // Close the activity after saving
            } else {
                Log.e(TAG, "Formato de IP inválido: Primario=$newPrimaryDns, Secundario=$newSecondaryDns")
                Toast.makeText(this, "Formato de dirección IP no válido", Toast.LENGTH_SHORT).show()
            }
        }

        // Configure the cancel button
        cancelButton.setOnClickListener {
            Log.d(TAG, "Configuración DNS cancelada")
            finish() // Close the activity without saving
        }
    }

    // Handle the back button in the Toolbar (if you add one)
    // override fun onSupportNavigateUp(): Boolean {
    //     onBackPressedDispatcher.onBackPressed() // Or finish()
    //     return true
    // }

    // Function to validate IP address format (basic IPv4 check)
    private fun isValidIpAddress(ip: String): Boolean {
        // This regex matches basic IPv4 format
        val pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\$"
        // You might want to add IPv6 validation if needed
        return ip.matches(pattern.toRegex())
    }
}
