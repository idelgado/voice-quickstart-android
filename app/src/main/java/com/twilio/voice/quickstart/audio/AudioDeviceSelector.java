package com.twilio.voice.quickstart.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.twilio.voice.quickstart.audio.AppRTCBluetoothManager.State.HEADSET_AVAILABLE;
import static com.twilio.voice.quickstart.audio.AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE;
import static com.twilio.voice.quickstart.audio.AppRTCBluetoothManager.State.SCO_CONNECTED;
import static com.twilio.voice.quickstart.audio.AppRTCBluetoothManager.State.SCO_CONNECTING;
import static com.twilio.voice.quickstart.audio.AppRTCBluetoothManager.State.SCO_DISCONNECTING;

public class AudioDeviceSelector implements AudioDeviceChange {

    private static final String TAG = "AudioDeviceSelector";
    private final Context context;
    private final android.media.AudioManager audioManager;
    private final AppRTCAudioManager.AudioManagerEvents audioManagerEvents;
    private final WiredHeadsetReceiver wiredHeadsetReceiver;
    private final boolean hasEarpiece;
    private final boolean hasSpeakerphone;
    private boolean hasWiredHeadset;
    private int savedAudioMode;
    private boolean savedIsMicrophoneMuted;
    private boolean savedSpeakerphoneEnabled;
    private List<AudioDevice> audioDevices = new ArrayList<>();
    private AudioDevice selectedAudioDevice;
    private boolean running = false;
    private AudioDevice EARPIECE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.EARPIECE, "Earpiece");
    private AudioDevice SPEAKERPHONE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.EARPIECE, "Speakerphone");
    private AudioDevice WIRED_HEADSET_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.EARPIECE, "Wired Headset");
    private final AppRTCBluetoothManager bluetoothManager;

    public static AudioDeviceSelector create(Context context, AppRTCAudioManager.AudioManagerEvents audioManagerEvents) {
        return new AudioDeviceSelector(context, audioManagerEvents);
    }

    private AudioDeviceSelector(Context context, AppRTCAudioManager.AudioManagerEvents audioManagerEvents) {
        ThreadUtils.checkIsOnMainThread();
        this.context = context.getApplicationContext();
        this.audioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioManagerEvents = audioManagerEvents;
        this.wiredHeadsetReceiver = new WiredHeadsetReceiver();
        this.bluetoothManager = AppRTCBluetoothManager.create(context, this);
        hasEarpiece = hasEarpiece();
        hasSpeakerphone = hasSpeakerphone();
    }

    public void start() {
        ThreadUtils.checkIsOnMainThread();
        running = true;
        // Store audio state
        savedAudioMode = audioManager.getMode();
        savedIsMicrophoneMuted = audioManager.isMicrophoneMute();
        savedSpeakerphoneEnabled = audioManager.isSpeakerphoneOn();

        context.registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        bluetoothManager.start();
    }

    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        running = false;
        // Restore stored audio state
        audioManager.setMode(savedAudioMode);
        setMicrophoneMute(savedIsMicrophoneMuted);
        setSpeakerphone(savedSpeakerphoneEnabled);

        bluetoothManager.stop();

        context.unregisterReceiver(wiredHeadsetReceiver);
    }

    public void setAudioDevice(@NonNull AudioDevice audioDevice) {
        ThreadUtils.checkIsOnMainThread();
        if (running) {
            selectAudioDevice(audioDevice);
        }
    }

    public @Nullable AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return (selectedAudioDevice == null) ?
                null : new AudioDevice(selectedAudioDevice.getType(), selectedAudioDevice.getName());
    }

    public @NonNull List<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableList(new ArrayList<>(audioDevices));
    }

    @Override
    public void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice();
        }
        selectAudioDevice(null);
    }

    private void selectAudioDevice(@Nullable AudioDevice audioDevice) {
        List<AudioDevice> availableAudioDevices = getAvailablePreferredAudioDevices();

        if (!audioDevices.equals(availableAudioDevices)) {
            audioDevices = availableAudioDevices;
        }

        if (audioDevice != null) {
            if (audioDevices.contains(audioDevice)) {
                applySelectedAudioDevice(audioDevice);
            } else {
                Log.e(TAG, audioDevice.getName() + " no longer available");
            }
        } else if (audioDevices.size() != 0) {
            applySelectedAudioDevice(audioDevices.get(0));
        } else {
            selectedAudioDevice = null;
        }
    }

    private void applySelectedAudioDevice(@NonNull AudioDevice audioDevice) {
        if (!audioDevice.equals(selectedAudioDevice)) {
            selectedAudioDevice = audioDevice;
            Log.d(TAG, selectedAudioDevice.getName() + " selected");
            apply(selectedAudioDevice.getType());
        }
    }

    private void apply(AudioDevice.Type type) {
        switch (type) {
            case BLUETOOTH:
                setSpeakerphone(false);
                break;
            case EARPIECE:
                setSpeakerphone(false);
                break;
            case SPEAKERPHONE:
                setSpeakerphone(true);
                break;
            case WIRED_HEADSET:
                setSpeakerphone(false);
                break;
        }
    }

    private @NonNull List<AudioDevice> getAvailablePreferredAudioDevices() {
        List<AudioDevice> availableAudioDevices = new ArrayList<>();

        // Add devices using this preferred order: bluetooth, wired headset, earpiece, speakerphone
        AudioDevice bluetoothAudioDevice = getConnectedBluetoothAudioDevice();
        if (bluetoothAudioDevice != null) {
            availableAudioDevices.add(bluetoothAudioDevice);
        }
        if (hasWiredHeadset) {
            availableAudioDevices.add(WIRED_HEADSET_AUDIO_DEVICE);
        }
        if (hasEarpiece) {
            availableAudioDevices.add(EARPIECE_AUDIO_DEVICE);
        }
        if (hasSpeakerphone) {
            availableAudioDevices.add(SPEAKERPHONE_AUDIO_DEVICE);
        }

        return availableAudioDevices;
    }

    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0;
        private static final int STATE_PLUGGED = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", STATE_UNPLUGGED);
            if (state == STATE_PLUGGED) {
                hasWiredHeadset = true;
                Log.d(TAG, "Wired Headset available");
                selectAudioDevice(WIRED_HEADSET_AUDIO_DEVICE);
            } else {
                hasWiredHeadset = false;
                Log.d(TAG, "Wired Headset not available");
                selectAudioDevice(null);
            }
        }
    }

    private AudioDevice getConnectedBluetoothAudioDevice() {
        if (bluetoothManager.getState() == SCO_CONNECTED
                || bluetoothManager.getState() == SCO_CONNECTING
                || bluetoothManager.getState() == HEADSET_AVAILABLE) {
            Log.d(TAG, "Bluetooth available");
            return new AudioDevice(AudioDevice.Type.BLUETOOTH, "Bluetooth");
        } else {
            Log.d(TAG, "Bluetooth not available");
            return null;
        }
    }

    private boolean hasEarpiece() {
        Log.d(TAG, "Earpiece available");
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private boolean hasSpeakerphone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) {
            AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "Speakerphone available");
                    return true;
                }
            }
            return false;
        } else {
           Log.d(TAG, "Speakerphone available");
           return true;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean hasWiredHeadset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo device : devices) {
                final int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    Log.d(TAG, "Wired Headset available");
                    return true;
                }
            }
            return false;
        } else {
            boolean wiredHeadset = audioManager.isWiredHeadsetOn();
            if (wiredHeadset) {
                Log.d(TAG, "Wired Headset available");
            }
            return wiredHeadset;
        }
    }

    private void setSpeakerphone(boolean on) {
        if (audioManager.isSpeakerphoneOn() && !on) {
            audioManager.setSpeakerphoneOn(false);
        }
    }

    private void setMicrophoneMute(boolean on) {
        if (audioManager.isMicrophoneMute() && !on) {
            audioManager.setMicrophoneMute(false);
        }
    }

}
