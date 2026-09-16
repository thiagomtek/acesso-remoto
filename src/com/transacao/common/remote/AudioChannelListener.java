package com.transacao.common.remote;

import javax.sound.sampled.AudioFormat;

/**
 * Recebe os eventos de um canal de audio (microfone ou audio do sistema)
 * vindo do outro lado da conexao.
 */
public interface AudioChannelListener {

    void onStart(AudioFormat format);

    void onStop();

    void onChunk(byte[] pcmData);
}
