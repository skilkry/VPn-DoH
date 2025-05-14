package com.ventaone.dnsvpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

// ¡Añadir la implementación de SharedPreferences.OnSharedPreferenceChangeListener aquí!
import android.content.SharedPreferences // Asegúrate de importar SharedPreferences
import androidx.preference.PreferenceManager // Asegúrate de importar PreferenceManager
// La Activity ahora implementa CertificateStatusListener, VpnStateListener Y OnSharedPreferenceChangeListener
class MainActivity : AppCompatActivity(),
    CertificateMonitor.CertificateStatusListener,
    PowerButtonManager.VpnStateListener,
    SharedPreferences.OnSharedPreferenceChangeListener { // <-- ¡Implementamos el listener!

    private lateinit var powerButtonManager: PowerButtonManager
    private val TAG = "MainActivity"
    private val VPN_REQUEST_CODE = 100 // Aunque usas el nuevo launcher, mantener por si acaso
    private lateinit var powerButtonContainer: MaterialCardView
    private lateinit var powerIcon: AppCompatImageView
    private lateinit var connectionStatusChip: Chip
    private lateinit var connectionStatusText: TextView
    private lateinit var certStatusIcon: ImageView
    private lateinit var certStatusText: TextView
    // certificateMonitor ya está declarado arriba
    // powerButtonManager ya está declarado arriba
    private lateinit var configPanel: MaterialCardView
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var dnsButton: MaterialButton
    private lateinit var certsButton: MaterialButton
    private lateinit var serverButton: MaterialButton // Asumo que este botón abre SettingsActivity
    private lateinit var certificateMonitor: CertificateMonitor
    // Añadir el gestor de protección contra fugas DNS (mantener si se usa su lógica)
    private lateinit var dnsLeakProtection: DnsLeakProtection

    // Launcher para la solicitud de permiso VPN usando la nueva API
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Permiso VPN concedido, iniciando servicio...")
            // Llamar a startVpnService solo si el permiso fue concedido ahora
            // Esto previene intentar iniciar si el usuario ya lo había iniciado y luego concedió el permiso
            // Es mejor que el onVpnStartRequested maneje la llamada a prepare y, si devuelve null, llame a startVpnService
            // Si devuelve un Intent, el launcher lo manejará y, al volver RESULT_OK, esta lambda se ejecuta.
            // Aquí solo deberíamos iniciar si la bandera indica que el usuario intentó iniciar.
            if (!DnsVpnService.isServiceRunning) { // Evitar doble inicio si el servicio ya se lanzó
                startVpnService()
            }

        } else {
            Log.d(TAG, "Permiso VPN denegado")
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
            powerButtonManager.updateState(false) // Asegurarse de que el botón refleje que no se pudo iniciar
        }
    }

    // Launcher para solicitar permisos de notificación
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Permiso de notificación concedido")
            // Si estamos iniciando el VPN, podemos mostrar una notificación ahora
            if (DnsVpnService.isServiceRunning) {
                // Enviar una acción para actualizar la notificación del servicio
                val updateIntent = Intent(this, DnsVpnService::class.java)
                updateIntent.action = "UPDATE_NOTIFICATION"
                startService(updateIntent) // Usar startService para enviar la acción
            }
        } else {
            Log.d(TAG, "Permiso de notificación denegado")
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

        // Inicializar el gestor de protección contra fugas DNS (mantener si se usa su lógica)
        dnsLeakProtection = DnsLeakProtection(this)

        // Verificar y solicitar permisos de notificación para Android 13+
        checkNotificationPermission()

        if (intent.hasExtra("CRASH_LOG_PATH")) {
            val logPath = intent.getStringExtra("CRASH_LOG_PATH")
            val crashInfo = intent.getStringExtra("CRASH_INFO")

            if (logPath != null && crashInfo != null) {
                showCrashDialog(File(logPath), crashInfo)
            }
        }
        // Inicializar Toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true) // Asegurar que el título esté visible


        // Inicializar componentes UI
        powerButtonContainer = findViewById(R.id.powerButtonContainer)
        powerIcon = findViewById(R.id.powerIcon)
        connectionStatusChip = findViewById(R.id.connectionStatusChip)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        certStatusIcon = findViewById(R.id.certStatusIcon)
        certStatusText = findViewById(R.id.certStatusText)
        configPanel = findViewById(R.id.configPanel)
        fabSettings = findViewById(R.id.fabSettings)
        dnsButton = findViewById(R.id.dnsButton) // Asumo que este abre DnsSettingsActivity
        certsButton = findViewById(R.id.certsButton) // Asumo que este abre CertificateVerificationActivity
        serverButton = findViewById(R.id.serverButton) // Asumo que este abre SettingsActivity (donde están los switches)


        // Inicializar PowerButtonManager
        powerButtonManager = PowerButtonManager(
            this,
            powerButtonContainer,
            powerIcon,
            connectionStatusChip,
            connectionStatusText,
            this // MainActivity implementa VpnStateListener
        )

        // Inicializar el monitor de certificados
        certificateMonitor = CertificateMonitor(this)
        certificateMonitor.addListener(this) // MainActivity implementa CertificateStatusListener

        // Registrar el listener de la Activity/Fragment de Preferencias en su onResume/onPause
        // Aquí en MainActivity, registramos el listener de preferencia para REINICIAR el servicio
        // Esto se hace en onResume/onPause, no en onCreate


        // Configurar FAB de configuración
        fabSettings.setOnClickListener {
            // Alternar la visibilidad del panel de configuración
            configPanel.visibility = if (configPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Configurar navegación a diferentes pantallas
        certsButton.setOnClickListener {
            configPanel.visibility = View.GONE // Ocultar panel al navegar
            val intent = Intent(this, CertificateVerificationActivity::class.java)
            startActivity(intent)
        }
        dnsButton.setOnClickListener {
            configPanel.visibility = View.GONE // Ocultar panel al navegar
            val intent = Intent(this, DnsSettingsActivity::class.java) // Asumo esta Activity/Fragment maneja DNS personalizado/URL servidor
            startActivity(intent)
        }

        // Asumo que SettingsActivity.kt contiene las preferencias con los switches de bloqueo
        serverButton.setOnClickListener {
            configPanel.visibility = View.GONE // Ocultar panel al navegar
            val intent = Intent(this, SettingsActivity::class.java) // <-- Esta Activity debe contener tus Switches
            startActivity(intent)
        }


        // Registrar el cambio de configuración de la protección contra fugas DNS (Mantener si se usa esta lógica)
        // dnsLeakProtection.logConfigChange() // Esta llamada podría necesitar estar en otro lugar o ser parte de la lógica del servicio si la protección se aplica allí.
        // Si dnsLeakProtection solo loguea, mantenerla aquí está bien. Si aplica la protección de alguna forma, su lógica DEBERÍA estar en el servicio.
        // Por ahora, la mantenemos donde estaba.
        if (::dnsLeakProtection.isInitialized) { // Verificar si ha sido inicializado
            dnsLeakProtection.logConfigChange()
        }
    }

    // Método para verificar y solicitar permisos de notificación
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Ya tenemos permiso de notificación")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Mostrar explicación antes de solicitar el permiso
                    showNotificationPermissionRationale()
                }
                else -> {
                    // Solicitar el permiso directamente
                    Log.d(TAG, "Solicitando permiso de notificación")
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu) // Asegúrate de que main_menu.xml contenga el submenú de Información/Ayuda
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            // Manejo de items dentro del submenú de Información/Ayuda
            R.id.action_guide -> { // ID del item "Guía de Uso"
                // Aquí inicia la Activity o muestra el Dialog/Fragment de la guía
                val intent = Intent(this, GuideActivity::class.java) // Asumo GuideActivity existe
                startActivity(intent)
                true
            }
            R.id.action_about -> { // ID del item "Acerca de"
                val intent = Intent(this, AboutActivity::class.java) // Asumo AboutActivity existe
                startActivity(intent)
                true
            }
            // No necesitas manejar R.id.action_info_group si solo abre el submenú

            android.R.id.home -> { // Manejar el botón de atrás en la toolbar si está visible (ej. en sub-activities)
                onBackPressedDispatcher.onBackPressed() // O finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Método para reiniciar el servicio VPN cuando cambian las preferencias relevantes
    // Este método es llamado por el listener de preferencias cuando detecta un cambio clave
    fun restartVpnService() {
        Log.d(TAG, "Solicitando reinicio VPN debido a cambio de preferencias.")
        // Verificar si la VPN está corriendo ANTES de intentar reiniciarla
        if (DnsVpnService.isServiceRunning) {
            Log.d(TAG, "VPN está corriendo, enviando acción de reinicio al servicio.")
            // Detener el servicio VPN enviando la acción de stop
            // El servicio al recibir STOP_VPN se detendrá y llamará a stopSelf().
            // Luego, el onStartCommand del servicio está programado para re-iniciar si es STICKY,
            // PERO es más seguro enviar una acción de REINICIO explícita después.
            // Aquí, llamamos a stopVpnService que ya envía la acción STOP_VPN.
            // El reinicio debe ser manejado por el servicio mismo al recibir ACTION_RESTART_VPN.

            val restartIntent = Intent(this, DnsVpnService::class.java)
            restartIntent.action = DnsVpnService.ACTION_RESTART_VPN // Acción para que el servicio se reinicie
            startService(restartIntent) // Enviar la señal de reinicio
            // El Toast se muestra en el servicio al conectar
            // Toast.makeText(this, "Reiniciando VPN para aplicar cambios...", Toast.LENGTH_SHORT).show()

        } else {
            Log.d(TAG, "VPN no está corriendo. Los cambios se aplicarán en el próximo inicio.")
            // Opcional: Mostrar un toast informando al usuario
            // Toast.makeText(this, "Cambios guardados. Se aplicarán al iniciar la VPN.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun shareCrashLog(logFile: File, crashInfo: String) {
        // ... (Tu código existente para compartir log) ...
    }

    private fun showCrashDialog(logFile: File, crashInfo: String) {
        // ... (Tu código existente para mostrar diálogo de crash) ...
    }

    // Llama a este método cuando quieras INICIAR la VPN (desde onVpnStartRequested)
    private fun startVpnService() {
        try {
            Log.d(TAG, "Intentando iniciar VpnService...")
            val intent = Intent(this, DnsVpnService::class.java)

            // Si dnsLeakProtection tiene un estado relevante, pásalo aquí (si el servicio lo necesita)
            // intent.putExtra(DnsLeakProtection.PREF_DNS_LEAK_PROTECTION, dnsLeakProtection.isEnabled()) // Ejemplo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }

            // updateUI(true) // La UI se actualiza en onResume y onCertificateStatus, o puedes escuchar el estado del servicio de forma más robusta
            // Toast.makeText(this, "Conectando...", Toast.LENGTH_SHORT).show() // Mostrar toast de "Conectando"

            // El monitoreo de certificados se inicia en el servicio en startVpn()

        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar VpnService", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            // updateUI(false) // Asegurarse de que la UI se actualice si falla el inicio
        }
    }

    // Llama a este método cuando quieras DETENER la VPN (desde onVpnStopRequested)
    private fun stopVpnService() {
        try {
            Log.d(TAG, "Intentando detener VpnService")
            val intent = Intent(this, DnsVpnService::class.java)
            intent.action = "STOP_VPN"
            startService(intent)  // Envía la acción de detener al servicio
            // updateUI(false) // La UI se actualiza cuando el servicio realmente se detiene (ej. usando un BroadcastReceiver o LiveData)
            // Toast.makeText(this, "Desconectando...", Toast.LENGTH_SHORT).show() // Mostrar toast de "Desconectando"

            // El monitoreo de certificados se detiene en el servicio en stopVpn()

        } catch (e: Exception) {
            Log.e(TAG, "Error al detener VpnService", e)
            Toast.makeText(this, "Error al detener: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Este método actualiza la UI del botón y chip según el estado del servicio
    private fun updateUI(running: Boolean) {
        Log.d(TAG, "Actualizando UI: corriendo = $running")
        powerButtonManager.updateState(running)

        // Si el servicio no está en ejecución, mostramos el estado de certificado como "Sin verificar"
        if (!running) {
            updateCertificateStatusUI(CertificateMonitor.CertificateStatus.NOT_VERIFIED)
        }
        // Si está corriendo, el estado del certificado se actualizará vía onCertificateStatus
    }

    // Este método actualiza la UI del estado del certificado
    private fun updateCertificateStatusUI(status: CertificateMonitor.CertificateStatus) {
        Log.d(TAG, "Actualizando UI certificado: estado = $status")
        runOnUiThread { // Asegurarse de que las actualizaciones de UI se hagan en el hilo principal
            when (status) {
                CertificateMonitor.CertificateStatus.NOT_VERIFIED -> {
                    certStatusIcon.setImageResource(R.drawable.cert_status_gray) // Asegúrate de tener estos drawables
                    certStatusText.text = getString(R.string.cert_status_default) // Asegúrate de tener estas strings
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

    // Método depreciado pero mantenido para compatibilidad con versiones antiguas del manejo de permiso VPN
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Permiso VPN concedido (método antiguo), iniciando servicio...")
            // Si el permiso fue concedido por el método antiguo, iniciar el servicio
            if (!DnsVpnService.isServiceRunning) {
                startVpnService()
            }
        } else if (requestCode == VPN_REQUEST_CODE) {
            Log.d(TAG, "Permiso VPN denegado (método antiguo)")
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
            // Asegurar que el estado de UI es correcto si el permiso es rechazado
            powerButtonManager.updateState(false)
        }
    }


    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        // Actualizar UI al volver a la aplicación
        updateUI(DnsVpnService.isServiceRunning)

        // Registrar el listener de preferencias aquí para REINICIAR la VPN
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias registrado en MainActivity")


        // Si el servicio está en ejecución, verificamos el estado del certificado de nuevo
        if (DnsVpnService.isServiceRunning) {
            // Reanudar el monitoreo si estaba activo anteriormente
            // Esto ya se inicia/detiene en el servicio, pero asegurar que la Activity
            // recupere el estado al volver
            // certificateMonitor.startMonitoring() // Iniciar/detener en el servicio es mejor
            // Actualizar el UI inmediatamente con el último estado conocido del monitor
            if (::certificateMonitor.isInitialized) { // Verificar inicialización
                updateCertificateStatusUI(certificateMonitor.getCurrentStatus()) // Necesitas añadir getCurrentStatus() a CertificateMonitor
            }
        } else {
            // Si la VPN no está corriendo, asegúrate de que el monitoreo no esté activo (el servicio lo detiene)
            // y que el estado del certificado en UI sea el por defecto.
            if (::certificateMonitor.isInitialized) { // Verificar inicialización
                certificateMonitor.stopMonitoring() // Asegurar que no corre si el servicio no corre
            }
            updateCertificateStatusUI(CertificateMonitor.CertificateStatus.NOT_VERIFIED)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
        // Quitar el listener de preferencias para evitar fugas de memoria
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Listener de preferencias quitado en MainActivity")

        // Ocultamos el panel de configuración si está visible
        if (configPanel.visibility == View.VISIBLE) {
            configPanel.visibility = View.GONE
        }

        // No detenemos el monitoreo de certificados aquí si el servicio sigue corriendo
        // Su ciclo de vida debe estar ligado al servicio VPN
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy")
        // Detener el monitoreo y eliminar este listener (aunque el servicio también lo haga)
        if (::certificateMonitor.isInitialized) { // Verificar inicialización
            certificateMonitor.removeListener(this)
            certificateMonitor.stopMonitoring() // Asegurar detención
        }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "onSharedPreferenceChanged disparado para key: $key") // <-- Añade este log al inicio del método

        val relevantKeys = setOf(
            "AD_BLOCKER",
            "MALWARE_BLOCKER",
            "USE_CUSTOM_DNS", // Si implementaste esta preferencia
            "PRIMARY_DNS",    // Si implementaste esta preferencia
            "SECONDARY_DNS",  // Si implementaste esta preferencia
            "SERVER_URL"      // URL del servidor DoH/para verificación de certificado
            // Añade aquí cualquier otra clave cuyo cambio deba reiniciar la VPN
        )

        if (key in relevantKeys) {
            Log.d(TAG, "Preferencia relevante '$key' cambiada. Solicitando reinicio de VPN.") // <-- Añade este log dentro del if
            // Llama al método que ya tienes para reiniciar la VPN
            restartVpnService()
        }
        // ... resto del método ...
    }

    // Implementación de la interfaz CertificateStatusListener (ya estaba)
    override fun onCertificateStatus(status: CertificateMonitor.CertificateStatus) {
        // Este método se llamará cuando cambie el estado del certificado
        Log.d(TAG, "Recibido evento de estado de certificado: $status")
        runOnUiThread { // Asegurarse de que las actualizaciones de UI se hagan en el hilo principal
            updateCertificateStatusUI(status)
        }
    }

    // Implementación de la interfaz VpnStateListener (ya estaba)
    // Este método se llama desde PowerButtonManager cuando el usuario pulsa el botón de "Encender"
    override fun onVpnStartRequested() {
        Log.d(TAG, "Solicitud de inicio VPN recibida desde PowerButtonManager.")
        // Verificar el permiso VPN antes de iniciar el servicio
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // Si prepare devuelve un Intent, el permiso NO está concedido, lanzar la Activity de sistema
            Log.d(TAG, "Permiso VPN no concedido, lanzando Activity de solicitud...")
            try {
                // Usar ActivityResultLauncher para el nuevo enfoque
                vpnPermissionLauncher.launch(vpnIntent)
            } catch (e: Exception) {
                // Fallback al método antiguo si el launcher falla (menos común ahora)
                Log.w(TAG, "Error con ActivityResultLauncher, usando startActivityForResult antiguo", e)
                @Suppress("DEPRECATION") // Suprimir la advertencia para el método antiguo
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
            }
        } else {
            // Si prepare devuelve null, el permiso YA está concedido, iniciar el servicio directamente
            Log.d(TAG, "Permiso VPN ya concedido, llamando a startVpnService()...")
            startVpnService()
        }
    }

    // Implementación de la interfaz VpnStateListener (ya estaba)
    // Este método se llama desde PowerButtonManager cuando el usuario pulsa el botón de "Apagar"
    override fun onVpnStopRequested() {
        Log.d(TAG, "Solicitud de detención VPN recibida desde PowerButtonManager.")
        // Llamar al método que detiene el servicio
        stopVpnService()
    }
}