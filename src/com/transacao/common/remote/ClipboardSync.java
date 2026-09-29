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
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Sincroniza a area de transferencia (texto e arquivos copiados) entre os
 * dois lados da conexao, para que Ctrl+C na maquina remota e Ctrl+V na
 * maquina local (e vice-versa) movam o mesmo conteudo.
 *
 * Utiliza tanto FlavorListener quanto uma thread de polling periodica (a cada
 * 300ms) para contornar limitacoes do Windows onde mudancas de texto sem
 * alteracao de flavor types nao disparam eventos do AWT.
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
    private volatile String lastKnownFilesFingerprint;
    private volatile boolean applyingRemoteChange = false;
    private volatile boolean running = true;
    private volatile boolean windowActive = true;
    private volatile Consumer<String> errorListener;
    private final Thread pollThread;

    public ClipboardSync(Sender sender, File receivedDir) {
        this.sender = sender;
        this.receivedDir = receivedDir;

        // Inicializa o estado conhecido para nao reenviar o conteudo que ja estava no clipboard
        initCurrentState();

        clipboard.addFlavorListener(this);

        this.pollThread = new Thread(this::pollLoop, "clipboard-sync-poll");
        this.pollThread.setDaemon(true);
        this.pollThread.start();
    }

    private void initCurrentState() {
        try {
            Transferable t = clipboard.getContents(null);
            if (t != null) {
                if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    lastKnownText = (String) t.getTransferData(DataFlavor.stringFlavor);
                } else if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    lastKnownFilesFingerprint = computeFilesFingerprint(files);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Chamado quando uma leitura/escrita do clipboard falha, so para logar na UI (nao interrompe a sincronizacao). */
    public void setErrorListener(Consumer<String> errorListener) {
        this.errorListener = errorListener;
    }

    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        clipboard.removeFlavorListener(this);
    }

    /**
     * Liga/desliga o monitoramento continuo da area de transferencia conforme
     * a janela deste app esta em primeiro plano (visivel e nao minimizada) ou
     * nao - para nao ficar de olho no clipboard o tempo todo enquanto o app
     * esta minimizado em segundo plano.
     *
     * Ao voltar ao primeiro plano, verifica o que esta no clipboard NESTE
     * MOMENTO e transfere se for diferente do que ja foi sincronizado antes -
     * e o mesmo comportamento de qualquer ferramenta de acesso remoto (copiar
     * um arquivo no Explorer e trocar para esta janela para colar so funciona
     * se o que foi copiado durante a troca de foco nao for descartado).
     */
    public synchronized void setWindowActive(boolean active) {
        if (active == windowActive) {
            return;
        }
        windowActive = active;
        if (active) {
            checkLocalClipboard();
        }
    }

    @Override
    public void flavorsChanged(FlavorEvent e) {
        checkLocalClipboard();
    }

    private void pollLoop() {
        while (running) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                break;
            }
            checkLocalClipboard();
        }
    }

    private synchronized void checkLocalClipboard() {
        if (applyingRemoteChange || !windowActive) {
            return;
        }
        try {
            Transferable t = clipboard.getContents(null);
            if (t == null) {
                return;
            }
            // Checa arquivos ANTES de texto: uma copia de arquivo/pasta no
            // Explorer as vezes tambem expoe uma flavor de texto (ex: o
            // caminho), o que faria ela ser tratada como texto em vez de
            // arquivo se checassemos texto primeiro.
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                String fingerprint = computeFilesFingerprint(files);
                if (fingerprint != null && !fingerprint.equals(lastKnownFilesFingerprint)) {
                    lastKnownFilesFingerprint = fingerprint;
                    lastKnownText = null;
                    byte[] zip = zipFiles(files);
                    if (zip != null) {
                        sender.sendFiles(zip);
                    }
                }
            } else if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (text != null && !text.isEmpty() && !text.equals(lastKnownText)) {
                    lastKnownText = text;
                    lastKnownFilesFingerprint = null;
                    sender.sendText(text);
                }
            }
        } catch (Exception e) {
            // Area de transferencia pode estar momentaneamente bloqueada por
            // outro app (comum no Windows) - nao interrompe a sincronizacao,
            // so avisa para nao ficar totalmente invisivel quando falha.
            if (errorListener != null) {
                errorListener.accept("Falha ao ler area de transferencia (tentando de novo): " + e);
            }
        }
    }

    public void applyRemoteText(String text) {
        applyingRemoteChange = true;
        try {
            lastKnownText = text;
            lastKnownFilesFingerprint = null;
            clipboard.setContents(new StringSelection(text), null);
        } finally {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            applyingRemoteChange = false;
        }
    }

    public void applyRemoteFiles(byte[] zipBytes) {
        applyingRemoteChange = true;
        try {
            File targetDir = new File(receivedDir, "clip_" + System.currentTimeMillis());
            targetDir.mkdirs();
            List<File> extracted = unzip(zipBytes, targetDir);
            lastKnownFilesFingerprint = computeFilesFingerprint(extracted);
            lastKnownText = null;
            clipboard.setContents(new FileListTransferable(extracted), null);
        } catch (IOException ignored) {
        } finally {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            applyingRemoteChange = false;
        }
    }

    private String computeFilesFingerprint(List<File> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            sb.append(f.getAbsolutePath()).append(':').append(f.length()).append(':').append(f.lastModified()).append(';');
        }
        return sb.toString();
    }

    private byte[] zipFiles(List<File> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (File f : files) {
                if (f.isDirectory()) {
                    zipDirectory(f, f.getName(), zos);
                } else if (f.isFile()) {
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    Files.copy(f.toPath(), zos);
                    zos.closeEntry();
                }
            }
        }
        return baos.toByteArray();
    }

    private void zipDirectory(File dir, String entryPrefix, ZipOutputStream zos) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String entryName = entryPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                zipDirectory(child, entryName, zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(child.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private List<File> unzip(byte[] zipBytes, File targetDir) throws IOException {
        // Retorna so as entradas de topo (arquivos soltos ou pastas), para o
        // clipboard exibir a mesma selecao de quem copiou (ex: uma pasta em vez
        // de cada arquivo dela individualmente).
        List<File> topLevel = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                }

                String topName = entry.getName().split("/", 2)[0];
                File topFile = new File(targetDir, topName);
                if (!topLevel.contains(topFile)) {
                    topLevel.add(topFile);
                }
            }
        }
        return topLevel;
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
