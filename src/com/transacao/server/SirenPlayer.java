package com.transacao.server;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.function.Consumer;

/**
 * Toca um som de sirene em loop, gerado na hora (sem arquivo de audio
 * externo): um tom cuja frequencia sobe e desce continuamente (estilo "wail"
 * de sirene de ambulancia), tocado na saida de audio padrao do sistema ate
 * stop() ser chamado.
 */
public class SirenPlayer {

    private static final float SAMPLE_RATE = 44100f;
    private static final double MIN_FREQ = 650;
    private static final double MAX_FREQ = 1350;
    /** Duracao de um ciclo completo (sobe e desce) da sirene. */
    private static final double SWEEP_PERIOD_S = 2.4;
    private static final double VOLUME = 0.7;
    /** Pedaco gerado por vez - pequeno para o stop() responder rapido. */
    private static final int CHUNK_FRAMES = 882; // 20 ms

    private final Object lock = new Object();
    private volatile boolean playing;
    private Thread thread;
    private volatile Consumer<String> errorListener;

    public void setErrorListener(Consumer<String> errorListener) {
        this.errorListener = errorListener;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void start() {
        synchronized (lock) {
            if (playing) {
                return;
            }
            playing = true;
            thread = new Thread(this::playLoop, "siren-player");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            playing = false;
            thread = null;
        }
    }

    private void playLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            // Buffer de ~100 ms: curto o bastante para o som parar quase na hora.
            line.open(format, (int) (SAMPLE_RATE / 10) * 2);
            line.start();

            byte[] buffer = new byte[CHUNK_FRAMES * 2];
            double phase = 0;
            long frame = 0;
            long fadeInFrames = (long) (SAMPLE_RATE * 0.05);
            while (playing) {
                for (int i = 0; i < CHUNK_FRAMES; i++, frame++) {
                    double t = frame / SAMPLE_RATE;
                    // Varia a frequencia seguindo um seno lento entre MIN e MAX.
                    double sweep = (1 - Math.cos(2 * Math.PI * t / SWEEP_PERIOD_S)) / 2;
                    double freq = MIN_FREQ + (MAX_FREQ - MIN_FREQ) * sweep;
                    phase += 2 * Math.PI * freq / SAMPLE_RATE;
                    if (phase > 2 * Math.PI) {
                        phase -= 2 * Math.PI;
                    }
                    // Um pouco do 2o harmonico deixa o som mais "estridente", como sirene.
                    double sample = 0.75 * Math.sin(phase) + 0.25 * Math.sin(2 * phase);
                    double gain = frame < fadeInFrames ? (double) frame / fadeInFrames : 1.0;
                    short value = (short) (sample * gain * VOLUME * Short.MAX_VALUE);
                    buffer[i * 2] = (byte) value;
                    buffer[i * 2 + 1] = (byte) (value >> 8);
                }
                line.write(buffer, 0, buffer.length);
            }
        } catch (Exception e) {
            playing = false;
            Consumer<String> listener = errorListener;
            if (listener != null) {
                listener.accept("Falha ao tocar a sirene: " + e.getMessage());
            }
        } finally {
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
            }
        }
    }
}
