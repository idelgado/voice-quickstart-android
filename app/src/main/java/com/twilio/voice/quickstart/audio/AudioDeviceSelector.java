package com.twilio.voice.quickstart.audio;

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

import com.twilio.voice.quickstart.audio.helper.AudioDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AudioDeviceSelector implements BluetoothController.Listener {

    private static final String TAG = "AudioDeviceSelector";
    private final Context context;
    private final android.media.AudioManager audioManager;
    private final AudioManagerEvents audioManagerEvents;
    private final WiredHeadsetReceiver wiredHeadsetReceiver;
    private final boolean hasEarpiece;
    private final boolean hasSpeakerphone;
    private boolean hasWiredHeadset;
    private int savedAudioMode;
    private boolean savedIsMicrophoneMuted;
    private boolean savedSpeakerphoneEnabled;
    private List<AudioDevice> audioDevices = new ArrayList<>();
    private AudioDevice selectedAudioDevice;
    private boolean userSelected = false;
    private boolean running = false;
    private AudioDevice EARPIECE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.EARPIECE, "Earpiece");
    private AudioDevice SPEAKERPHONE_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.SPEAKERPHONE, "Speakerphone");
    private AudioDevice WIRED_HEADSET_AUDIO_DEVICE = new AudioDevice(AudioDevice.Type.WIRED_HEADSET, "Wired Headset");
    private AudioDevice bluetoothDevice;
    private final BluetoothController bluetoothController;

    public static AudioDeviceSelector create(Context context, AudioManagerEvents audioManagerEvents) {
        return new AudioDeviceSelector(context, audioManagerEvents);
    }

    private AudioDeviceSelector(Context context, AudioManagerEvents audioManagerEvents) {
        ThreadUtils.checkIsOnMainThread();
        this.context = context.getApplicationContext();
        this.audioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioManagerEvents = audioManagerEvents;
        this.wiredHeadsetReceiver = new WiredHeadsetReceiver();
        this.bluetoothController = new BluetoothController(context, this);
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

        selectAudioDevice(null);

        context.registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        bluetoothController.start();
    }

    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        running = false;
        // Restore stored audio state
        audioManager.setMode(savedAudioMode);
        audioManager.abandonAudioFocus(null);
        mute(savedIsMicrophoneMuted);
        enableSpeakerphone(savedSpeakerphoneEnabled);

        bluetoothController.stop();

        context.unregisterReceiver(wiredHeadsetReceiver);
    }

    @Override
    public void onHeadsetDisconnected() {
        bluetoothDevice = null;
    }

    @Override
    public void onHeadsetConnected() {
        bluetoothDevice = new AudioDevice(AudioDevice.Type.BLUETOOTH, "Bluetooth");
        selectAudioDevice(bluetoothDevice);
    }

    @Override
    public void onScoAudioDisconnected() {

    }

    @Override
    public void onScoAudioConnected() {

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
                null : new AudioDevice(selectedAudioDevice.type, selectedAudioDevice.name);
    }

    public @NonNull List<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableList(new ArrayList<>(audioDevices));
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
                Log.e(TAG, audioDevice.name + " no longer available");
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
            Log.d(TAG, selectedAudioDevice.name + " selected");
            apply(selectedAudioDevice.type);
        }
    }

    private void apply(AudioDevice.Type type) {
        switch (type) {
            case BLUETOOTH:
                enableSpeakerphone(false);
                if (!bluetoothController.isOnHeadsetSco()) {
                    bluetoothController.start();
                }
                break;
            case EARPIECE:
            case WIRED_HEADSET:
                enableSpeakerphone(false);
                if (bluetoothController.isOnHeadsetSco()) {
                    bluetoothController.stop();
                }
                break;
            case SPEAKERPHONE:
                enableSpeakerphone(true);
                if (bluetoothController.isOnHeadsetSco()) {
                    bluetoothController.stop();
                }
                break;
        }
    }

    private @NonNull List<AudioDevice> getAvailablePreferredAudioDevices() {
        List<AudioDevice> availableAudioDevices = new ArrayList<>();

        if (bluetoothDevice != null) {
            availableAudioDevices.add(bluetoothDevice);
        }
        if (hasWiredHeadset) {
            availableAudioDevices.add(WIRED_HEADSET_AUDIO_DEVICE);
        }
        if (hasEarpiece && !hasWiredHeadset) {
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

    private void enableSpeakerphone(boolean enable) {
        audioManager.setSpeakerphoneOn(enable);
    }

    private void mute(boolean mute) {
        audioManager.setMicrophoneMute(mute);
    }

}
