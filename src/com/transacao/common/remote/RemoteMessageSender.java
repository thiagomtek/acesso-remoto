package com.transacao.common.remote;

import com.transacao.common.Protocol;

import javax.sound.sampled.AudioFormat;
import java.awt.Dimension;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Escreve mensagens de acesso remoto (controle e tamanho de tela) na
 * conexao ja estabelecida, sincronizando no mesmo lock usado para as
 * demais escritas (transferencia de arquivo e streaming de tela) para
 * nao intercalar bytes de mensagens diferentes.
 */
public final class RemoteMessageSender {

    private RemoteMessageSender() {
    }

    public static void sendStart(DataOutputStream out, Object writeLock) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_START);
            out.flush();
        }
    }

    public static void sendStop(DataOutputStream out, Object writeLock) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_STOP);
            out.flush();
        }
    }

    public static void sendScreenSize(DataOutputStream out, Object writeLock, Dimension size, double dpiScale) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_SCREEN_SIZE);
            out.writeInt(size.width);
            out.writeInt(size.height);
            out.writeDouble(dpiScale);
            out.flush();
        }
    }

    public static void sendMouseMove(DataOutputStream out, Object writeLock, int x, int y) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MOUSE_MOVE);
            out.writeInt(x);
            out.writeInt(y);
            out.flush();
        }
    }

    public static void sendMousePress(DataOutputStream out, Object writeLock, int button) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MOUSE_PRESS);
            out.writeInt(button);
            out.flush();
        }
    }

    public static void sendMouseRelease(DataOutputStream out, Object writeLock, int button) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MOUSE_RELEASE);
            out.writeInt(button);
            out.flush();
        }
    }

    public static void sendMouseWheel(DataOutputStream out, Object writeLock, int rotation) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MOUSE_WHEEL);
            out.writeInt(rotation);
            out.flush();
        }
    }

    public static void sendKeyPress(DataOutputStream out, Object writeLock, int keyCode) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_KEY_PRESS);
            out.writeInt(keyCode);
            out.flush();
        }
    }

    public static void sendKeyRelease(DataOutputStream out, Object writeLock, int keyCode) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_KEY_RELEASE);
            out.writeInt(keyCode);
            out.flush();
        }
    }

    public static void sendKeyTyped(DataOutputStream out, Object writeLock, char c) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_KEY_TYPED);
            out.writeChar(c);
            out.flush();
        }
    }

    public static void sendClipboardText(DataOutputStream out, Object writeLock, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_CLIPBOARD_TEXT);
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
        }
    }

    public static void sendClipboardFiles(DataOutputStream out, Object writeLock, byte[] zipBytes) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_CLIPBOARD_FILES);
            out.writeInt(zipBytes.length);
            out.write(zipBytes);
            out.flush();
        }
    }

    public static void sendMicStart(DataOutputStream out, Object writeLock, AudioFormat format) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MIC_START);
            out.writeFloat(format.getSampleRate());
            out.writeInt(format.getSampleSizeInBits());
            out.writeInt(format.getChannels());
            out.flush();
        }
    }

    public static void sendMicStop(DataOutputStream out, Object writeLock) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MIC_STOP);
            out.flush();
        }
    }

    public static void sendMicChunk(DataOutputStream out, Object writeLock, byte[] chunk) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_MIC_CHUNK);
            out.writeInt(chunk.length);
            out.write(chunk);
            out.flush();
        }
    }

    public static void sendSysAudioStart(DataOutputStream out, Object writeLock, AudioFormat format) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_SYSAUDIO_START);
            out.writeFloat(format.getSampleRate());
            out.writeInt(format.getSampleSizeInBits());
            out.writeInt(format.getChannels());
            out.flush();
        }
    }

    public static void sendSysAudioStop(DataOutputStream out, Object writeLock) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_SYSAUDIO_STOP);
            out.flush();
        }
    }

    public static void sendSysAudioChunk(DataOutputStream out, Object writeLock, byte[] chunk) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.REMOTE_SYSAUDIO_CHUNK);
            out.writeInt(chunk.length);
            out.write(chunk);
            out.flush();
        }
    }

    public static void sendUpdatePush(DataOutputStream out, Object writeLock, byte[] jarBytes) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.UPDATE_PUSH);
            out.writeLong(jarBytes.length);
            out.write(jarBytes);
            out.flush();
        }
    }
}
