package com.ventaone.dnsvpn.util
import com.ventaone.dnsvpn.MainActivity
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manejador de excepciones para capturar crashes de la aplicación
 * Guarda los logs y permite compartirlos por correo u otras aplicaciones
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler?
    private val TAG = "CrashHandler"
    private val EMAIL_ADDRESS = "desarrollador@ventaone.com" // Cambia esto por tu correo

    init {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    companion object {
        private var instance: CrashHandler? = null

        /**
         * Inicializa el manejador de excepciones
         */
        @JvmStatic
        fun init(context: Context) {
            if (instance == null) {
                instance = CrashHandler(context.applicationContext)
            }
            Thread.setDefaultUncaughtExceptionHandler(instance)
            Log.d("CrashHandler", "Sistema de captura de errores inicializado")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Recopilar información del error
            val crashInfo = collectCrashInfo(throwable)

            // Guardar log en archivo
            val logFile = saveLogToFile(crashInfo)

            // Si estamos en una actividad, mostrar diálogo para compartir
            if (context is Activity) {
                showCrashDialog(context, logFile, crashInfo)
            } else {
                // Si no estamos en una actividad, intentar iniciar una nueva para mostrar el error
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("CRASH_LOG_PATH", logFile.absolutePath)
                    putExtra("CRASH_INFO", crashInfo)
                }
                context.startActivity(intent)
            }

            // Esperar un momento para que el diálogo se muestre
            Thread.sleep(3000)
        } catch (e: Exception) {
            Log.e(TAG, "Error en el manejador de excepciones", e)
        }

        // Llamar al manejador por defecto para finalizar la app
        defaultHandler?.uncaughtException(thread, throwable)

        // Si no hay manejador por defecto, finalizamos el proceso
        Process.killProcess(Process.myPid())
        System.exit(1)
    }

    /**
     * Recopila información detallada sobre el error
     */
    private fun collectCrashInfo(throwable: Throwable): String {
        val result = StringBuilder()

        // Información de la aplicación
        try {
            val packageManager = context.packageManager
            val packageInfo: PackageInfo = packageManager.getPackageInfo(context.packageName, 0)
            result.append("App Version: ${packageInfo.versionName} (${packageInfo.versionCode})\n")
        } catch (e: PackageManager.NameNotFoundException) {
            result.append("App Version: Desconocida\n")
        }

        // Información del dispositivo
        result.append("\nDISPOSITIVO\n")
        result.append("Marca: ${Build.BRAND}\n")
        result.append("Modelo: ${Build.MODEL}\n")
        result.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")

        // Stack trace del error
        result.append("\nSTACK TRACE\n")
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        result.append(sw.toString())

        // Logs del sistema
        result.append("\nLOGS DEL SISTEMA\n")
        try {
            val command = "logcat -d -v threadtime"
            val process = Runtime.getRuntime().exec(command)
            val bufferedReader = process.inputStream.bufferedReader()
            val logs = bufferedReader.use { it.readText() }

            // Filtrar solo los logs de nuestra aplicación para no hacer el archivo muy grande
            val ourLogLines = logs.lines().filter {
                it.contains("ventaone") || it.contains(context.packageName) || it.contains("DnsVpn")
            }
            result.append(ourLogLines.joinToString("\n"))
        } catch (e: Exception) {
            result.append("No se pudieron obtener logs del sistema: ${e.message}\n")
        }

        return result.toString()
    }

    /**
     * Guarda el log en un archivo
     */
    private fun saveLogToFile(crashInfo: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "crash_${timestamp}.log"

        // Guardar en el directorio de archivos privados de la app
        val logFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        logFile.writeText(crashInfo)

        return logFile
    }

    /**
     * Muestra diálogo para compartir el informe de error
     */
    private fun showCrashDialog(activity: Activity, logFile: File, crashInfo: String) {
        try {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("¡Ups! Algo salió mal")
                    .setMessage("La aplicación ha sufrido un error. ¿Te gustaría enviar un informe para ayudarnos a solucionar el problema?")
                    .setPositiveButton("Enviar informe") { _, _ ->
                        shareLogFile(activity, logFile, crashInfo)
                    }
                    .setNegativeButton("Cerrar") { _, _ ->
                        Process.killProcess(Process.myPid())
                        System.exit(1)
                    }
                    .setCancelable(false)
                    .show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al mostrar diálogo de crash", e)
        }
    }

    /**
     * Permite compartir el archivo de log
     */
    private fun shareLogFile(activity: Activity, logFile: File, crashInfo: String) {
        try {
            val fileUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                logFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL_ADDRESS))
                putExtra(Intent.EXTRA_SUBJECT, "Informe de error VentaOne DNSVPN")
                putExtra(Intent.EXTRA_TEXT, "Se ha producido un error en la aplicación. Adjunto encontrarás el archivo de log con los detalles.")
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(intent, "Enviar informe de error"))
        } catch (e: Exception) {
            Log.e(TAG, "Error al compartir archivo de log", e)

            // Como alternativa, si falla el compartir, intentamos copiar al portapapeles
            val clipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Crash Log", crashInfo)
            clipboardManager.setPrimaryClip(clip)

            AlertDialog.Builder(activity)
                .setTitle("Error al compartir")
                .setMessage("No se pudo compartir el archivo de log, pero se ha copiado al portapapeles. Por favor, envíalo manualmente.")
                .setPositiveButton("Aceptar", null)
                .show()
        }
    }
}