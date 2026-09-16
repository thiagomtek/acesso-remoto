package com.transacao.common;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Envia arquivos pela conexao SSL, seguindo o protocolo definido em Protocol.
 *
 * - ZIP: enviado como binario puro, byte a byte, preservando o arquivo exatamente.
 * - Pasta: percorrida recursivamente e cada arquivo e enviado como texto
 *   (nome relativo + conteudo), para ser recriado do outro lado.
 */
public final class FileTransferSender {

    private static final int BUFFER_SIZE = 8192;

    private FileTransferSender() {
    }

    public static void sendZip(DataOutputStream out, Object writeLock, File zipFile, TransferListener listener) throws IOException {
        synchronized (writeLock) {
            out.writeByte(Protocol.TYPE_ZIP);
            out.writeUTF(zipFile.getName());
            long length = zipFile.length();
            out.writeLong(length);

            try (InputStream in = new BufferedInputStream(new FileInputStream(zipFile))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long sent = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    sent += read;
                    if (listener != null) {
                        listener.onProgress(sent, length);
                    }
                }
            }
            out.flush();
            if (listener != null) {
                listener.onLog("Enviado ZIP: " + zipFile.getName() + " (" + length + " bytes)");
            }
        }
    }

    public static void sendDirectoryAsText(DataOutputStream out, Object writeLock, File rootDir, TransferListener listener) throws IOException {
        Path rootPath = rootDir.toPath();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }

        long totalSize = 0;
        for (Path f : files) {
            totalSize += Files.size(f);
        }

        synchronized (writeLock) {
            out.writeByte(Protocol.TYPE_DIR);
            out.writeUTF(rootDir.getName());
            out.writeInt(files.size());

            long sentBytes = 0;
            for (Path f : files) {
                String relative = rootPath.relativize(f).toString().replace(File.separatorChar, '/');
                byte[] content = Files.readAllBytes(f);
                out.writeUTF(relative);
                out.writeInt(content.length);
                out.write(content);
                sentBytes += content.length;
                if (listener != null) {
                    listener.onProgress(sentBytes, Math.max(totalSize, 1));
                }
            }
            out.flush();
            if (listener != null) {
                listener.onLog("Enviada pasta: " + rootDir.getName() + " (" + files.size() + " arquivos)");
            }
        }
    }
}
