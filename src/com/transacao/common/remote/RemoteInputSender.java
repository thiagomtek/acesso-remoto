package com.transacao.common.remote;

/**
 * Usado pelo RemoteViewerPanel para encaminhar, ao lado que compartilha a
 * tela, os eventos de mouse/teclado capturados localmente por quem controla.
 */
public interface RemoteInputSender {

    void sendMouseMove(int x, int y);

    void sendMousePress(int button);

    void sendMouseRelease(int button);

    void sendMouseWheel(int rotation);

    void sendKeyPress(int keyCode);

    void sendKeyRelease(int keyCode);

    void sendKeyTyped(char c);
}
