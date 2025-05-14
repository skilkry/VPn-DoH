package com.ventaone.dnsvpn

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip

/**
 * Administra el botón de encendido/apagado de la VPN y sus animaciones asociadas
 * También proporciona retroalimentación táctil y visual cuando el usuario interactúa con él
 */
class PowerButtonManager(
    private val context: Context,
    private val buttonContainer: MaterialCardView,
    private val powerIcon: AppCompatImageView,
    private val statusChip: Chip,
    private val statusText: TextView,
    private val stateListener: VpnStateListener?
) {
    // Estado actual del botón (activado/desactivado)
    private var isActive = false

    // Tag para identificar la animación del pulso
    private val PULSE_ANIMATOR_TAG = 101

    // Interfaz para comunicar los cambios de estado a la actividad principal
    interface VpnStateListener {
        fun onVpnStartRequested()
        fun onVpnStopRequested()
    }

    init {
        setupButton()
    }

    private fun setupButton() {
        // Configura el listener de click para el contenedor del botón
        buttonContainer.setOnClickListener { toggleVpnState() }
    }

    /**
     * Cambia el estado del botón y notifica al listener
     */
    fun toggleVpnState() {
        isActive = !isActive

        // Proporcionar retroalimentación táctil
        provideHapticFeedback()

        // Animar el botón al presionarlo
        animateButtonPress()

        // Actualizar la UI y notificar al listener según el nuevo estado
        if (isActive) {
            setActiveState()
            stateListener?.onVpnStartRequested()
        } else {
            setInactiveState()
            stateListener?.onVpnStopRequested()
        }
    }

    /**
     * Proporciona vibración al presionar el botón
     */
    private fun provideHapticFeedback() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
    }

    /**
     * Animación de escala cuando se presiona el botón
     */
    private fun animateButtonPress() {
        val animatorSet = AnimatorSet()

        // Animación de reducción de escala
        val scaleDownX = ObjectAnimator.ofFloat(buttonContainer, "scaleX", 0.9f)
        val scaleDownY = ObjectAnimator.ofFloat(buttonContainer, "scaleY", 0.9f)
        scaleDownX.duration = 100
        scaleDownY.duration = 100

        // Animación de regreso a escala normal con rebote
        val scaleUpX = ObjectAnimator.ofFloat(buttonContainer, "scaleX", 1.0f)
        val scaleUpY = ObjectAnimator.ofFloat(buttonContainer, "scaleY", 1.0f)
        scaleUpX.duration = 200
        scaleUpY.duration = 200
        scaleUpX.interpolator = android.view.animation.OvershootInterpolator(1.5f)
        scaleUpY.interpolator = android.view.animation.OvershootInterpolator(1.5f)

        // Reproducir la secuencia de animaciones
        animatorSet.play(scaleDownX).with(scaleDownY)
        animatorSet.play(scaleUpX).with(scaleUpY).after(scaleDownX)
        animatorSet.start()
    }

    /**
     * Configura la UI para el estado activo (VPN conectada)
     */
    private fun setActiveState() {
        try {
            // Cambiar el color del botón
            buttonContainer.setCardBackgroundColor(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.buttonOn))
            )

            // Cambiar el icono de encendido
            powerIcon.setImageResource(R.drawable.power_on)

            // Actualizar el chip de estado
            statusChip.text = context.getString(R.string.online)
            statusChip.setChipBackgroundColor(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.statusConnected))
            )

            // Actualizar el texto de estado
            statusText.text = context.getString(R.string.connected)

            // Iniciar la animación de pulso
            pulseAnimation(true)
        } catch (e: Exception) {
            // Manejo de errores por si algún recurso no está disponible
            e.printStackTrace()
        }
    }

    /**
     * Configura la UI para el estado inactivo (VPN desconectada)
     */
    private fun setInactiveState() {
        try {
            // Cambiar el color del botón
            buttonContainer.setCardBackgroundColor(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.buttonOff))
            )

            // Cambiar el icono a apagado
            powerIcon.setImageResource(R.drawable.power_off)

            // Actualizar el chip de estado
            statusChip.text = context.getString(R.string.offline)
            statusChip.setChipBackgroundColor(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.statusDisconnected))
            )

            // Actualizar el texto de estado
            statusText.text = context.getString(R.string.disconnected)

            // Detener la animación de pulso
            pulseAnimation(false)
        } catch (e: Exception) {
            // Manejo de errores por si algún recurso no está disponible
            e.printStackTrace()
        }
    }

    /**
     * Controla la animación de pulso del borde del botón
     * @param start true para iniciar la animación, false para detenerla
     */
    private fun pulseAnimation(start: Boolean) {
        try {
            if (start) {
                // Crear un pulso suave usando el strokeWidth de la tarjeta
                val pulseAnimator = ValueAnimator.ofFloat(2f, 6f, 2f)
                pulseAnimator.duration = 2000
                pulseAnimator.repeatCount = ValueAnimator.INFINITE
                pulseAnimator.addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    buttonContainer.strokeWidth = value.toInt()
                }
                pulseAnimator.start()

                // Guardar el animador como tag para poder detenerlo después
                buttonContainer.setTag(PULSE_ANIMATOR_TAG, pulseAnimator)
            } else {
                // Detener la animación si existe
                val tag = buttonContainer.getTag(PULSE_ANIMATOR_TAG)
                if (tag is ValueAnimator) {
                    tag.cancel()
                    buttonContainer.strokeWidth = 2 // Restaurar valor original
                }
            }
        } catch (e: Exception) {
            // Manejo silencioso de errores si no se puede animar
            e.printStackTrace()
        }
    }

    /**
     * Actualiza el estado visual del botón sin activar los callbacks
     * Útil cuando se restaura el estado desde la actividad
     * @param active true si la VPN está activa, false si está inactiva
     */
    fun updateState(active: Boolean) {
        if (this.isActive != active) {
            this.isActive = active
            if (active) {
                setActiveState()
            } else {
                setInactiveState()
            }
        }
    }
}