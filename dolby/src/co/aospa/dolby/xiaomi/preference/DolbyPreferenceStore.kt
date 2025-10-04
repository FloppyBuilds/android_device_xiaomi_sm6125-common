/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.preference

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import co.aospa.dolby.xiaomi.DolbyConstants
import co.aospa.dolby.xiaomi.device.AudioDeviceManager

class DolbyPreferenceStore(
    private val context: Context
) : PreferenceDataStore() {

    private val defaultSharedPrefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    private lateinit var profileSharedPrefs: SharedPreferences
    private var currentDevice: AudioDeviceInfo? = null

    var profile = 0
        set(value) {
            field = value
            profileSharedPrefs = context.getSharedPreferences(
                "profile_$value",
                Context.MODE_PRIVATE
            )
        }

    fun setCurrentDevice(device: AudioDeviceInfo?) {
        currentDevice = device
    }

    private fun getDevicePrefsKey(device: AudioDeviceInfo?): String {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
            AudioDeviceInfo.TYPE_HDMI -> "hdmi"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
            else -> "device_${device?.type ?: 0}"
        }
    }

    private fun getDeviceSpecificSharedPrefs(device: AudioDeviceInfo?): SharedPreferences {
        val deviceKey = getDevicePrefsKey(device)
        return context.getSharedPreferences("device_$deviceKey", Context.MODE_PRIVATE)
    }

    private fun getSharedPreferences(key: String) =
        if (DolbyConstants.PROFILE_SPECIFIC_PREFS.contains(key)) {
            profileSharedPrefs
        } else if (DolbyConstants.DEVICE_SPECIFIC_PREFS.contains(key)) {
            getDeviceSpecificSharedPrefs(currentDevice)
        } else {
            defaultSharedPrefs
        }

    override fun putBoolean(key: String, value: Boolean) =
        getSharedPreferences(key).edit()
            .putBoolean(key, value)
            .apply()

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val prefs = getSharedPreferences(key)

        // Handle device-specific enable preference with speaker default
        if (key == DolbyConstants.PREF_DEVICE_ENABLE) {
            val globalEnabled = defaultSharedPrefs.getBoolean(DolbyConstants.PREF_ENABLE, true)
            val defaultForDevice = if (AudioDeviceManager.isSpeakerDevice(currentDevice)) {
                false
            } else {
                globalEnabled
            }
            return prefs.getBoolean(key, defaultForDevice)
        }

        return prefs.getBoolean(key, defValue)
    }

    override fun putInt(key: String, value: Int) =
        getSharedPreferences(key).edit()
            .putInt(key, value)
            .apply()

    override fun getInt(key: String, defValue: Int) =
        getSharedPreferences(key).getInt(key, defValue)

    override fun putString(key: String, value: String?) =
        getSharedPreferences(key).edit()
            .putString(key, value)
            .apply()

    override fun getString(key: String, defValue: String?) =
        getSharedPreferences(key).getString(key, defValue)
}
