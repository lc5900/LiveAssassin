package com.lc5900.liveassassin;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class UsbAudioLoopback {
    private static final String TAG = "UsbAudioLoopback";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final AudioManager audioManager;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread ioThread;

    public UsbAudioLoopback(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean start() {
        if (running.get()) {
            return true;
        }
        int minRecordSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        int minTrackSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        int bufferSize = Math.max(minRecordSize, minTrackSize) * 2;
        if (bufferSize <= 0) {
            return false;
        }

        AudioFormat inFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_IN)
                .build();
        AudioFormat outFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build();

        audioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                .setAudioFormat(inFormat)
                .setBufferSizeInBytes(bufferSize)
                .build();
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(outFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED
                || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            stop();
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo usbInput = findDevice(AudioManager.GET_DEVICES_INPUTS,
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET);
            if (usbInput != null) {
                audioRecord.setPreferredDevice(usbInput);
            }
            AudioDeviceInfo output = pickBestOutputDevice();
            if (output != null) {
                audioTrack.setPreferredDevice(output);
                Log.i(TAG, "Audio output routed to type=" + output.getType() + ", id=" + output.getId());
            } else {
                Log.w(TAG, "No preferred output device found, using system default route");
            }
        }

        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);

        running.set(true);
        ioThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize / 2];
            try {
                audioRecord.startRecording();
                audioTrack.play();
                while (running.get()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "usb-audio-loopback");
        ioThread.start();
        return true;
    }

    public void stop() {
        running.set(false);
        if (ioThread != null) {
            try {
                ioThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ioThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception ignored) {
            }
            audioTrack.release();
            audioTrack = null;
        }
    }

    private AudioDeviceInfo findDevice(int flag, int... types) {
        AudioDeviceInfo[] devices = audioManager.getDevices(flag);
        for (AudioDeviceInfo device : devices) {
            for (int type : types) {
                if (device.getType() == type) {
                    return device;
                }
            }
        }
        return null;
    }

    private AudioDeviceInfo pickBestOutputDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        AudioDeviceInfo best = findFirstByTypes(outputs,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST
        );
        if (best != null) {
            return best;
        }

        best = findFirstByTypes(outputs,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET
        );
        if (best != null) {
            return best;
        }

        return findFirstByTypes(outputs,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        );
    }

    private AudioDeviceInfo findFirstByTypes(AudioDeviceInfo[] devices, int... types) {
        for (int type : types) {
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == type) {
                    return device;
                }
            }
        }
        return null;
    }
}
