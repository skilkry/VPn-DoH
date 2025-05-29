package com.ventaone.dnsvpn

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.app.AlertDialog

class DropdownMenuController(
    private val gearButton: ImageButton,
    private val dnsButton: Button,
    private val certsButton: Button,
    private val serverButton: Button
) {
    private var isMenuVisible = false

    init {
        // Ocultar botones del menú inicialmente
        hideMenu()

        // Configurar el botón de engranaje para mostrar/ocultar el menú
        gearButton.setOnClickListener {
            toggleMenu()
        }

        // Configurar el botón DNS para abrir la configuración de DNS directamente
        dnsButton.setOnClickListener {
            val context = dnsButton.context
            val intent = Intent(context, DnsSettingsActivity::class.java)
            context.startActivity(intent)
        }

        // Configurar botón Certs con selección de opciones
        certsButton.setOnClickListener {
            val context = certsButton.context

            // Crear un diálogo para elegir entre "Verificar Certificados" o "Configuración del Servidor"
            val options = arrayOf("Verificar Certificados", "Configuración del Servidor")

            AlertDialog.Builder(context)
                .setTitle("Seleccione una opción")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            // Opción: Verificar Certificados
                            val certIntent = Intent(context, CertificateVerificationActivity::class.java)
                            context.startActivity(certIntent)
                        }
                        1 -> {
                            // Opción: Configuración del Servidor
                            val serverIntent = Intent(context, SettingsActivity::class.java)
                            context.startActivity(serverIntent)
                        }
                    }
                }
                .show()
        }

        // No agregar funcionalidad a serverButton por ahora
        serverButton.setOnClickListener {
            // Mantener sin funcionalidad como solicitaste
        }
    }

    private fun toggleMenu() {
        if (isMenuVisible) {
            hideMenu()
        } else {
            showMenu()
        }
        isMenuVisible = !isMenuVisible
    }

    private fun showMenu() {
        dnsButton.visibility = View.VISIBLE
        certsButton.visibility = View.VISIBLE
        serverButton.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        dnsButton.visibility = View.GONE
        certsButton.visibility = View.GONE
        serverButton.visibility = View.GONE
    }
}