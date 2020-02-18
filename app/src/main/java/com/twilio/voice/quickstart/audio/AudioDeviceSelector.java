package com.twilio.voice.quickstart.audio;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AudioDeviceSelector {
    private static final String TAG = "AudioDeviceSelector";

    private final AudioDeviceChangeListener audioDeviceChangeListener;
    private final Context context;
    private final AudioManager audioManager;
    private final boolean hasEarpiece;
    private final boolean hasSpeakerphone;
    private final BluetoothController bluetoothController;

    private @Nullable AudioDevice selectedDevice;
    private @Nullable AudioDevice userSelectedDevice;
    private @NonNull State state;
    private @NonNull WiredHeadsetReceiver wiredHeadsetReceiver;
    private boolean wiredHeadseatAvaiable;
    private ArrayList<AudioDevice> availableAudioDevices = new ArrayList<>();

    // Saved Audio Settings
    private int savedAudioMode;
    private boolean savedIsMicrophoneMuted;
    private boolean savedSpeakerphoneEnabled;

    private enum State {
        STARTED,
        ACTIVE,
        STOPPED
    }

    private AudioDevice EARPIECE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.EARPIECE, "Earpiece");
    private AudioDevice SPEAKERPHONE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.SPEAKERPHONE, "Speakerphone");
    private AudioDevice WIRED_HEADSET_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.WIRED_HEADSET, "Wired Headset");
    private @Nullable AudioDevice bluetoothAudioDevice;

    public AudioDeviceSelector(@NonNull Context context,
                               @NonNull AudioDeviceChangeListener audioDeviceChangeListener) {
        this.audioDeviceChangeListener = audioDeviceChangeListener;
        ThreadUtils.checkIsOnMainThread();
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.wiredHeadsetReceiver = new WiredHeadsetReceiver();
        this.bluetoothController = new BluetoothController(context, new BluetoothController.Listener() {
            @Override
            public void onBluetoothConnected(@NonNull BluetoothDevice bluetoothDevice) {
                bluetoothAudioDevice = new AudioDevice(AudioDevice.Type.BLUETOOTH, bluetoothDevice.getName());
                if (state == State.ACTIVE) {
                    userSelectedDevice = bluetoothAudioDevice;
                }
                enumerateDevices();
            }

            @Override
            public void onBluetoothDisconnected() {
                bluetoothAudioDevice = null;
                enumerateDevices();
            }
        });
        hasEarpiece = hasEarpiece();
        hasSpeakerphone = hasSpeakerphone();
        state = State.STOPPED;
    }

    /**
     * Start listening for audio device changes
     */
    public void start() {
        ThreadUtils.checkIsOnMainThread();
        switch (state) {
            case STOPPED:
                context.registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
                bluetoothController.start();
                state = State.STARTED;
                break;
            case STARTED:
            case ACTIVE:
                throw new IllegalStateException();
        }
    }

    /**
     * Stop listening for audio device changes
     */
    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        switch (state) {
            case STARTED:
                context.unregisterReceiver(wiredHeadsetReceiver);
                bluetoothController.stop();
                state = State.STOPPED;
                break;
            case STOPPED:
            case ACTIVE:
                throw new IllegalStateException();
        }
    }

    /**
     * Request focus for the selected audio device
     */
    public void activate() {
        ThreadUtils.checkIsOnMainThread();

        switch (state) {
            case STARTED:
                savedAudioMode = audioManager.getMode();
                savedIsMicrophoneMuted = audioManager.isMicrophoneMute();
                savedSpeakerphoneEnabled = audioManager.isSpeakerphoneOn();

                // Always set mute to false for WebRTC
                mute(false);

                setAudioFocus();

                if (selectedDevice != null) {
                    activate(selectedDevice);
                }
                state = State.ACTIVE;
                break;
            case ACTIVE:
                // Activate the newly selected device
                if (selectedDevice != null) {
                    activate(selectedDevice);
                }
                break;
            case STOPPED:
                throw new IllegalStateException();
        }

    }

    private void activate(@NonNull AudioDevice audioDevice) {
        switch (audioDevice.type) {
            case BLUETOOTH:
                enableSpeakerphone(false);
                bluetoothController.activate();
                break;
            case EARPIECE:
            case WIRED_HEADSET:
                enableSpeakerphone(false);
                bluetoothController.deactivate();
                break;
            case SPEAKERPHONE:
                enableSpeakerphone(true);
                bluetoothController.deactivate();
                break;
        }
    }

    /**
     * Restore focus away from the selected audio device
     */
    public void deactivate() {
        ThreadUtils.checkIsOnMainThread();

        bluetoothController.deactivate();

        // Restore stored audio state
        audioManager.setMode(savedAudioMode);
        mute(savedIsMicrophoneMuted);
        enableSpeakerphone(savedSpeakerphoneEnabled);

        audioManager.abandonAudioFocus(null);
    }

    /**
     * Select the desired {@link AudioDevice}. If the provided {@link AudioDevice} is not available
     * no changes are made. If the provided {@link AudioDevice} is null an {@link AudioDevice} is
     * chosen based on the following preference: Bluetooth, Wired Headset, Microphone, Speakerphone
     *
     * @param audioDevice The {@link AudioDevice} to use
     */
    public void selectDevice(@Nullable AudioDevice audioDevice) {
        ThreadUtils.checkIsOnMainThread();
        userSelectedDevice = audioDevice;
        enumerateDevices();
    }

    /**
     * @return The selected {@link AudioDevice}
     */
    public @Nullable AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return selectedDevice != null ?
                new AudioDevice(selectedDevice.type, selectedDevice.name) : null;
    }

    public @NonNull List<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableList(new ArrayList<>(availableAudioDevices));
    }

    private void setAudioFocus() {
        // Request audio focus before making any device switch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(i -> {
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(
                    focusChange -> { },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
        /*
         * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
         * required to be in this mode when playout and/or recording starts for
         * best possible VoIP performance. Some devices have difficulties with speaker mode
         * if this is not set.
         */
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }

    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0;
        private static final int STATE_PLUGGED = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            ThreadUtils.checkIsOnMainThread();
            int state = intent.getIntExtra("state", STATE_UNPLUGGED);
            if (state == STATE_PLUGGED) {
                wiredHeadseatAvaiable = true;
                Log.d(TAG, "Wired Headset available");
                if (AudioDeviceSelector.this.state == State.ACTIVE) {
                    userSelectedDevice = WIRED_HEADSET_AUDIO_DEVICE;
                }
                enumerateDevices();
            } else {
                wiredHeadseatAvaiable = false;
                enumerateDevices();
            }
        }
    }

    private void enumerateDevices() {
        availableAudioDevices.clear();
        if (bluetoothAudioDevice != null) {
            availableAudioDevices.add(bluetoothAudioDevice);
        }
        if (wiredHeadseatAvaiable) {
            availableAudioDevices.add(WIRED_HEADSET_AUDIO_DEVICE);
        }
        if (hasEarpiece && !wiredHeadseatAvaiable) {
            availableAudioDevices.add(EARPIECE_AUDIO_DEVICE);
        }
        if (hasSpeakerphone) {
            availableAudioDevices.add(SPEAKERPHONE_AUDIO_DEVICE);
        }

        // Check whether the user selected device is still present
        if (!userSelectedDevicePresent(availableAudioDevices)) {
           userSelectedDevice = null;
        }

        // Select the audio device
        if (userSelectedDevice != null && userSelectedDevicePresent(availableAudioDevices)) {
            selectedDevice = userSelectedDevice;
        } else if (availableAudioDevices.size() > 0) {
            selectedDevice = availableAudioDevices.get(0);
        } else {
            selectedDevice = null;
        }

        // Activate the device if in the active state
        if (state == State.ACTIVE) {
            activate();
        }

        if (selectedDevice != null) {
            audioDeviceChangeListener.onAvailableAudioDevices(availableAudioDevices,
                    new AudioDevice(selectedDevice.type, selectedDevice.name));
        } else {
            audioDeviceChangeListener.onAvailableAudioDevices(availableAudioDevices,
                    selectedDevice);
        }
    }

    private boolean userSelectedDevicePresent(List<AudioDevice> audioDevices) {
        for (AudioDevice audioDevice: audioDevices) {
            if (audioDevice.equals(userSelectedDevice)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEarpiece() {
        boolean hasEarpiece = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        if (hasEarpiece) {
            Log.d(TAG, "Earpiece available");
        }
        return hasEarpiece;
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

    private void enableSpeakerphone(boolean enable) {
        audioManager.setSpeakerphoneOn(enable);
    }

    private void mute(boolean mute) {
        audioManager.setMicrophoneMute(mute);
    }

}
