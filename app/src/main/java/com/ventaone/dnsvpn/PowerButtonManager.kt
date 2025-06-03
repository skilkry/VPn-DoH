package com.ventaone.dnsvpn

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.ImageView // Asegúrate de tener esta importación si connectionStatusIcon es ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip

class PowerButtonManager(
    private val context: Context,
    private val buttonContainer: MaterialCardView,
    private val powerIcon: AppCompatImageView,
    private val statusChip: Chip,
    private val statusText: TextView,
    // private val connectionStatusIcon: ImageView, // DESCOMENTA SI LO AÑADES
    private val stateListener: VpnStateListener?
) {

    // Enum para los estados de la UI
    enum class VpnUiState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private var currentUiState = VpnUiState.DISCONNECTED // Estado inicial
    private var vpnIsActive = false // Para el toggle interno

    private val PULSE_ANIMATOR_TAG = 101
    private var rotateAnimation: Animation? = null // Para la animación de rotación

    interface VpnStateListener {
        fun onVpnStartRequested()
        fun onVpnStopRequested()
    }

    init {
        setupButton()
        // Inicializar la UI al estado desconectado por defecto
        updateUiForState(VpnUiState.DISCONNECTED, false)
    }

    private fun setupButton() {
        buttonContainer.setOnClickListener {
            toggleVpnService()
        }
    }

    private fun toggleVpnService() {
        provideHapticFeedback()
        // La animación de animateButtonPress ya no está aquí, se llama desde MainActivity o al cambiar estado si se desea
        // animateButtonPress() // Puedes mantenerla si quieres la animación en cada toque físico

        vpnIsActive = !vpnIsActive // Invierte el estado lógico de la VPN

        if (vpnIsActive) {
            // El usuario quiere iniciar la VPN. La UI pasará a CONECTANDO.
            // El listener notificará a MainActivity para que inicie el servicio.
            stateListener?.onVpnStartRequested()
            // La UI se actualizará a CONECTANDO desde MainActivity o cuando el servicio notifique.
        } else {
            // El usuario quiere detener la VPN.
            stateListener?.onVpnStopRequested()
            // La UI se actualizará a DESCONECTADO desde MainActivity o cuando el servicio notifique.
        }
    }


    private fun provideHapticFeedback() { //
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator? //
        vibrator?.let { //
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { //
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)) //
            } else { //
                @Suppress("DEPRECATION")
                vibrator.vibrate(50) //
            }
        }
    }

    // Esta animación es para el feedback del click, mantenla si te gusta.
    // Considera si el StateListAnimator que añadimos en el XML es suficiente o prefieres esta.
    // Si usas esta, elimina el stateListAnimator del XML para evitar conflictos.
    private fun animateButtonPress() { //
        val animatorSet = AnimatorSet() //

        val scaleDownX = ObjectAnimator.ofFloat(buttonContainer, "scaleX", 0.9f) //
        val scaleDownY = ObjectAnimator.ofFloat(buttonContainer, "scaleY", 0.9f) //
        scaleDownX.duration = 100 //
        scaleDownY.duration = 100 //

        val scaleUpX = ObjectAnimator.ofFloat(buttonContainer, "scaleX", 1.0f) //
        val scaleUpY = ObjectAnimator.ofFloat(buttonContainer, "scaleY", 1.0f) //
        scaleUpX.duration = 200 //
        scaleUpY.duration = 200 //
        scaleUpX.interpolator = android.view.animation.OvershootInterpolator(1.5f) //
        scaleUpY.interpolator = android.view.animation.OvershootInterpolator(1.5f) //

        animatorSet.play(scaleDownX).with(scaleDownY) //
        animatorSet.play(scaleUpX).with(scaleUpY).after(scaleDownX) //
        animatorSet.start() //
    }

    /**
     * Actualiza la UI según el estado de la VPN proporcionado.
     * @param newState El nuevo estado de la UI para la VPN.
     * @param serviceIsActuallyActive Actualiza el estado lógico interno si es necesario.
     */
    fun updateUiForState(newState: VpnUiState, serviceIsActuallyActive: Boolean? = null) {
        currentUiState = newState
        serviceIsActuallyActive?.let { vpnIsActive = it }


        // Detener animaciones previas
        stopPulseAnimation() //
        stopRotateAnimation()

        try {
            when (newState) {
                VpnUiState.DISCONNECTED -> {
                    buttonContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.neutral_gray_disconnected))
                    powerIcon.setImageResource(R.drawable.ic_power_outline_24dp) // O tu power_off
                    powerIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusChip.text = context.getString(R.string.offline)
                    statusChip.setChipBackgroundColorResource(R.color.neutral_gray_disconnected)
                    statusChip.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusText.text = context.getString(R.string.disconnected)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white)) // O text_secondary_gray si el fondo de statusIndicator es claro

                    // connectionStatusIcon.setImageResource(R.drawable.ic_status_disconnected_gray)
                    // connectionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary_gray))
                }
                VpnUiState.CONNECTING -> {
                    buttonContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.vibrant_blue_primary))
                    powerIcon.setImageResource(R.drawable.ic_hourglass_empty_24dp) // Ejemplo de icono "conectando"
                    powerIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_primary_white))
                    startRotateAnimation()

                    statusChip.text = context.getString(R.string.connecting_status) // e.g., "Conectando..."
                    statusChip.setChipBackgroundColorResource(R.color.vibrant_blue_primary)
                    statusChip.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusText.text = context.getString(R.string.connecting_status)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    // connectionStatusIcon.setImageResource(R.drawable.ic_status_connecting_blue)
                    // connectionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.vibrant_blue_primary))
                }
                VpnUiState.CONNECTED -> {
                    buttonContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.accent_green_connected))
                    powerIcon.setImageResource(R.drawable.ic_power_outline_24dp) // O tu power_on
                    powerIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_primary_white))
                    startPulseAnimation() //

                    statusChip.text = context.getString(R.string.online)
                    statusChip.setChipBackgroundColorResource(R.color.accent_green_connected)
                    statusChip.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusText.text = context.getString(R.string.connected)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    // connectionStatusIcon.setImageResource(R.drawable.ic_status_connected_green)
                    // connectionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent_green_connected))
                }
                VpnUiState.ERROR -> {
                    buttonContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.accent_red_error))
                    powerIcon.setImageResource(R.drawable.ic_error_outline_24dp) // Ejemplo de icono "error"
                    powerIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusChip.text = context.getString(R.string.error_status) // e.g., "Error"
                    statusChip.setChipBackgroundColorResource(R.color.accent_red_error)
                    statusChip.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    statusText.text = context.getString(R.string.connection_error_status) // e.g., "Error de Conexión"
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.text_primary_white))

                    // connectionStatusIcon.setImageResource(R.drawable.ic_status_error_red)
                    // connectionStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.accent_red_error))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRotateAnimation() {
        if (rotateAnimation == null) {
            rotateAnimation = AnimationUtils.loadAnimation(context, R.anim.rotate_indefinitely_power_icon) // Crea este anim XML
        }
        powerIcon.startAnimation(rotateAnimation)
    }

    private fun stopRotateAnimation() {
        powerIcon.clearAnimation()
    }


    private fun startPulseAnimation() { // Modificado de tu código original
        try {
            // Crear un pulso suave usando el strokeWidth de la tarjeta
            val pulseAnimator = ValueAnimator.ofFloat(2f, 8f, 2f) // Aumentado el efecto
            pulseAnimator.duration = 1500 // Más rápido
            pulseAnimator.repeatCount = ValueAnimator.INFINITE
            pulseAnimator.interpolator = LinearInterpolator()
            pulseAnimator.addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                buttonContainer.strokeWidth = value.toInt()
                // Opcional: Animar el color del borde también
                // buttonContainer.strokeColor = ContextCompat.getColor(context, R.color.white_transparent_pulse)
            }
            pulseAnimator.start()
            buttonContainer.setTag(PULSE_ANIMATOR_TAG, pulseAnimator) //
        } catch (e: Exception) {
            e.printStackTrace() //
        }
    }

    private fun stopPulseAnimation() { // Modificado de tu código original
        try {
            val tag = buttonContainer.getTag(PULSE_ANIMATOR_TAG) //
            if (tag is ValueAnimator) { //
                tag.cancel() //
            }
            buttonContainer.strokeWidth = 0 // Restaurar valor original (o el que tenías para el estado no pulsante)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}