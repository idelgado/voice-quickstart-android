package com.twilio.voice.quickstart.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public interface AudioDeviceChangeListener {
    void onAvailableAudioDevices(@NonNull List<AudioDevice> audioDevices,
                                 @Nullable AudioDevice selectedAudioDevice);
}
