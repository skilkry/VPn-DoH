package com.ventaone.dnsvpn


import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlin.concurrent.thread

class SecurityAuditActivity : AppCompatActivity() {
    private val TAG = "SecurityAuditActivity"

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var auditButton: Button
    private val auditResults = mutableListOf<SecurityAuditItem>()
    private lateinit var adapter: SecurityAuditAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_audit)

        // Configurar Toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Auditoría de Seguridad"

        // Inicializar vistas
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        auditButton = findViewById(R.id.auditButton)

        // Configurar RecyclerView
        adapter = SecurityAuditAdapter(auditResults)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Configurar botón de auditoría
        auditButton.setOnClickListener {
            performSecurityAudit()
        }

        // Realizar auditoría automáticamente al abrir
        performSecurityAudit()
    }

    private fun performSecurityAudit() {
        // Mostrar progreso
        progressBar.visibility = View.VISIBLE
        auditButton.isEnabled = false
        auditResults.clear()
        adapter.notifyDataSetChanged()

        thread {
            // Obtener preferencias
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)

            // Verificar protocolo DNS
            val dnsProtocol = preferences.getString("DNS_PROTOCOL", "doh") ?: "doh"
            val isSecureProtocol = dnsProtocol == "doh" || dnsProtocol == "dot"
            auditResults.add(SecurityAuditItem(
                "Protocolo DNS",
                "Verificando si el protocolo usado es seguro",
                if (isSecureProtocol) SecurityAuditStatus.PASS else SecurityAuditStatus.FAIL,
                "Protocolo actual: ${getProtocolName(dnsProtocol)}"
            ))

            // Verificar certificate pinning
            val certPinningEnabled = preferences.getBoolean("CERTIFICATE_PINNING", false)
            auditResults.add(SecurityAuditItem(
                "Certificate Pinning",
                "Verifica si se está validando el certificado del servidor",
                if (certPinningEnabled) SecurityAuditStatus.PASS else SecurityAuditStatus.WARNING,
                if (certPinningEnabled) "Activado" else "Desactivado - Se recomienda activar"
            ))

            // Verificar protección de fugas DNS
            val dnsLeakProtection = preferences.getBoolean("DNS_LEAK_PROTECTION", true)
            auditResults.add(SecurityAuditItem(
                "Protección contra fugas DNS",
                "Previene fugas de consultas DNS fuera del túnel seguro",
                if (dnsLeakProtection) SecurityAuditStatus.PASS else SecurityAuditStatus.FAIL,
                if (dnsLeakProtection) "Activado" else "Desactivado - Vulnerabilidad de seguridad"
            ))

            // Verificar bloqueo de malware
            val malwareBlocker = preferences.getBoolean("MALWARE_BLOCKER", true)
            auditResults.add(SecurityAuditItem(
                "Protección contra malware",
                "Bloqueo de dominios maliciosos conocidos",
                if (malwareBlocker) SecurityAuditStatus.PASS else SecurityAuditStatus.WARNING,
                if (malwareBlocker) "Activado" else "Desactivado - Se recomienda activar"
            ))

            // Verificar URL del servidor
            val serverUrl = preferences.getString("SERVER_URL", "https://cloudflare-dns.com") ?: ""
            val isSecureUrl = serverUrl.startsWith("https://")
            auditResults.add(SecurityAuditItem(
                "URL del servidor",
                "Verifica si la URL del servidor utiliza HTTPS",
                if (isSecureUrl) SecurityAuditStatus.PASS else SecurityAuditStatus.FAIL,
                "URL actual: $serverUrl"
            ))

            // Actualizar UI
            runOnUiThread {
                progressBar.visibility = View.GONE
                auditButton.isEnabled = true
                adapter.notifyDataSetChanged()

                // Mostrar resumen
                val passCount = auditResults.count { it.status == SecurityAuditStatus.PASS }
                val total = auditResults.size
                Snackbar.make(
                    recyclerView,
                    "Auditoría completada: $passCount/$total verificaciones aprobadas",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getProtocolName(protocol: String): String {
        return when (protocol) {
            "doh" -> "DNS over HTTPS (Seguro)"
            "dot" -> "DNS over TLS (Seguro)"
            "doq" -> "DNS over QUIC (Seguro)"
            "plain" -> "DNS estándar (No seguro)"
            else -> protocol
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Clase para datos de auditoría
    data class SecurityAuditItem(
        val title: String,
        val description: String,
        val status: SecurityAuditStatus,
        val details: String
    )

    // Estados posibles
    enum class SecurityAuditStatus {
        PASS, WARNING, FAIL
    }

    // Adaptador para RecyclerView
    inner class SecurityAuditAdapter(private val items: List<SecurityAuditItem>) :
        RecyclerView.Adapter<SecurityAuditAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(R.id.titleText)
            val descriptionText: TextView = view.findViewById(R.id.descriptionText)
            val statusIcon: ImageView = view.findViewById(R.id.statusIcon)
            val detailsText: TextView = view.findViewById(R.id.detailsText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_security_audit, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.titleText.text = item.title
            holder.descriptionText.text = item.description
            holder.detailsText.text = item.details

            // Configurar icono según el estado
            when (item.status) {
                SecurityAuditStatus.PASS -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_check_circle)
                    holder.statusIcon.setColorFilter(
                        ContextCompat.getColor(this@SecurityAuditActivity, R.color.status_pass)
                    )
                }
                SecurityAuditStatus.WARNING -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_warning)
                    holder.statusIcon.setColorFilter(
                        ContextCompat.getColor(this@SecurityAuditActivity, R.color.status_warning)
                    )
                }
                SecurityAuditStatus.FAIL -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_error)
                    holder.statusIcon.setColorFilter(
                        ContextCompat.getColor(this@SecurityAuditActivity, R.color.status_fail)
                    )
                }
            }
        }

        override fun getItemCount() = items.size
    }
}