package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

class ConfigurationRepository(context: Context, lifecycleOwner: LifecycleOwner) : AutoCloseable {

    enum class Preferences(val key: String) {
        ShowTimerWhenMinimized("showTimerWhenMinimized"),
        BleTxEnabled("bleTxEnabled"),
    BleFtmsDeviceName("bleFtmsDeviceName"),
    SerialNumber("serialNumber")
    }

    companion object {
        const val SharedPrefsName = "configuration"
        // This workaround is required since SharedPreferences
        // only stores weak references to objects
        val SharedPreferenceListeners =
            mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    private val mutableShowTimerWhenMinimized = MutableStateFlow(true)
    private val mutableBleTxEnabled = MutableStateFlow(true)
    private val mutableBleFtmsDeviceName = MutableStateFlow("Grupetto FTMS")
    private val mutableSerialNumber = MutableStateFlow("")

    val showTimerWhenMinimized = mutableShowTimerWhenMinimized
    val bleTxEnabled = mutableBleTxEnabled
    val endurainHost: MutableStateFlow<String> by lazy {
        MutableStateFlow(sharedPreferences.getString(Preferences.EndurainHost.key, "") ?: "")
    }
    val endurainUsername: MutableStateFlow<String> by lazy {
        MutableStateFlow(sharedPreferences.getString(Preferences.EndurainUsername.key, "") ?: "")
    }
    val endurainPassword: MutableStateFlow<String> by lazy {
        MutableStateFlow(sharedPreferences.getString(Preferences.EndurainPassword.key, "") ?: "")
    }

    val bleFtmsDeviceName = mutableBleFtmsDeviceName
    val serialNumber = mutableSerialNumber

    private val sharedPreferences: SharedPreferences

    // Must be kept as reference, unowned lambda would be garbage collected
    private fun createSharedPreferencesListener() =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updateFromSharedPrefs()
        }

    private val listener : SharedPreferences.OnSharedPreferenceChangeListener

    init {
        sharedPreferences = context.getSharedPreferences(SharedPrefsName, Context.MODE_PRIVATE)
        updateFromSharedPrefs()

        listener = createSharedPreferencesListener()
        SharedPreferenceListeners.add(listener)
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                close()
            }
        })
    }

    fun setShowTimerWhenMinimized(isShown: Boolean) {
        mutableShowTimerWhenMinimized.value = isShown
        sharedPreferences.edit {
            putBoolean(Preferences.ShowTimerWhenMinimized.key, isShown)
        }
    }

    fun setBleTxEnabled(enabled: Boolean) {
        mutableBleTxEnabled.value = enabled
        sharedPreferences.edit {
            putBoolean(Preferences.BleTxEnabled.key, enabled)
        }
    }

    fun setEndurainHost(value: String) {
        sharedPreferences.edit().putString(Preferences.EndurainHost.key, value).apply()
        endurainHost.value = value
    }

    fun setEndurainUsername(value: String) {
        sharedPreferences.edit().putString(Preferences.EndurainUsername.key, value).apply()
        endurainUsername.value = value
    }

    fun setEndurainPassword(value: String) {
        sharedPreferences.edit().putString(Preferences.EndurainPassword.key, value).apply()
        endurainPassword.value = value
    }

    fun setBleFtmsDeviceName(name: String) {
        mutableBleFtmsDeviceName.value = name
        sharedPreferences.edit {
            putString(Preferences.BleFtmsDeviceName.key, name)
        }
    }

    fun setSerialNumber(serial: String) {
        val normalized = serial.trim().uppercase()
        mutableSerialNumber.value = normalized
        sharedPreferences.edit {
            putString(Preferences.SerialNumber.key, normalized)
        }
    }

    private fun generateSerialHex(): String {
        val value = kotlin.random.Random.nextInt(0x10000)
        return value.toString(16).padStart(4, '0').uppercase()
    }

    private fun updateFromSharedPrefs() {
        mutableShowTimerWhenMinimized.value =
            sharedPreferences
                .getBoolean(Preferences.ShowTimerWhenMinimized.key, true)

        mutableBleTxEnabled.value =
            sharedPreferences
                .getBoolean(Preferences.BleTxEnabled.key, true)

        mutableBleFtmsDeviceName.value =
            sharedPreferences
                .getString(Preferences.BleFtmsDeviceName.key, "Grupetto FTMS") ?: "Grupetto FTMS"

        // Ensure a serial number exists and keep it in memory
        val existingSerial = sharedPreferences.getString(Preferences.SerialNumber.key, null)
        val ensuredSerial = if (existingSerial.isNullOrEmpty()) {
            val sn = generateSerialHex()
            sharedPreferences.edit { putString(Preferences.SerialNumber.key, sn) }
            sn
        } else existingSerial
        mutableSerialNumber.value = ensuredSerial
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        SharedPreferenceListeners.remove(listener)
    }
}
