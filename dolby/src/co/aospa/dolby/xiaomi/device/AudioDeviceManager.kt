/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.device

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import java.util.concurrent.CopyOnWriteArrayList

class AudioDeviceManager(
    private val context: Context,
    private val handler: Handler
) : AudioDeviceCallback() {

    interface AudioOutputChangedCallback {
        fun onAudioOutputChanged(currentDevice: AudioDeviceInfo?)
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val callbacks = CopyOnWriteArrayList<AudioOutputChangedCallback>()
    private var currentDevice: AudioDeviceInfo? = null
    private var isMonitoring = false

    private val periodicCheck = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkForDeviceChange()
                handler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
    }

    fun addCallback(callback: AudioOutputChangedCallback) {
        val wasEmpty = callbacks.isEmpty()
        callbacks.add(callback)

        if (wasEmpty) {
            audioManager.registerAudioDeviceCallback(this, handler)
            startPeriodicMonitoring()
            // Notify with current device immediately
            currentDevice = getCurrentDevice()
            callback.onAudioOutputChanged(currentDevice)
        }
    }

    fun removeCallback(callback: AudioOutputChangedCallback) {
        callbacks.remove(callback)

        if (callbacks.isEmpty()) {
            audioManager.unregisterAudioDeviceCallback(this)
            stopPeriodicMonitoring()
        }
    }

    private fun startPeriodicMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            dlog(TAG, "Starting periodic device monitoring")
            handler.post(periodicCheck)
        }
    }

    private fun stopPeriodicMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            dlog(TAG, "Stopping periodic device monitoring")
            handler.removeCallbacks(periodicCheck)
        }
    }

    private fun notifyCallbacks() {
        currentDevice = getCurrentDevice()
        dlog(TAG, "Notifying callbacks of device change: ${currentDevice?.type}")
        callbacks.forEach { it.onAudioOutputChanged(currentDevice) }
    }

    override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
        dlog(TAG, "Audio devices added: ${addedDevices.size}")
        notifyCallbacks()
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
        dlog(TAG, "Audio devices removed: ${removedDevices.size}")
        notifyCallbacks()
    }

    /**
     * Force a device change check - useful when manual output switching occurs
     */
    fun checkForDeviceChange() {
        val newDevice = getCurrentDevice()
        if (newDevice != currentDevice) {
            dlog(TAG, "Device change detected via manual check")
            notifyCallbacks()
        }
    }

    fun getCurrentDevice(): AudioDeviceInfo? {
        // Get the current music stream routing to determine which device is active
        val musicDevices = audioManager.getDevicesForStream(AudioManager.STREAM_MUSIC)
        val connectedDevices = getConnectedOutputs()

        // Find the device that matches the current music stream routing
        for (device in connectedDevices) {
            val deviceType = convertDeviceTypeToInternalDevice(device.type)
            if ((deviceType and musicDevices) != 0) {
                return device
            }
        }

        // If no device matches the routing mask, try to find the most likely candidate
        // Priority: Speaker > Wired Headphones > Bluetooth > Others
        var speaker: AudioDeviceInfo? = null
        var wiredHeadphones: AudioDeviceInfo? = null
        var bluetooth: AudioDeviceInfo? = null

        for (device in connectedDevices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> speaker = device
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> wiredHeadphones = device
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bluetooth = device
            }
        }

        // Return in priority order
        return speaker ?: wiredHeadphones ?: bluetooth ?: connectedDevices.firstOrNull()
    }

    fun getConnectedOutputs(): List<AudioDeviceInfo> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    fun getDeviceTypeName(device: AudioDeviceInfo?): String {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Out"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Out (Digital)"
            else -> "Unknown (${device?.type})"
        }
    }

    fun isSpeakerDevice(device: AudioDeviceInfo?): Boolean {
        return device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    fun isHeadphoneDevice(device: AudioDeviceInfo?): Boolean {
        return when (device?.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    private fun convertDeviceTypeToInternalDevice(deviceType: Int): Int {
        return when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioManager.DEVICE_OUT_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioManager.DEVICE_OUT_WIRED_HEADPHONE
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioManager.DEVICE_OUT_WIRED_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioManager.DEVICE_OUT_BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioManager.DEVICE_OUT_BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioManager.DEVICE_OUT_USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioManager.DEVICE_OUT_USB_DEVICE
            AudioDeviceInfo.TYPE_HDMI -> AudioManager.DEVICE_OUT_HDMI
            AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioManager.DEVICE_OUT_LINE
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioManager.DEVICE_OUT_DGTL_DOCK_HEADSET
            else -> 0
        }
    }

    companion object {
        private const val TAG = "AudioDeviceManager"
        
        fun isSpeakerDevice(device: AudioDeviceInfo?): Boolean {
            return device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }
}
