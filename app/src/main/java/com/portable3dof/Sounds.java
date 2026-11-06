package com.portable3dof;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Short synthesised menu sounds (the CRTXR recipe): a soft 440 Hz tick for moving, a 660 Hz blip for confirming.
 * Callers stay silent when a press changes nothing (ends of lists), and never during playback.
 */
public final class Sounds {
    private Sounds() { }

    private static final int RATE = 22050;
    private static AudioTrack move, confirm;

    public static void move() { play(true); }

    public static void confirm() { play(false); }

    private static synchronized void play(boolean isMove) {
        try {
            if (move == null) {
                move = track(440, 30, 0.12f);
                confirm = track(660, 60, 0.14f);
            }
            AudioTrack t = isMove ? move : confirm;
            if (t == null || Settings.soundLevel <= 0) return;
            t.setVolume(Settings.soundLevel == 1 ? 0.35f : 1f);
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (RuntimeException ignored) {
            // audio is a nicety; never let it break input
        }
    }

    private static AudioTrack track(float hz, int ms, float amp) {
        int n = RATE * ms / 1000;
        short[] pcm = new short[n];
        int fade = RATE * 4 / 1000;                 // 4 ms fade in/out: no clicks
        for (int i = 0; i < n; i++) {
            float env = Math.min(1f, Math.min(i, n - 1 - i) / (float) fade);
            pcm[i] = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * amp * env * Short.MAX_VALUE);
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build();
        t.write(pcm, 0, n);
        return t;
    }
}
