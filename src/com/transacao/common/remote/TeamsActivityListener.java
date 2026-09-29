package com.transacao.common.remote;

/**
 * Recebe avisos de atividade nova no Microsoft Teams detectados no lado que
 * compartilha a tela (client).
 */
public interface TeamsActivityListener {

    void onActivityDetected();

    void onActivityCleared();
}
