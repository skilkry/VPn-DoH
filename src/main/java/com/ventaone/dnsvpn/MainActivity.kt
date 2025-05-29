package com.ventaone.dnsvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class MainActivity : AppCompatActivity(),
    CertificateMonitor.CertificateStatusListener,
    PowerButtonManager.VpnStateListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var powerButtonManager: PowerButtonManager
    private val TAG = "MainActivity"
    private val VPN_REQUEST_CODE = 100
    private lateinit var powerButtonContainer: MaterialCardView
    private lateinit var powerIcon: AppCompatImageView
    private lateinit var connectionStatusChip: Chip
    private lateinit var connectionStatusText: TextView
    private lateinit var certStatusIcon: ImageView
    private lateinit var certStatusText: TextView
    private lateinit var configPanel: MaterialCardView
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var dnsButton: MaterialButton
    private lateinit var certsButton: MaterialButton
    private lateinit var serverButton: MaterialButton
    private lateinit var certificateMonitor: CertificateMonitor

    // --- INICIO DE LA ELIMINACIÓN ---
    // Se ha eliminado la variable 'dnsLeakProtection'.
    // --- FIN DE LA ELIMINACIÓN ---

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (!DnsVpnService.isServiceRunning) {
                startVpnService()
            }
        } else {
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
            powerButtonManager.updateState(false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Las notificaciones están desactivadas. Algunas funciones pueden no trabajar correctamente.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- INICIO DE LA ELIMINACIÓN ---
        // Se ha eliminado la inicialización de 'dnsLeakProtection'.
        // --- FIN DE LA ELIMINACIÓN ---
        checkNotificationPermission()

        if (intent.hasExtra("CRASH_LOG_PATH")) {
            val logPath = intent.getStringExtra("CRASH_LOG_PATH")
            val crashInfo = intent.getStringExtra("CRASH_INFO")
            if (logPath != null && crashInfo != null) {
                showCrashDialog(File(logPath), crashInfo)
            }
        }

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        powerButtonContainer = findViewById(R.id.powerButtonContainer)
        powerIcon = findViewById(R.id.powerIcon)
        connectionStatusChip = findViewById(R.id.connectionStatusChip)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        certStatusIcon = findViewById(R.id.certStatusIcon)
        certStatusText = findViewById(R.id.certStatusText)
        configPanel = findViewById(R.id.configPanel)
        fabSettings = findViewById(R.id.fabSettings)
        dnsButton = findViewById(R.id.dnsButton)
        certsButton = findViewById(R.id.certsButton)
        serverButton = findViewById(R.id.serverButton)

        powerButtonManager = PowerButtonManager(
            this, powerButtonContainer, powerIcon, connectionStatusChip, connectionStatusText, this
        )
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(this)

        fabSettings.setOnClickListener {
            configPanel.visibility = if (configPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        certsButton.setOnClickListener {
            configPanel.visibility = View.GONE
            startActivity(Intent(this, CertificateVerificationActivity::class.java))
        }
        dnsButton.setOnClickListener {
            configPanel.visibility = View.GONE
            startActivity(Intent(this, DnsSettingsActivity::class.java))
        }
        serverButton.setOnClickListener {
            configPanel.visibility = View.GONE
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias registrado en MainActivity")

        if (DnsVpnService.isCertificatePinningFailed) {
            updateCertificateErrorUI()
        } else {
            updateUI(DnsVpnService.isServiceRunning)
        }

        if (DnsVpnService.isServiceRunning) {
            certificateMonitor.startMonitoring()
        } else {
            certificateMonitor.stopMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias quitado en MainActivity")

        if (configPanel.visibility == View.VISIBLE) {
            configPanel.visibility = View.GONE
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    showNotificationPermissionRationale()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de notificación")
            .setMessage("Necesitamos mostrar notificaciones para mantener la aplicación VPN activa y proporcionarte información sobre el estado de la conexión.")
            .setPositiveButton("Solicitar permiso") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startVpnService() {
        val intent = Intent(this, DnsVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = "STOP_VPN"
        startService(intent)
    }

    private fun updateUI(running: Boolean) {
        powerButtonManager.updateState(running)
        powerButtonContainer.isEnabled = true
        powerButtonContainer.alpha = 1.0f

        certStatusIcon.setOnClickListener(null)
        certStatusText.setOnClickListener(null)

        if (!running) {
            updateCertificateStatusUI(CertificateMonitor.CertificateStatus.NOT_VERIFIED)
        }
    }

    private fun updateCertificateErrorUI() {
        updateUI(false)
        powerButtonContainer.isEnabled = false
        powerButtonContainer.alpha = 0.5f

        certStatusText.text = "Error de Certificado"
        certStatusIcon.setImageResource(R.drawable.cert_status_red)
        Toast.makeText(this, "¡Acción requerida! El certificado del servidor no es válido.", Toast.LENGTH_LONG).show()

        val errorClickListener = View.OnClickListener {
            startActivity(Intent(this, CertificateVerificationActivity::class.java))
        }
        certStatusIcon.setOnClickListener(errorClickListener)
        certStatusText.setOnClickListener(errorClickListener)
    }

    private fun updateCertificateStatusUI(status: CertificateMonitor.CertificateStatus) {
        runOnUiThread {
            when (status) {
                CertificateMonitor.CertificateStatus.NOT_VERIFIED -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_gray)
                    certStatusText.text = getString(R.string.cert_status_default)
                }
                CertificateMonitor.CertificateStatus.VALID -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_green)
                    certStatusText.text = getString(R.string.cert_status_valid)
                }
                CertificateMonitor.CertificateStatus.INVALID -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_red)
                    certStatusText.text = getString(R.string.cert_status_invalid)
                }
                CertificateMonitor.CertificateStatus.WARNING -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_yellow)
                    certStatusText.text = getString(R.string.cert_status_warning)
                }
            }
        }
    }

    fun restartVpnService() {
        // La lógica de reinicio ahora está implícita en detener y que el sistema
        // lo levante de nuevo con START_STICKY, que ahora sí recargará todo correctamente.
        if (DnsVpnService.isServiceRunning) {
            stopVpnService()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val relevantKeys = setOf("AD_BLOCKER", "MALWARE_BLOCKER", "USE_CUSTOM_DNS", "PRIMARY_DNS", "SECONDARY_DNS", "SERVER_URL")
        if (key in relevantKeys) {
            Log.d(TAG, "Preferencia clave cambiada: $key. Reiniciando VPN...")
            restartVpnService()
        }
    }

    override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) {
        if (!DnsVpnService.isCertificatePinningFailed) {
            updateCertificateStatusUI(status)
        }
    }

    override fun onVpnStartRequested() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    override fun onVpnStopRequested() {
        stopVpnService()
    }

    private fun showCrashDialog(logFile: File, crashInfo: String) {
        // Tu implementación...
    }
}