package com.transacao.common.remote;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Escolhe, entre alguns formatos PCM candidatos, o primeiro suportado por
 * um mixer de captura ou de reproducao especifico.
 */
public final class AudioFormats {

    private static final AudioFormat[] CANDIDATES = {
            new AudioFormat(44100f, 16, 2, true, false),
            new AudioFormat(44100f, 16, 1, true, false),
            new AudioFormat(22050f, 16, 1, true, false),
            new AudioFormat(16000f, 16, 1, true, false),
    };

    private AudioFormats() {
    }

    public static AudioFormat pickForCapture(Mixer.Info mixerInfo) {
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        for (AudioFormat f : CANDIDATES) {
            if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) {
                return f;
            }
        }
        return CANDIDATES[CANDIDATES.length - 1];
    }

    public static AudioFormat pickForPlayback(Mixer.Info mixerInfo) {
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        for (AudioFormat f : CANDIDATES) {
            if (mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, f))) {
                return f;
            }
        }
        return CANDIDATES[CANDIDATES.length - 1];
    }
}
