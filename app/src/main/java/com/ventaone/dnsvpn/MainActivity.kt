/**
 * MainActivity - Actividad principal de la aplicación.
 *
 * @authors skilkry (Daniel Sardina)  ¢ Daniel Enriquez Cayuelas
 * @since 2025-04-01
 * Copyright (c) 2025 skilkry. All rights reserved.
 * Licenciado bajo la Licencia MIT.
 */

package com.ventaone.dnsvpn

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.navigation.NavigationView
import java.io.File


class MainActivity : AppCompatActivity(),
    NavigationView.OnNavigationItemSelectedListener,
    CertificateMonitor.CertificateStatusListener,
    PowerButtonManager.VpnStateListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val TAG = "MainActivity"
    private val DRAWER_TAG = "DrawerDebug"

    // Vistas principales de tu UI
    private lateinit var powerButtonManager: PowerButtonManager
    private lateinit var powerButtonContainer: MaterialCardView
    private val powerIcon: AppCompatImageView by lazy {
        findViewById(R.id.powerIcon)
    }
    private lateinit var connectionStatusChip: Chip
    private lateinit var connectionStatusText: TextView
    private lateinit var connectionStatusIcon: ImageView
    private lateinit var certStatusIcon: ImageView
    private lateinit var certStatusText: TextView

    // Vistas para Navigation Drawer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar

    private lateinit var certificateMonitor: CertificateMonitor

    // ActivityResultLaunchers (sin cambios)
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted.")
        } else {
            Log.w(TAG, "VPN permission denied.")
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
            updateUIVisuals(PowerButtonManager.VpnUiState.DISCONNECTED, false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Log.w(TAG, "Notification permission denied.")
            Toast.makeText(this, getString(R.string.notification_permission_denied_message), Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "Notification permission granted.")
        }
    }

    // BroadcastReceiver para estados de VPN (sin cambios)
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "vpnStateReceiver: Received action: ${intent?.action}")
            when (intent?.action) {
                DnsVpnService.ACTION_VPN_CONNECTING -> updateUIVisuals(PowerButtonManager.VpnUiState.CONNECTING)
                DnsVpnService.ACTION_VPN_CONNECTED -> {
                    updateUIVisuals(PowerButtonManager.VpnUiState.CONNECTED, true)
                    certificateMonitor.startMonitoring()
                }
                DnsVpnService.ACTION_VPN_DISCONNECTED -> {
                    updateUIVisuals(PowerButtonManager.VpnUiState.DISCONNECTED, false)
                    certificateMonitor.stopMonitoring()
                }
                DnsVpnService.ACTION_VPN_ERROR -> {
                    val errorMessage = intent.getStringExtra(DnsVpnService.EXTRA_ERROR_MESSAGE) ?: getString(R.string.unknown_error)
                    Log.e(TAG, "vpnStateReceiver: VPN_ERROR - $errorMessage")
                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                    updateUIVisuals(PowerButtonManager.VpnUiState.ERROR, false)
                }
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate: Activity Main created. ContentView set.")
        Log.d(DRAWER_TAG, "onCreate: Activity Main (Drawer perspective) created.")

        checkNotificationPermission()

        if (intent.hasExtra("CRASH_LOG_PATH")) {
            val logPath = intent.getStringExtra("CRASH_LOG_PATH")
            val crashInfo = intent.getStringExtra("CRASH_INFO")
            if (logPath != null && crashInfo != null) {
                Log.w(TAG, "onCreate: App crashed previously. Log: $logPath, Info: $crashInfo")
                showCrashDialog(File(logPath), crashInfo)
            }
        }

        // --- Inicialización de Toolbar y Navigation Drawer ---
        Log.d(DRAWER_TAG, "onCreate: Initializing Toolbar and Drawer views...")
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        Log.d(DRAWER_TAG, "onCreate: Toolbar set as ActionBar.")
        // supportActionBar?.setDisplayShowTitleEnabled(false) // Opcional: si quieres control total del título

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)


        if (drawerLayout == null) Log.e(DRAWER_TAG, "onCreate: drawerLayout is NULL! Check R.id.drawer_layout in activity_main.xml")
        if (navigationView == null) Log.e(DRAWER_TAG, "onCreate: navigationView is NULL! Check R.id.navigation_view in activity_main.xml")
        Log.d(DRAWER_TAG, "onCreate: DrawerLayout and NavigationView references obtained.")

        // --- ELIMINADA LA CONFIGURACIÓN DEL ActionBarDrawerToggle y toolbar.setNavigationOnClickListener ---

        // Listener para logs detallados del estado del Drawer (opcional, pero útil)
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                var gravityDescription = "UNKNOWN_GRAVITY"
                if (drawerView.id == R.id.navigation_view) {
                    val params = drawerView.layoutParams as DrawerLayout.LayoutParams
                    gravityDescription = if (params.gravity == GravityCompat.START) "START (Correcto para izquierda)"
                    else if (params.gravity == GravityCompat.END) "END (Incorrecto para izquierda)"
                    else "GRAVITY_CODE ${params.gravity}"
                }
                Log.d(DRAWER_TAG, "onDrawerOpened: Drawer. Effective Gravity: $gravityDescription")
            }
            override fun onDrawerClosed(drawerView: View) {
                Log.d(DRAWER_TAG, "onDrawerClosed: Drawer closed.")
            }
            override fun onDrawerStateChanged(newState: Int) {
                val stateString = when (newState) {
                    DrawerLayout.STATE_IDLE -> "IDLE"; DrawerLayout.STATE_DRAGGING -> "DRAGGING"
                    DrawerLayout.STATE_SETTLING -> "SETTLING"; else -> "UNKNOWN_STATE"
                }
                Log.d(DRAWER_TAG, "onDrawerStateChanged: New state $stateString")
            }
        })
        Log.d(DRAWER_TAG, "onCreate: Custom DrawerListener added.")
        // --- Fin Configuración Navigation Drawer ---

        navigationView.setNavigationItemSelectedListener(this)
        Log.d(DRAWER_TAG, "onCreate: NavigationView item selected listener set.")

        // --- Inicialización de Vistas del Contenido Principal (sin cambios) ---
        Log.d(TAG, "onCreate: Initializing main content views...")
        powerButtonContainer = findViewById(R.id.powerButtonContainer)
        connectionStatusChip = findViewById(R.id.connectionStatusChip)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectionStatusIcon = findViewById(R.id.connectionStatusIcon)
        certStatusIcon = findViewById(R.id.certStatusIcon)
        certStatusText = findViewById(R.id.certStatusText)
        if (::powerButtonContainer.isInitialized && powerButtonContainer != null) {
            Log.d(TAG, "onCreate: powerButtonContainer inicializado. Visible: ${powerButtonContainer.visibility}, Alpha: ${powerButtonContainer.alpha}, Enabled: ${powerButtonContainer.isEnabled}")
        } else { Log.e(TAG, "onCreate: powerButtonContainer NO ESTÁ inicializado o es null!") }
        try {
            val iconClassName = powerIcon.javaClass.simpleName
            Log.d(TAG, "onCreate: powerIcon accesible. Clase: $iconClassName. Visible: ${powerIcon.visibility}, Alpha: ${powerIcon.alpha}")
        } catch (e: Exception) { Log.e(TAG, "onCreate: Error al acceder a powerIcon", e) }
        Log.d(TAG, "onCreate: Main content views initialized.")

        powerButtonManager = PowerButtonManager(this, powerButtonContainer, powerIcon, connectionStatusChip, connectionStatusText, this)
        Log.d(TAG, "onCreate: PowerButtonManager inicializado.")
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(this)
        Log.d(TAG, "onCreate: CertificateMonitor inicializado y listener añadido.")

        val intentFilter = IntentFilter().apply {
            addAction(DnsVpnService.ACTION_VPN_CONNECTING); addAction(DnsVpnService.ACTION_VPN_CONNECTED)
            addAction(DnsVpnService.ACTION_VPN_DISCONNECTED); addAction(DnsVpnService.ACTION_VPN_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver, intentFilter)
        Log.d(TAG, "onCreate: vpnStateReceiver registrado.")

        if (savedInstanceState == null) {
            Log.d(TAG, "onCreate: savedInstanceState is null, first creation.")
        }
    }

    override fun onResume() { // (Sin cambios en la lógica interna)
        super.onResume()
        Log.d(TAG, "onResume called.")
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias registrado en MainActivity")
        if (DnsVpnService.isCertificatePinningFailed) {
            Log.w(TAG, "onResume: Certificate pinning FAILED state detected.")
            handleCertificatePinningError()
        } else {
            if (DnsVpnService.isServiceRunning) {
                Log.d(TAG, "onResume: VPN Service IS running.")
                updateUIVisuals(PowerButtonManager.VpnUiState.CONNECTED, true); certificateMonitor.startMonitoring()
            } else {
                Log.d(TAG, "onResume: VPN Service IS NOT running.")
                updateUIVisuals(PowerButtonManager.VpnUiState.DISCONNECTED, false); certificateMonitor.stopMonitoring()
                updateCertificateStatusUI(CertificateMonitor.CertificateStatus.NOT_VERIFIED)
            }
        }
    }

    override fun onPause() { // (Sin cambios)
        super.onPause()
        Log.d(TAG, "onPause called.")
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias quitado en MainActivity")
    }

    override fun onDestroy() { // (Sin cambios)
        super.onDestroy()
        Log.d(TAG, "onDestroy called.")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnStateReceiver)
        certificateMonitor.removeListener(this); certificateMonitor.stopMonitoring()
    }

    // --- Añadido para inflar el menú de la Toolbar ---
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu) // Asume que tienes res/menu/main_toolbar_menu.xml
        Log.d(DRAWER_TAG, "onCreateOptionsMenu: Inflated main_toolbar_menu.")
        return true
    }

    // --- Modificado para manejar clics en ítems de la Toolbar (botón de drawer) ---
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.d(DRAWER_TAG, "onOptionsItemSelected: Item ID ${item.itemId}, Title: '${item.title}'")
        return when (item.itemId) {
            R.id.action_open_drawer_custom -> { // ID del ítem en main_toolbar_menu.xml
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) { // CAMBIADO a START
                    Log.d(DRAWER_TAG, "Toolbar custom button: START drawer is open, closing it.")
                    drawerLayout.closeDrawer(GravityCompat.START) // CAMBIADO a START
                } else {
                    Log.d(DRAWER_TAG, "Toolbar custom button: START drawer is closed, opening it.")
                    drawerLayout.openDrawer(GravityCompat.START) // CAMBIADO a START
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Modificado para cerrar el drawer IZQUIERDO ---
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        Log.d(DRAWER_TAG, "onNavigationItemSelected: Item ID ${item.itemId}, Title: '${item.title}'")
        when (item.itemId) {
            R.id.btnServer -> {
                Log.d(DRAWER_TAG, "Navigating to CertificateVerificationActivity"); startActivity(Intent(this, CertificateVerificationActivity::class.java))
            }
            R.id.nav_dns -> {
                Log.d(DRAWER_TAG, "Navigating to DnsSettingsActivity"); startActivity(Intent(this, DnsSettingsActivity::class.java))
            }
            R.id.nav_certs -> {
                Log.d(DRAWER_TAG, "Navigating to CertificateVerificationActivity"); startActivity(Intent(this, CertificateVerificationActivity::class.java))
            }

            R.id.action_about -> { //Actividad sobre los creadores
                Log.d(DRAWER_TAG, "Navigating to SettingsActivity"); startActivity(Intent(this, AboutActivity::class.java))
            }

            R.id.action_guide -> {
                Log.d(DRAWER_TAG,  "Navigating to GuideActivity"); startActivity(Intent(this, GuideActivity::class.java))
            }

            else -> Log.w(DRAWER_TAG, "Unknown navigation item selected: ID ${item.itemId}")
        }
        Log.d(DRAWER_TAG, "onNavigationItemSelected: Closing START drawer.")
        drawerLayout.closeDrawer(GravityCompat.START) // CAMBIADO a START
        return true
    }

    // --- Modificado para cerrar el drawer IZQUIERDO ---
    override fun onBackPressed() {
        Log.d(DRAWER_TAG, "onBackPressed called.")
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) { // CAMBIADO a START
            Log.d(DRAWER_TAG, "onBackPressed: START drawer is open, closing it.")
            drawerLayout.closeDrawer(GravityCompat.START)    // CAMBIADO a START
        } else {
            Log.d(DRAWER_TAG, "onBackPressed: START drawer is not open or not initialized, calling super.onBackPressed().")
            super.onBackPressed()
        }
    }

    // --- Métodos de UI y Lógica de VPN (sin cambios en su lógica interna) ---
    private fun updateUIVisuals(uiState: PowerButtonManager.VpnUiState, serviceIsActive: Boolean? = null) {
        Log.d(TAG, "updateUIVisuals: UI State: $uiState, Service Active: $serviceIsActive")
        if (!::powerButtonManager.isInitialized) { Log.e(TAG, "updateUIVisuals: Aborted - powerButtonManager not initialized."); return }
        powerButtonManager.updateUiForState(uiState, serviceIsActive)
        Log.d(TAG, "updateUIVisuals: powerButtonManager.updateUiForState called.")
        when (uiState) {
            PowerButtonManager.VpnUiState.DISCONNECTED -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_status_disconnected_gray_24dp)
                connectionStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary_gray))
            }
            PowerButtonManager.VpnUiState.CONNECTING -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_status_connecting_blue_24dp)
                connectionStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.vibrant_blue_primary))
            }
            PowerButtonManager.VpnUiState.CONNECTED -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_status_connected_green_24dp)
                connectionStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green_connected))
            }
            PowerButtonManager.VpnUiState.ERROR -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_status_error_red_24dp)
                connectionStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_red_error))
            }
        }
        Log.d(TAG, "updateUIVisuals: connectionStatusIcon updated for state $uiState.")
        if (::powerButtonContainer.isInitialized) {
            val isEnabledNew = (uiState != PowerButtonManager.VpnUiState.CONNECTING)
            powerButtonContainer.isEnabled = isEnabledNew
            powerButtonContainer.alpha = if (isEnabledNew) 1.0f else 0.7f
            Log.d(TAG, "updateUIVisuals: powerButtonContainer.isEnabled set to $isEnabledNew, alpha to ${powerButtonContainer.alpha}")
        } else { Log.e(TAG, "updateUIVisuals: powerButtonContainer not initialized, cannot update isEnabled/alpha.") }
    }

    private fun handleCertificatePinningError() { /* (Sin cambios) */
        Log.e(TAG, "handleCertificatePinningError called.")
        updateUIVisuals(PowerButtonManager.VpnUiState.ERROR, false)
        if(::powerButtonContainer.isInitialized) powerButtonContainer.isEnabled = false
        certStatusText.text = getString(R.string.cert_pinning_error_title)
        certStatusIcon.setImageResource(R.drawable.cert_status_red)
        certStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_red_error))
        Toast.makeText(this, getString(R.string.cert_pinning_error_action_required), Toast.LENGTH_LONG).show()
        val errorClickListener = View.OnClickListener {
            Log.d(TAG, "Certificate pinning error UI clicked, navigating to CertificateVerificationActivity.")
            startActivity(Intent(this, CertificateVerificationActivity::class.java))
        }
        certStatusIcon.setOnClickListener(errorClickListener); certStatusText.setOnClickListener(errorClickListener)
    }

    private fun updateCertificateStatusUI(status: CertificateMonitor.CertificateStatus) { /* (Sin cambios) */
        Log.d(TAG, "updateCertificateStatusUI: Status: $status")
        runOnUiThread {
            when (status) {
                CertificateMonitor.CertificateStatus.NOT_VERIFIED -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_gray); certStatusIcon.clearColorFilter()
                    certStatusText.text = getString(R.string.cert_status_default)
                }
                CertificateMonitor.CertificateStatus.VALID -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_green)
                    certStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green_connected))
                    certStatusText.text = getString(R.string.cert_status_valid)
                }
                CertificateMonitor.CertificateStatus.INVALID -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_red)
                    certStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_red_error))
                    certStatusText.text = getString(R.string.cert_status_invalid)
                }
                CertificateMonitor.CertificateStatus.WARNING -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_yellow)
                    certStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.your_yellow_color))
                    certStatusText.text = getString(R.string.cert_status_warning)
                }
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) { /* (Sin cambios) */
        Log.d(TAG, "onSharedPreferenceChanged: Key: $key")
        val relevantKeys = setOf("AD_BLOCKER", "MALWARE_BLOCKER", "USE_CUSTOM_DNS", "PRIMARY_DNS", "SECONDARY_DNS", "SERVER_URL")
        if (key in relevantKeys) {
            Log.i(TAG, "Relevant preference changed: $key. Requesting VPN reload/restart.")
            if (DnsVpnService.isServiceRunning) DnsVpnService.sendAction(this, DnsVpnService.ACTION_RELOAD_CONFIG_AND_RESTART)
        }
    }

    override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) { /* (Sin cambios) */
        Log.d(TAG, "onCertificateStatus: Received status: $status")
        if (DnsVpnService.isCertificatePinningFailed) { Log.w(TAG, "onCertificateStatus: Certificate pinning FAILED state active, handling error UI."); handleCertificatePinningError(); return }
        updateCertificateStatusUI(status)
        if (status == CertificateMonitor.CertificateStatus.INVALID && DnsVpnService.isServiceRunning) {
            Log.w(TAG, "onCertificateStatus: Certificate became INVALID while VPN is running.")
            updateUIVisuals(PowerButtonManager.VpnUiState.ERROR, true)
            if(::powerButtonContainer.isInitialized) powerButtonContainer.isEnabled = false
            Toast.makeText(this, getString(R.string.certificate_now_invalid_warning), Toast.LENGTH_LONG).show()
        } else if (status == CertificateMonitor.CertificateStatus.VALID && DnsVpnService.isServiceRunning) {
            Log.d(TAG, "onCertificateStatus: Certificate became VALID while VPN is running.")
            updateUIVisuals(PowerButtonManager.VpnUiState.CONNECTED, true)
        }
    }

    override fun onVpnStartRequested() { /* (Sin cambios) */
        Log.i(TAG, "onVpnStartRequested: CALLED")
        updateUIVisuals(PowerButtonManager.VpnUiState.CONNECTING)
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) { Log.d(TAG, "onVpnStartRequested: VPN permission needed, launching dialog."); vpnPermissionLauncher.launch(vpnIntent) }
        else { Log.d(TAG, "onVpnStartRequested: VPN permission already granted, starting service."); startVpnService() }
    }

    override fun onVpnStopRequested() { /* (Sin cambios) */
        Log.i(TAG, "onVpnStopRequested: CALLED"); stopVpnService()
    }

    private fun startVpnService() { /* (Sin cambios) */
        Log.d(TAG, "startVpnService() called."); val intent = Intent(this, DnsVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() { /* (Sin cambios) */
        Log.d(TAG, "stopVpnService() called."); val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_STOP_VPN; ContextCompat.startForegroundService(this, intent)
    }

    private fun checkNotificationPermission() { /* (Sin cambios) */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission not granted.")
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    Log.d(TAG, "Showing notification permission rationale."); showNotificationPermissionRationale()
                } else { Log.d(TAG, "Requesting notification permission."); requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else { Log.d(TAG, "Notification permission already granted.") }
        }
    }

    private fun showNotificationPermissionRationale() { /* (Sin cambios) */
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notification_permission_title))
            .setMessage(getString(R.string.notification_permission_rationale))
            .setPositiveButton(getString(R.string.request_permission_button)) { _, _ ->
                Log.d(TAG, "Rationale accepted, requesting notification permission.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(getString(R.string.cancel_button), null).show()
    }

    private fun showCrashDialog(logFile: File, crashInfo: String) { /* (Sin cambios) */
        AlertDialog.Builder(this).setTitle(R.string.unexpected_error_title)
            .setMessage("Detalles:\n$crashInfo\n\nLog: ${logFile.absolutePath}")
            .setPositiveButton(android.R.string.ok, null).show()
    }
}