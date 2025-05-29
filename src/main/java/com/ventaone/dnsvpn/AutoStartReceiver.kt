package com.ventaone.dnsvpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager

class AutoStartReceiver : BroadcastReceiver() {
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent) {
        // Verificar que el evento recibido sea BOOT_COMPLETED
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Obtener las preferencias
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoStartEnabled = prefs.getBoolean("AUTO_START", false)
            // Si la opción de auto arranque está activada, iniciar el servicio VPN
            if (autoStartEnabled) {
                val vpnIntent = Intent(context, DnsVpnService::class.java)

                // Usar startForegroundService para Android O y superior
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }

                showVpnNotification(context)  // Mostrar la notificación
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showVpnNotification(context: Context) {
        val channelId = "vpn_channel"

        // Crear el canal de notificación para Android O y versiones superiores
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "VPN Notifications"
            val channelDescription = "Notifications related to the VPN service"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
            }
            // Registrar el canal de notificación
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Crear un PendingIntent para cuando el usuario toque la notificación
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),  // Asegúrate de usar tu actividad principal
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Construir la notificación
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("VPN Conectado")
            .setContentText("El VPN ha sido iniciado automáticamente.")
            .setSmallIcon(R.drawable.ic_vpn)  // Asegúrate de tener este ícono en tus recursos
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // La notificación se cancela al tocarla
            .build()

        try {
            // Mostrar la notificación usando NotificationManagerCompat
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            // Verificar permisos de notificación para Android 13+
            if (Build.VERSION.SDK_INT >= 33) {
                if (notificationManagerCompat.areNotificationsEnabled()) {
                    notificationManagerCompat.notify(1, notification)
                }
            } else {
                notificationManagerCompat.notify(1, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}