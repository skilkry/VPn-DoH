package com.ventaone.dnsvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Configurar la barra de herramientas
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Acerca de"

        // Obtener la versión de la aplicación y mostrarla
        val versionText = findViewById<TextView>(R.id.versionText)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        versionText.text = "Versión ${packageInfo.versionName}"

        // Configurar el botón de GitHub
        val githubButton = findViewById<ImageButton>(R.id.githubButton)
        githubButton.setOnClickListener {
            // Abrir el perfil de GitHub en el navegador
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/skilkry"))
            startActivity(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Manejar la acción de retroceso en la barra de herramientas
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}