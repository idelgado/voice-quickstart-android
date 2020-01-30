package com.twilio.voice.quickstart.audio;

public class AudioDevice {
    private final Type type;
    private final String name;

    public AudioDevice(Type type, String name) {
        this.type = type;
        this.name = name;
    }

    public final String getName() {
        return name;

    }

    public final Type getType() {
        return type;
    }

    /**
     * Supported audio device types
     */
    public enum Type {SPEAKERPHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE }
}
