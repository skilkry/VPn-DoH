package com.ventaone.dnsvpn

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        // Si se recibió el extra para abrir una sección específica
        val openSection = intent.getStringExtra("OPEN_SECTION")
        if (openSection == "SERVER") {
            // Implementar la lógica para abrir la sección de configuración del servidor
            // Esto depende de cómo hayas estructurado tus preferencias
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Configurar el input type para la URL del servidor
            val serverUrlPreference: EditTextPreference? = findPreference("SERVER_URL")
            serverUrlPreference?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }

            // Configurar preferencia para verificación de certificados
            val certVerificationPreference: Preference? = findPreference("VERIFY_CERTIFICATE")
            certVerificationPreference?.setOnPreferenceClickListener {
                // Abrir la actividad de verificación de certificados
                activity?.let { activity ->
                    val intent = android.content.Intent(activity, CertificateVerificationActivity::class.java)
                    activity.startActivity(intent)
                }
                true
            }
        }
    }
}