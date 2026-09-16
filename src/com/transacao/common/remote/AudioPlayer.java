package com.transacao.common.remote;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Recebe blocos de audio PCM vindos do outro lado e toca no dispositivo de
 * saida escolhido pelo usuario (caixa de som, ou um dispositivo de audio
 * virtual como o VB-Audio Virtual Cable, para expor como microfone a
 * outros programas).
 */
public class AudioPlayer {

    private SourceDataLine line;

    public synchronized void open(Mixer.Info mixerInfo, AudioFormat format) throws LineUnavailableException {
        close();
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        line = (SourceDataLine) mixer.getLine(info);
        line.open(format);
        line.start();
    }

    public synchronized void play(byte[] pcmData) {
        if (line != null) {
            line.write(pcmData, 0, pcmData.length);
        }
    }

    public synchronized void close() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }
    }
}
