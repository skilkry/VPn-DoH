package com.ventaone.dnsvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat




class AboutActivity : AppCompatActivity() {

    private val animationDelay = 100L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Configurar la barra de herramientas
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Acerca de"

        // Obtener la versión actual de la aplicación
        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "Versión ${packageInfo.versionName}"
        } catch (e: Exception) {
            versionText.text = "Versión 1.0.0"
        }

        // Configurar botón de GitHub
        val githubButton = findViewById<View>(R.id.githubButton)
        githubButton.setOnClickListener {
            // Aplicar animación al botón cuando se presiona
            githubButton.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(0.8f)
                .setDuration(150)
                .withEndAction {
                    githubButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(150)
                        .start()
                }.start()

            // Retrasar la apertura del enlace para permitir que la animación termine
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/skilkry"))
                startActivity(intent)
            }, 300)
        }

        // Iniciar animaciones con retrasos escalonados
        startAnimationsWithDelay()
    }
    private fun animateView(view: View, animationResId: Int, delay: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            val animation = AnimationUtils.loadAnimation(this, animationResId)
            animation.fillAfter = true  // Esto mantiene la posición final
            view.startAnimation(animation)
            view.visibility = View.VISIBLE
        }, delay)
    }


    private fun startAnimationsWithDelay() {
        animateView(findViewById(R.id.logoContainer), R.anim.bounce, animationDelay)
        animateView(findViewById(R.id.appNameText), R.anim.pulse, animationDelay * 3)
        animateView(findViewById(R.id.versionText), R.anim.slide_up, animationDelay * 4)
        animateView(findViewById(R.id.descriptionCard), R.anim.slide_in_right, animationDelay * 5)
        animateView(findViewById(R.id.featuresTitle), R.anim.staggered_slide_up, animationDelay * 6)
        animateView(findViewById(R.id.featuresCard), R.anim.staggered_slide_up, animationDelay * 7)

        val featureIds = listOf(
            R.id.feature1, R.id.feature2, R.id.feature3, R.id.feature4, R.id.feature5
        )

        featureIds.forEachIndexed { index, id ->
            animateView(findViewById(id), R.anim.slide_in_right, animationDelay * (8 + index))
        }

        animateView(findViewById(R.id.developerTitle), R.anim.staggered_slide_up, animationDelay * 13)
        animateView(findViewById(R.id.developerCard), R.anim.staggered_slide_up, animationDelay * 14)
        animateView(findViewById(R.id.licenseText), R.anim.pulse, animationDelay * 16)
    }


    private fun animateFeatureItems() {
        val slideInRight = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)

        // Animar cada elemento de característica con un pequeño retraso
        Handler(Looper.getMainLooper()).postDelayed({
            findViewById<View>(R.id.feature1).startAnimation(slideInRight)
        }, animationDelay * 8)

        Handler(Looper.getMainLooper()).postDelayed({
            findViewById<View>(R.id.feature2).startAnimation(slideInRight)
        }, animationDelay * 9)

        Handler(Looper.getMainLooper()).postDelayed({
            findViewById<View>(R.id.feature3).startAnimation(slideInRight)
        }, animationDelay * 10)

        Handler(Looper.getMainLooper()).postDelayed({
            findViewById<View>(R.id.feature4).startAnimation(slideInRight)
        }, animationDelay * 11)

        Handler(Looper.getMainLooper()).postDelayed({
            findViewById<View>(R.id.feature5).startAnimation(slideInRight)
        }, animationDelay * 12)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // Agregar una animación de transición cuando se cierra la actividad
            finish()
            overridePendingTransition(R.anim.pulse, R.anim.slide_up)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // Agregar una animación de transición cuando se presiona el botón Atrás
        overridePendingTransition(R.anim.pulse, R.anim.slide_up)
    }
}