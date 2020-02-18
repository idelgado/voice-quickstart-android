package com.twilio.voice.quickstart.audio.helper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public interface AudioDeviceChangeListener {
    void onAvailableAudioDevices(@NonNull List<AudioDevice> audioDevices,
                                 @Nullable AudioDevice selectedAudioDevice);
}
