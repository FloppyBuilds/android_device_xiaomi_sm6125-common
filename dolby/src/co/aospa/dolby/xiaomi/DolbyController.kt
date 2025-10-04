/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyConstants.DsParam
import co.aospa.dolby.xiaomi.R
import co.aospa.dolby.xiaomi.device.AudioDeviceManager

internal class DolbyController private constructor(
    private val context: Context
) {
    private var dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(context.mainLooper)
    private val audioDeviceManager = AudioDeviceManager(context, handler)
    private var currentDevice: AudioDeviceInfo? = null

    // Restore current profile on every media session
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val isPlaying = configs.any {
                it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            }
            dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
            if (isPlaying) {
                // Check for device changes when music starts
                audioDeviceManager.checkForDeviceChange()
                setCurrentProfile()
            }
        }
    }

    // Handle audio output changes with per-device configuration
    private val audioOutputCallback = object : AudioDeviceManager.AudioOutputChangedCallback {
        override fun onAudioOutputChanged(currentDevice: AudioDeviceInfo?) {
            dlog(TAG, "onAudioOutputChanged: ${audioDeviceManager.getDeviceTypeName(currentDevice)}")
            this@DolbyController.currentDevice = currentDevice
            setCurrentProfile()
        }
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager!!.registerAudioPlaybackCallback(playbackCallback, handler)
                audioDeviceManager.addCallback(audioOutputCallback)
            } else {
                audioManager!!.unregisterAudioPlaybackCallback(playbackCallback)
                audioDeviceManager.removeCallback(audioOutputCallback)
            }
        }

    var dsOn: Boolean
        get() = getDeviceEnabled(currentDevice)
        set(value) {
            dlog(TAG, "setDsOn: $value")
            setDeviceEnabled(currentDevice, value)
        }

    fun getDeviceEnabled(device: AudioDeviceInfo?): Boolean {
        val prefs = context.getSharedPreferences("device_${getDevicePrefsKey(device)}", Context.MODE_PRIVATE)
        val globalEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(DolbyConstants.PREF_ENABLE, true)

        // Default to disabled for speakers, enabled for other devices
        val defaultEnabled = if (audioDeviceManager.isSpeakerDevice(device)) {
            false
        } else {
            globalEnabled
        }
        
        val deviceEnabled = prefs.getBoolean(DolbyConstants.PREF_DEVICE_ENABLE, defaultEnabled)
        dlog(TAG, "getDeviceEnabled(${audioDeviceManager.getDeviceTypeName(device)}): $deviceEnabled (default: $defaultEnabled)")
        return deviceEnabled
    }

    fun setDeviceEnabled(device: AudioDeviceInfo?, enabled: Boolean) {
        dlog(TAG, "setDeviceEnabled(${audioDeviceManager.getDeviceTypeName(device)}, $enabled)")
        val prefs = context.getSharedPreferences("device_${getDevicePrefsKey(device)}", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DolbyConstants.PREF_DEVICE_ENABLE, enabled).apply()

        // Update the global enable state if this is the current device
        if (device == currentDevice) {
            updateDolbyEffect()
        }
    }

    private fun getDevicePrefsKey(device: AudioDeviceInfo?): String {
        return audioDeviceManager.getDeviceTypeName(device).lowercase().replace(" ", "_")
    }

    var profile: Int
        get() = getDeviceProfile(currentDevice)
        set(value) {
            dlog(TAG, "setProfile: $value")
            setDeviceProfile(currentDevice, value)
        }

    fun getDeviceProfile(device: AudioDeviceInfo?): Int {
        val prefs = context.getSharedPreferences("device_${getDevicePrefsKey(device)}", Context.MODE_PRIVATE)
        val globalProfile = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DolbyConstants.PREF_PROFILE, "0")!!.toInt()
        val deviceProfile = prefs.getInt(DolbyConstants.PREF_DEVICE_PROFILE, globalProfile)
        dlog(TAG, "getDeviceProfile(${audioDeviceManager.getDeviceTypeName(device)}): $deviceProfile")
        return deviceProfile
    }

    fun setDeviceProfile(device: AudioDeviceInfo?, profile: Int) {
        dlog(TAG, "setDeviceProfile(${audioDeviceManager.getDeviceTypeName(device)}, $profile)")
        val prefs = context.getSharedPreferences("device_${getDevicePrefsKey(device)}", Context.MODE_PRIVATE)
        prefs.edit().putInt(DolbyConstants.PREF_DEVICE_PROFILE, profile).apply()

        // Update the effect if this is the current device
        if (device == currentDevice) {
            updateDolbyEffect()
        }
    }

    private fun updateDolbyEffect() {
        val enabled = getDeviceEnabled(currentDevice)
        val profile = getDeviceProfile(currentDevice)

        dlog(TAG, "updateDolbyEffect: enabled=$enabled, profile=$profile, device=${audioDeviceManager.getDeviceTypeName(currentDevice)}")

        checkEffect()
        dolbyEffect.dsOn = enabled
        dolbyEffect.profile = profile
        registerCallbacks = enabled

        if (enabled) {
            setCurrentProfile()
        }
    }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        // Initialize current device
        currentDevice = audioDeviceManager.getCurrentDevice()
        dlog(TAG, "Initial device: ${audioDeviceManager.getDeviceTypeName(currentDevice)}")

        // Restore our main settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val globalEnabled = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)

        context.resources.getStringArray(R.array.dolby_profile_values)
            .map { it.toInt() }
            .forEach { profile ->
                // Reset dolby first to prevent it from loading bad settings
                dolbyEffect.resetProfileSpecificSettings(profile)
                // Now restore our profile-specific settings
                restoreSettings(profile)
            }

        // Initialize device-specific settings if they don't exist
        initializeDeviceDefaults()

        // Finally restore the current profile for the current device
        setCurrentProfile()
    }

    private fun initializeDeviceDefaults() {
        // Check if this is a first-time setup or upgrade
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val migrationDone = defaultPrefs.getBoolean(DolbyConstants.PREF_MIGRATION_DONE, false)
        val currentVersion = defaultPrefs.getInt("dolby_prefs_version", 1)

        if (!migrationDone || currentVersion < DolbyConstants.CURRENT_VERSION) {
            dlog(TAG, "Migration needed - current version: $currentVersion, target: ${DolbyConstants.CURRENT_VERSION}")

            // Clear any existing device-specific preferences to ensure clean state
            clearDeviceSpecificPreferences()

            // Set speaker as disabled by default (override any previous state)
            val speakerPrefs = context.getSharedPreferences("device_speaker", Context.MODE_PRIVATE)
            speakerPrefs.edit().putBoolean(DolbyConstants.PREF_DEVICE_ENABLE, false).apply()

            // Mark migration as complete and update version
            defaultPrefs.edit()
                .putBoolean(DolbyConstants.PREF_MIGRATION_DONE, true)
                .putInt("dolby_prefs_version", DolbyConstants.CURRENT_VERSION)
                .apply()

            dlog(TAG, "Device-specific preferences migrated - speaker disabled by default")
        } else {
            // Normal initialization - only set defaults if preferences don't exist
            val speakerPrefs = context.getSharedPreferences("device_speaker", Context.MODE_PRIVATE)
            if (!speakerPrefs.contains(DolbyConstants.PREF_DEVICE_ENABLE)) {
                speakerPrefs.edit().putBoolean(DolbyConstants.PREF_DEVICE_ENABLE, false).apply()
                dlog(TAG, "Initialized speaker device with disabled state")
            }
        }
    }

    private fun clearDeviceSpecificPreferences() {
        dlog(TAG, "Clearing existing device-specific preferences for clean upgrade")

        // List of device types to clear
        val deviceTypes = listOf(
            "speaker", "wired_headphones", "wired_headset", "bt_a2dp", "bt_sco",
            "usb_headset", "usb_device", "hdmi", "line_analog", "line_digital"
        )

        deviceTypes.forEach { deviceType ->
            try {
                val prefs = context.getSharedPreferences("device_$deviceType", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                dlog(TAG, "Cleared preferences for device: $deviceType")
            } catch (e: Exception) {
                dlog(TAG, "Failed to clear preferences for device $deviceType: ${e.message}")
            }
        }
    }

    private fun restoreSettings(profile: Int) {
        dlog(TAG, "restoreSettings(profile=$profile)")
        val prefs = context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
        setPreset(
            prefs.getString(DolbyConstants.PREF_PRESET, getPreset(profile))!!,
            profile
        )
        setIeqPreset(
            prefs.getString(
                DolbyConstants.PREF_IEQ,
                getIeqPreset(profile).toString()
            )!!.toInt(),
            profile
        )
        setHeadphoneVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, getHeadphoneVirtEnabled(profile)),
            profile
        )
        setSpeakerVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, getSpeakerVirtEnabled(profile)),
            profile
        )
        setStereoWideningAmount(
            prefs.getString(
                DolbyConstants.PREF_STEREO,
                getStereoWideningAmount(profile).toString()
            )!!.toInt(),
            profile
        )
        setDialogueEnhancerAmount(
            prefs.getString(
                DolbyConstants.PREF_DIALOGUE,
                getDialogueEnhancerAmount(profile).toString()
            )!!.toInt(),
            profile
        )
        setBassEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_BASS, getBassEnhancerEnabled(profile)),
            profile
        )
        setVolumeLevelerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_VOLUME, getVolumeLevelerEnabled(profile)),
            profile
        )
    }

    private fun checkEffect() {
        if (!dolbyEffect.hasControl()) {
            Log.w(TAG, "lost control, recreating effect")
            dolbyEffect.release()
            dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        }
    }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        updateDolbyEffect()
    }

    fun checkForDeviceChange() {
        audioDeviceManager.checkForDeviceChange()
    }

    fun getCurrentDevice(): AudioDeviceInfo? = currentDevice

    fun getDeviceTypeName(device: AudioDeviceInfo?): String {
        return audioDeviceManager.getDeviceTypeName(device)
    }

    fun getProfileName(): String? {
        val profile = dolbyEffect.profile.toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null else context.resources.getStringArray(
            R.array.dolby_profile_entries
        )[profileIndex]
    }

    fun resetProfileSpecificSettings() {
        dlog(TAG, "resetProfileSpecificSettings")
        checkEffect()
        dolbyEffect.resetProfileSpecificSettings()
        context.deleteSharedPreferences("profile_$profile")
    }

    fun resetAllDeviceSpecificSettings() {
        dlog(TAG, "resetAllDeviceSpecificSettings")
        clearDeviceSpecificPreferences()
        initializeDeviceDefaults()
        setCurrentProfile()
    }

    fun getPreset(profile: Int = this.profile): String {
        val gains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
        return gains.joinToString(separator = ",").also {
            dlog(TAG, "getPreset: $it")
        }
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        checkEffect()
        val gains = value.split(",")
            .map { it.toInt() }
            .toIntArray()
        dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
    }

    fun getPresetName(): String {
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val presetIndex = presets.indexOf(getPreset())
        return if (presetIndex == -1) {
            "Custom"
        } else {
            context.resources.getStringArray(
                R.array.dolby_preset_entries
            )[presetIndex]
        }
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile).also {
            dlog(TAG, "getHeadphoneVirtEnabled: $it")
        }

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile)
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile).also {
            dlog(TAG, "getSpeakerVirtEnabled: $it")
        }

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, profile)
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile).also {
            dlog(TAG, "getBassEnhancerEnabled: $it")
        }

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile)
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile).also {
            dlog(TAG, "getVolumeLevelerEnabled: $it")
        }

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile)
    }

    fun getStereoWideningAmount(profile: Int = this.profile) =
        dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile).also {
            dlog(TAG, "getStereoWideningAmount: $it")
        }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setStereoWideningAmount: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, value, profile)
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int {
        val enabled = dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        val amount = if (enabled) {
            dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        } else 0
        dlog(TAG, "getDialogueEnhancerAmount: enabled=$enabled amount=$amount")
        return amount
    }

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerAmount: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, (value > 0), profile)
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
    }

    fun getIeqPreset(profile: Int = this.profile) =
        dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile).also {
            dlog(TAG, "getIeqPreset: $it")
        }

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, value, profile)
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100

        @Volatile
        private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: DolbyController(context).also { instance = it }
            }
    }
}
