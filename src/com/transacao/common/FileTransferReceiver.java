package com.transacao.common;

import com.transacao.common.remote.AudioChannelListener;
import com.transacao.common.remote.ClipboardListener;
import com.transacao.common.remote.RemoteControlListener;
import com.transacao.common.remote.RemoteFrameListener;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Le continuamente mensagens da conexao SSL e recria, na pasta de saida
 * configurada, o zip recebido ou a arvore de pastas/arquivos de texto recebida.
 * Tambem despacha, para quem estiver interessado, as mensagens de acesso
 * remoto (frames de tela e eventos de mouse/teclado) que chegarem na mesma
 * conexao.
 *
 * Roda em background (uma thread por conexao) para nao travar a interface.
 */
public class FileTransferReceiver implements Runnable {

    private final DataInputStream in;
    private final File outputDir;
    private final TransferListener listener;
    private volatile boolean running = true;

    private volatile RemoteFrameListener remoteFrameListener;
    private volatile RemoteControlListener remoteControlListener;
    private volatile ClipboardListener clipboardListener;
    private volatile AudioChannelListener micAudioListener;
    private volatile AudioChannelListener systemAudioListener;
    private volatile BiConsumer<String, String> helloListener;
    private volatile Runnable disconnectListener;
    private volatile Consumer<byte[]> updateListener;

    public FileTransferReceiver(DataInputStream in, File outputDir, TransferListener listener) {
        this.in = in;
        this.outputDir = outputDir;
        this.listener = listener;
    }

    /** Registrado por quem CONTROLA, para exibir a tela recebida. */
    public void setRemoteFrameListener(RemoteFrameListener remoteFrameListener) {
        this.remoteFrameListener = remoteFrameListener;
    }

    /** Registrado por quem COMPARTILHA, para reagir a inicio/fim e a entrada recebida. */
    public void setRemoteControlListener(RemoteControlListener remoteControlListener) {
        this.remoteControlListener = remoteControlListener;
    }

    /** Registrado por qualquer um dos dois lados, para sincronizar a area de transferencia. */
    public void setClipboardListener(ClipboardListener clipboardListener) {
        this.clipboardListener = clipboardListener;
    }

    /** Registrado por quem RECEBE o audio do microfone do outro lado. */
    public void setMicAudioListener(AudioChannelListener micAudioListener) {
        this.micAudioListener = micAudioListener;
    }

    /** Registrado por quem RECEBE o audio do sistema do outro lado. */
    public void setSystemAudioListener(AudioChannelListener systemAudioListener) {
        this.systemAudioListener = systemAudioListener;
    }

    /** Registrado pelo servidor para saber o nome/hostname e a versao (hash do jar) de quem conectou. */
    public void setHelloListener(BiConsumer<String, String> helloListener) {
        this.helloListener = helloListener;
    }

