package com.transacao.common.remote;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.ArrayList;
import java.util.List;

/**
 * Lista os dispositivos de audio (mixers) disponiveis para captura
 * (microfone, linha de monitoramento) e reproducao (caixa de som,
 * dispositivo de audio virtual).
 */
public final class AudioDevices {

    private AudioDevices() {
    }

    public static List<Mixer.Info> listCaptureDevices() {
        List<Mixer.Info> result = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.getTargetLineInfo().length > 0) {
                result.add(info);
            }
        }
        return result;
    }

    public static List<Mixer.Info> listPlaybackDevices() {
        List<Mixer.Info> result = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.getSourceLineInfo().length > 0) {
                result.add(info);
            }
        }
        return result;
    }
}
