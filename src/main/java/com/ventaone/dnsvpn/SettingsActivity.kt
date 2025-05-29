package com.ventaone.dnsvpn

import android.os.Bundle
import android.text.InputType
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager // Keep if needed for other logic, but not for listener here
import androidx.preference.SwitchPreference // Keep if needed for other logic, but not for listener here
import androidx.appcompat.widget.Toolbar // Import Toolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Assuming you have a layout file for SettingsActivity that contains a FrameLayout
        // or similar container with id R.id.settings_container
        setContentView(R.layout.activity_settings) // Make sure this layout exists

        // Set up the Toolbar (using the correct ID from your activity_settings.xml)
        val toolbar: Toolbar = findViewById(R.id.toolbar) // <-- CORRECTED ID HERE
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Show the back button
        supportActionBar?.setDisplayShowTitleEnabled(true) // Show the title
        // The title "Configuración" is already set in your XML, but you can set it programmatically too
        // supportActionBar?.title = getString(R.string.settings_title) // Define settings_title in strings.xml


        if (savedInstanceState == null) {
            // Load the SettingsFragment into the container
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()) // R.id.settings_container is the ID of the container in activity_settings.xml
                .commit()
        }

        // Logic to open a specific section (optional, depends on your preference structure)
        // This would typically involve navigating within the PreferenceFragment
        val openSection = intent.getStringExtra("OPEN_SECTION")
        if (openSection == "SERVER") {
            // You would handle this within the SettingsFragment, perhaps by scrolling
            // to a specific preference or category. This requires more complex handling
            // within the Fragment's onCreatePreferences or a separate method.
            // For now, we'll leave this as a placeholder.
        }
    }

    // Handle the back button in the Toolbar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed() // Or finish()
        return true
    }


    // Inner class for the Preference Fragment
    class SettingsFragment : PreferenceFragmentCompat() {

        private val TAG = "SettingsFragment" // Add a TAG for logging

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Load the preferences from the XML resource
            setPreferencesFromResource(R.xml.preferences, rootKey) // Ensure R.xml.preferences exists and contains your switches

            // --- Preference Specific Logic ---

            // Configure the input type for the Server URL EditTextPreference
            val serverUrlPreference: EditTextPreference? = findPreference("SERVER_URL")
            serverUrlPreference?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            // Optional: Set a dynamic summary for the server URL preference
            serverUrlPreference?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val text = preference.text
                if (text.isNullOrEmpty()) {
                    getString(R.string.server_url_summary_default) // Define a default summary string
                } else {
                    text
                }
            }


            // Configure click listener for the Certificate Verification Preference
            // (Assuming this preference navigates to CertificateVerificationActivity)
            val certVerificationPreference: Preference? = findPreference("VERIFY_CERTIFICATE") // Ensure this key exists in your XML
            certVerificationPreference?.setOnPreferenceClickListener {
                activity?.let { currentActivity ->
                    val intent = android.content.Intent(
                        currentActivity,
                        CertificateVerificationActivity::class.java
                    )
                    currentActivity.startActivity(intent)
                }
                true // Indicate that the click event was handled
            }
            // Optional: Set a dynamic summary for the certificate verification preference
            // This might show the current status (Valid, Invalid, etc.)
            // You would need a way to get the current status from the CertificateMonitor
            // certVerificationPreference?.summaryProvider = Preference.SummaryProvider<Preference> {
            //     // Logic to get current cert status and return a string
            //     getString(R.string.cert_status_summary_placeholder) // Replace with actual status logic
            // }


            // Configure preference change listener for the Notification Switch
            // (This logic handles enabling/disabling notifications, NOT VPN restart)
            val notificationSwitch: SwitchPreference? = findPreference("NOTIFICATION_ENABLED") // Ensure this key exists in your XML
            notificationSwitch?.setOnPreferenceChangeListener { preference, newValue ->
                val isEnabled = newValue as Boolean
                Log.d(TAG, "Notification preference changed to: $isEnabled")
                // Here you would manage the logic to enable/disable system notifications
                // or adjust notification behavior within your app/service.
                // This typically involves interacting with the NotificationManager or your service.
                if (isEnabled) {
                    // Logic to enable notifications (e.g., ensure channels are created, service shows foreground notif)
                    // If your service handles notifications, you might send an Intent to it.
                    // Example: sendIntentToService(DnsVpnService.ACTION_ENABLE_NOTIFICATIONS) // Define this action
                } else {
                    // Logic to disable notifications (e.g., cancel ongoing notifications, adjust service behavior)
                    // Example: sendIntentToService(DnsVpnService.ACTION_DISABLE_NOTIFICATIONS) // Define this action
                }
                true // Indicate that the change was accepted and should be saved
            }

            // --- No need for SharedPreferences.OnSharedPreferenceChangeListener here for VPN restart ---
            // The listener in MainActivity handles triggering the VPN restart when
            // AD_BLOCKER, MALWARE_BLOCKER, USE_CUSTOM_DNS, PRIMARY_DNS, SECONDARY_DNS, or SERVER_URL change.
            // This fragment's role is primarily to display and allow editing of preferences.

        } // End of onCreatePreferences

        // Helper function to send intents to the VpnService (Optional, but can be useful)
        // private fun sendIntentToService(action: String) {
        //     val intent = Intent(context, DnsVpnService::class.java)
        //     intent.action = action
        //     context?.startService(intent)
        // }


        // Implement onResume and onPause if you need to register/unregister listeners
        // specific to this fragment (e.g., for dynamic summaries based on external state).
        // For the VPN restart listener, it's in MainActivity.
        // If you add listeners here, remember to unregister them in onPause.
        // override fun onResume() {
        //     super.onResume()
        //     // Register listeners specific to SettingsFragment if needed
        // }
        //
        // override fun onPause() {
        //     super.onPause()
        //     // Unregister listeners specific to SettingsFragment
        // }

    } // End of SettingsFragment class
} // End of SettingsActivity class
