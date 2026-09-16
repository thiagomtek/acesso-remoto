package com.transacao.common.remote;

/**
 * Recebe atualizacoes da area de transferencia do outro lado da conexao.
 */
public interface ClipboardListener {

    void onClipboardText(String text);

    void onClipboardFiles(byte[] zipBytes);
}
