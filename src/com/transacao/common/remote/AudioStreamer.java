package com.transacao.common.remote;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.DataOutputStream;

/**
 * Captura audio continuamente de um dispositivo de entrada (microfone ou
 * uma linha de monitoramento/loopback escolhida pelo usuario) e envia os
 * blocos PCM pela conexao ja estabelecida, ate ser parado.
 */
public class AudioStreamer implements Runnable {

    private final DataOutputStream out;
    private final Object writeLock;
    private final byte chunkType;
    private final TargetDataLine line;
    private volatile boolean running = true;

    public AudioStreamer(DataOutputStream out, Object writeLock, byte chunkType,
                          Mixer.Info mixerInfo, AudioFormat format) throws LineUnavailableException {
        this.out = out;
        this.writeLock = writeLock;
        this.chunkType = chunkType;
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        this.line = (TargetDataLine) mixer.getLine(info);
        this.line.open(format);
    }

    public AudioFormat getFormat() {
        return line.getFormat();
    }

    public void start() {
        line.start();
    }

    public void stop() {
        running = false;
        try {
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[4096];
        try {
            while (running) {
                int read = line.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                synchronized (writeLock) {
                    out.writeByte(chunkType);
                    out.writeInt(chunk.length);
                    out.write(chunk);
                    out.flush();
                }
            }
        } catch (Exception e) {
            System.err.println("Streaming de audio encerrado: " + e.getMessage());
        }
    }
}
