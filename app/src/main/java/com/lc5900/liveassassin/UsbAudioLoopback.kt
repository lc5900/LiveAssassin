package com.lc5900.liveassassin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class UsbAudioLoopback(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var ioThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) {
            return true
        }
        val minRecordSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val minTrackSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val bufferSize = maxOf(minRecordSize, minTrackSize) * 2
        if (bufferSize <= 0) {
            return false
        }

        val inFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL_IN)
            .build()
        val outFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL_OUT)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(inFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(outFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED ||
            audioTrack?.state != AudioTrack.STATE_INITIALIZED
        ) {
            stop()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val usbInput = findDevice(
                AudioManager.GET_DEVICES_INPUTS,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
            if (usbInput != null) {
                audioRecord?.preferredDevice = usbInput
            }
            val output = pickBestOutputDevice()
            if (output != null) {
                audioTrack?.preferredDevice = output
                Log.i(TAG, "Audio output routed to type=${output.type}, id=${output.id}")
            } else {
                Log.w(TAG, "No preferred output device found, using system default route")
            }
        }

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        running.set(true)
        ioThread = Thread({
            val buffer = ByteArray(bufferSize / 2)
            try {
                audioRecord?.startRecording()
                audioTrack?.play()
                while (running.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioTrack?.write(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {
            }
        }, "usb-audio-loopback")
        ioThread?.start()
        return true
    }

    fun stop() {
        running.set(false)
        if (ioThread != null) {
            try {
                ioThread?.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ioThread = null
        }
        if (audioRecord != null) {
            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            audioRecord?.release()
            audioRecord = null
        }
        if (audioTrack != null) {
            try {
                audioTrack?.stop()
            } catch (_: Exception) {
            }
            audioTrack?.release()
            audioTrack = null
        }
    }

    private fun findDevice(flag: Int, vararg types: Int): AudioDeviceInfo? {
        val devices = audioManager.getDevices(flag)
        for (device in devices) {
            for (type in types) {
                if (device.type == type) {
                    return device
                }
            }
        }
        return null
    }

    private fun pickBestOutputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var best = findFirstByTypes(
            outputs,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
        if (best != null) {
            return best
        }
        best = findFirstByTypes(
            outputs,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        if (best != null) {
            return best
        }
        return findFirstByTypes(
            outputs,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        )
    }

    private fun findFirstByTypes(devices: Array<AudioDeviceInfo>, vararg types: Int): AudioDeviceInfo? {
        for (type in types) {
            for (device in devices) {
                if (device.type == type) {
                    return device
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "UsbAudioLoopback"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

