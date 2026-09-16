package com.transacao.common.remote;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorEvent;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sincroniza a area de transferencia (texto e arquivos copiados) entre os
 * dois lados da conexao, para que Ctrl+C na maquina remota e Ctrl+V na
 * maquina local (e vice-versa) movam o mesmo conteudo.
 *
 * Usa java.awt.datatransfer.Clipboard.addFlavorListener para detectar
 * mudancas locais e reencaminha-las; ao aplicar uma mudanca vinda do outro
 * lado, marca um flag para nao reencaminhar de volta (evitar eco infinito).
 */
public class ClipboardSync implements FlavorListener {

    public interface Sender {
        void sendText(String text);

        void sendFiles(byte[] zipBytes);
    }

    private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    private final Sender sender;
    private final File receivedDir;
    private volatile String lastKnownText;
    private volatile boolean applyingRemoteChange = false;

    public ClipboardSync(Sender sender, File receivedDir) {
        this.sender = sender;
        this.receivedDir = receivedDir;
        clipboard.addFlavorListener(this);
    }

    public void stop() {
        clipboard.removeFlavorListener(this);
    }

    @Override
    public void flavorsChanged(FlavorEvent e) {
        if (applyingRemoteChange) {
            return;
        }
        try {
            Transferable t = clipboard.getContents(null);
            if (t == null) {
                return;
            }
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                byte[] zip = zipFiles(files);
                if (zip != null) {
                    sender.sendFiles(zip);
                }
            } else if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (text != null && !text.equals(lastKnownText)) {
                    lastKnownText = text;
                    sender.sendText(text);
                }
            }
        } catch (Exception ignored) {
            // area de transferencia pode ficar momentaneamente indisponivel
            // (outro processo segurando o lock nativo); ignora e tenta na proxima mudanca
        }
    }

    public void applyRemoteText(String text) {
        applyingRemoteChange = true;
        try {
            lastKnownText = text;
            clipboard.setContents(new StringSelection(text), null);
        } finally {
            applyingRemoteChange = false;
        }
    }

    public void applyRemoteFiles(byte[] zipBytes) {
        applyingRemoteChange = true;
        try {
            File targetDir = new File(receivedDir, "clip_" + System.currentTimeMillis());
            targetDir.mkdirs();
            List<File> extracted = unzip(zipBytes, targetDir);
            clipboard.setContents(new FileListTransferable(extracted), null);
        } catch (IOException ignored) {
        } finally {
            applyingRemoteChange = false;
        }
    }

    private byte[] zipFiles(List<File> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (File f : files) {
                if (f.isFile()) {
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    Files.copy(f.toPath(), zos);
                    zos.closeEntry();
                }
            }
        }
        return baos.toByteArray();
    }

    private List<File> unzip(byte[] zipBytes, File targetDir) throws IOException {
        List<File> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                result.add(out);
            }
        }
        return result;
    }

    private static class FileListTransferable implements Transferable {
        private final List<File> files;

        FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }
}
