package com.transacao.server;

import com.transacao.common.FileTransferReceiver;

import javax.net.ssl.SSLSocket;
import java.io.DataOutputStream;
import java.io.File;

/**
 * Estado de uma conexao de um client com o servidor. Como varios clients
 * podem estar conectados ao mesmo tempo, cada um tem sua propria sessao
 * (socket, stream de saida, lock de escrita e thread de recebimento), e o
 * usuario escolhe no combo box qual delas esta "ativa" no momento.
 */
public class ClientSession {

    public final SSLSocket socket;
    public final DataOutputStream out;
    public final Object writeLock = new Object();
    public final FileTransferReceiver receiver;
    public Thread receiverThread;
    public volatile String displayName;
    public volatile String jarHash;
    public File selectedFile;

    public ClientSession(SSLSocket socket, DataOutputStream out, FileTransferReceiver receiver, String displayName) {
        this.socket = socket;
        this.out = out;
        this.receiver = receiver;
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
