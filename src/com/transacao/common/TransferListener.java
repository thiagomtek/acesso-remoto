package com.transacao.common;

/**
 * Callback usado por FileTransferSender/FileTransferReceiver para reportar
 * progresso e eventos para a interface grafica.
 */
public interface TransferListener {

    void onLog(String message);

    void onProgress(long done, long total);

    void onTransferComplete(String description);
}
