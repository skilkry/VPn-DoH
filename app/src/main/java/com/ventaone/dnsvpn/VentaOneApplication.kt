package com.ventaone.dnsvpn

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ventaone.dnsvpn.util.CrashHandler

/**
 * Clase de aplicación principal donde inicializamos el manejador de excepciones
 */
class VentaOneApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Inicializar el manejador de excepciones
        CrashHandler.init(this)

        Log.d("VentaOneApplication", "Aplicación inicializada con manejador de excepciones")
    }
}