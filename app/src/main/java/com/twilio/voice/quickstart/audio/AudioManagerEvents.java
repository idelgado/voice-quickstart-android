package com.twilio.voice.quickstart.audio;

import com.twilio.voice.quickstart.audio.helper.AudioDevice;

import java.util.Set;

public interface AudioManagerEvents {
    void onAudioDevicesChanged(
            AudioDevice selectedAudioDevice, Set<AudioDevice> availableAudioDevices);
}
