package com.ventaone.dnsvpn

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class NewActivityMain : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val onButton: Button = findViewById(R.id.onButton)
        val offButton: Button = findViewById(R.id.offButton)

        // Acción del botón "Iniciar VPN"
        onButton.setOnClickListener {
            val intent = Intent(this, DnsVpnService::class.java)
            startService(intent)

            // Esconder el botón de inicio y mostrar el botón de detener
            onButton.visibility = View.GONE
            offButton.visibility = View.VISIBLE
        }

        // Acción del botón "Detener VPN"
        offButton.setOnClickListener {
            val intent = Intent(this, DnsVpnService::class.java)
            stopService(intent)

            // Esconder el botón de detener y mostrar el botón de inicio
            offButton.visibility = View.GONE
            onButton.visibility = View.VISIBLE
        }
    }
}