    /** Chamado quando o loop de recebimento termina (conexao caiu ou foi fechada). */
    public void setDisconnectListener(Runnable disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    /** Registrado pelo client para receber uma nova versao do proprio jar enviada pelo servidor. */
    public void setUpdateListener(Consumer<byte[]> updateListener) {
        this.updateListener = updateListener;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        outputDir.mkdirs();
        try {
            while (running) {
                byte type;
                try {
                    type = in.readByte();
                } catch (EOFException | SocketException e) {
                    break;
                }

                if (type == Protocol.TYPE_ZIP) {
                    receiveZip();
                } else if (type == Protocol.TYPE_DIR) {
                    receiveDir();
                } else if (!dispatchRemote(type)) {
                    if (listener != null) {
                        listener.onLog("Tipo de mensagem desconhecido recebido: " + type);
                    }
                    break;
                }
            }
        } catch (IOException e) {
            if (running && listener != null) {
                listener.onLog("Conexao encerrada: " + e.getMessage());
            }
        } finally {
            if (listener != null) {
                listener.onLog("Recebimento finalizado.");
            }
            if (disconnectListener != null) {
                disconnectListener.run();
            }
        }
    }

    /**
     * Trata uma mensagem de acesso remoto, se for de um tipo conhecido.
     * Retorna false se o tipo nao for de acesso remoto, para o chamador
     * decidir o que fazer com uma mensagem realmente desconhecida.
     */
    private boolean dispatchRemote(byte type) throws IOException {
        switch (type) {
            case Protocol.CLIENT_HELLO: {
                String name = in.readUTF();
                String jarHash = in.readUTF();
                if (helloListener != null) {
                    helloListener.accept(name, jarHash);
                }
                return true;
            }
            case Protocol.UPDATE_PUSH: {
                long length = in.readLong();
                byte[] jarBytes = new byte[(int) length];
                in.readFully(jarBytes);
                if (updateListener != null) {
                    updateListener.accept(jarBytes);
                }
                return true;
            }
            case Protocol.REMOTE_START:
                if (remoteControlListener != null) {
                    remoteControlListener.onStartRequested();
                }
                return true;
            case Protocol.REMOTE_STOP:
                if (remoteControlListener != null) {
                    remoteControlListener.onStopRequested();
                }
                return true;
            case Protocol.REMOTE_SCREEN_SIZE: {
                int width = in.readInt();
                int height = in.readInt();
                double dpiScale = in.readDouble();
                if (remoteFrameListener != null) {
                    remoteFrameListener.onScreenSize(new Dimension(width, height), dpiScale);
                }
                return true;
            }
            case Protocol.REMOTE_FRAME_TILE: {
                int x = in.readInt();
                int y = in.readInt();
                in.readInt();
                in.readInt();
                int length = in.readInt();
                byte[] pngBytes = new byte[length];
                in.readFully(pngBytes);
                if (remoteFrameListener != null) {
                    BufferedImage tile = ImageIO.read(new ByteArrayInputStream(pngBytes));
                    remoteFrameListener.onTile(x, y, tile);
                }
                return true;
            }
            case Protocol.REMOTE_MOUSE_MOVE: {
                int x = in.readInt();
                int y = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onMouseMove(x, y);
                }
                return true;
            }
            case Protocol.REMOTE_MOUSE_PRESS: {
                int button = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onMousePress(button);
                }
                return true;
            }
            case Protocol.REMOTE_MOUSE_RELEASE: {
                int button = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onMouseRelease(button);
                }
                return true;
            }
            case Protocol.REMOTE_MOUSE_WHEEL: {
                int rotation = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onMouseWheel(rotation);
                }
                return true;
            }
            case Protocol.REMOTE_KEY_PRESS: {
                int keyCode = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onKeyPress(keyCode);
                }
                return true;
            }
            case Protocol.REMOTE_KEY_RELEASE: {
                int keyCode = in.readInt();
                if (remoteControlListener != null) {
                    remoteControlListener.onKeyRelease(keyCode);
                }
                return true;
            }
            case Protocol.REMOTE_KEY_TYPED: {
                char c = in.readChar();
                if (remoteControlListener != null) {
                    remoteControlListener.onKeyTyped(c);
                }
                return true;
            }
            case Protocol.REMOTE_CLIPBOARD_TEXT: {
                int length = in.readInt();
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                if (clipboardListener != null) {
                    clipboardListener.onClipboardText(new String(bytes, StandardCharsets.UTF_8));
                }
                return true;
            }
            case Protocol.REMOTE_CLIPBOARD_FILES: {
                int length = in.readInt();
                byte[] zipBytes = new byte[length];
                in.readFully(zipBytes);
                if (clipboardListener != null) {
                    clipboardListener.onClipboardFiles(zipBytes);
                }
                return true;
            }
            case Protocol.REMOTE_MIC_START: {
                AudioFormat format = readAudioFormat();
                if (micAudioListener != null) {
                    micAudioListener.onStart(format);
                }
                return true;
            }
            case Protocol.REMOTE_MIC_STOP:
                if (micAudioListener != null) {
                    micAudioListener.onStop();
                }
                return true;
            case Protocol.REMOTE_MIC_CHUNK: {
                byte[] chunk = readChunk();
                if (micAudioListener != null) {
                    micAudioListener.onChunk(chunk);
                }
                return true;
            }
            case Protocol.REMOTE_SYSAUDIO_START: {
                AudioFormat format = readAudioFormat();
                if (systemAudioListener != null) {
                    systemAudioListener.onStart(format);
                }
                return true;
            }
            case Protocol.REMOTE_SYSAUDIO_STOP:
                if (systemAudioListener != null) {
                    systemAudioListener.onStop();
                }
                return true;
            case Protocol.REMOTE_SYSAUDIO_CHUNK: {
                byte[] chunk = readChunk();
                if (systemAudioListener != null) {
                    systemAudioListener.onChunk(chunk);
                }
                return true;
            }
            default:
                return false;
        }
    }

    private AudioFormat readAudioFormat() throws IOException {
        float sampleRate = in.readFloat();
        int bits = in.readInt();
        int channels = in.readInt();
        return new AudioFormat(sampleRate, bits, channels, true, false);
    }

    private byte[] readChunk() throws IOException {
        int length = in.readInt();
        byte[] chunk = new byte[length];
        in.readFully(chunk);
        return chunk;
    }

    private void receiveZip() throws IOException {
        String name = in.readUTF();
        long length = in.readLong();
        File outFile = uniqueFile(outputDir, name);

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) {
                    throw new EOFException("Conexao perdida durante recebimento do ZIP");
                }
                os.write(buffer, 0, read);
                remaining -= read;
                if (listener != null) {
                    listener.onProgress(length - remaining, length);
                }
            }
        }

        if (listener != null) {
            listener.onLog("Recebido ZIP: " + outFile.getName() + " (" + length + " bytes)");
            listener.onTransferComplete(outFile.getAbsolutePath());
        }
    }

    private void receiveDir() throws IOException {
        String rootName = in.readUTF();
        int fileCount = in.readInt();
        File rootOut = uniqueFile(outputDir, rootName);
        rootOut.mkdirs();

        for (int i = 0; i < fileCount; i++) {
            String relative = in.readUTF();
            int len = in.readInt();
            byte[] content = new byte[len];
            in.readFully(content);

            File target = new File(rootOut, relative);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(target.toPath(), content);

            if (listener != null) {
                listener.onProgress(i + 1, Math.max(fileCount, 1));
            }
        }

        if (listener != null) {
            listener.onLog("Recebida pasta: " + rootOut.getName() + " (" + fileCount + " arquivos)");
            listener.onTransferComplete(rootOut.getAbsolutePath());
        }
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        File candidate;
        int i = 1;
        do {
            candidate = new File(dir, base + "_" + i + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }
}
