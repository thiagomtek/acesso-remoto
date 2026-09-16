package com.transacao.common;

/**
 * Constantes do protocolo de transferencia usado entre server e client.
 */
public final class Protocol {

    /** Transferencia de um arquivo .zip completo, em modo binario. */
    public static final byte TYPE_ZIP = 1;

    /** Transferencia de uma pasta inteira, arquivo por arquivo, em modo texto. */
    public static final byte TYPE_DIR = 2;

    /** Pede ao lado que compartilha a tela para iniciar o streaming. */
    public static final byte REMOTE_START = 10;

    /** Pede ao lado que compartilha a tela para parar o streaming. */
    public static final byte REMOTE_STOP = 11;

    /** Tamanho da tela de quem compartilha (largura/altura), enviado ao iniciar. */
    public static final byte REMOTE_SCREEN_SIZE = 12;

    /** (Nao usado mais) Um frame JPEG completo da tela compartilhada. */
    public static final byte REMOTE_FRAME = 13;

    /** Um bloco (tile) PNG sem perdas com a regiao da tela que mudou. */
    public static final byte REMOTE_FRAME_TILE = 14;

    /** Evento de mouse: mover cursor. */
    public static final byte REMOTE_MOUSE_MOVE = 20;

    /** Evento de mouse: pressionar botao. */
    public static final byte REMOTE_MOUSE_PRESS = 21;

    /** Evento de mouse: soltar botao. */
    public static final byte REMOTE_MOUSE_RELEASE = 22;

    /** Evento de mouse: rolar roda. */
    public static final byte REMOTE_MOUSE_WHEEL = 23;

    /** Evento de teclado: pressionar tecla. */
    public static final byte REMOTE_KEY_PRESS = 24;

    /** Evento de teclado: soltar tecla. */
    public static final byte REMOTE_KEY_RELEASE = 25;

    /** Sincronizacao de area de transferencia: texto copiado. */
    public static final byte REMOTE_CLIPBOARD_TEXT = 26;

    /** Sincronizacao de area de transferencia: arquivos copiados (zip). */
    public static final byte REMOTE_CLIPBOARD_FILES = 27;

    /** Inicia o envio do audio do microfone (carrega o formato usado). */
    public static final byte REMOTE_MIC_START = 40;

    /** Para o envio do audio do microfone. */
    public static final byte REMOTE_MIC_STOP = 41;

    /** Um bloco de audio PCM do microfone. */
    public static final byte REMOTE_MIC_CHUNK = 42;

    /** Inicia o envio do audio do sistema (carrega o formato usado). */
    public static final byte REMOTE_SYSAUDIO_START = 43;

    /** Para o envio do audio do sistema. */
    public static final byte REMOTE_SYSAUDIO_STOP = 44;

    /** Um bloco de audio PCM do audio do sistema. */
    public static final byte REMOTE_SYSAUDIO_CHUNK = 45;

    /** Enviado pelo client logo apos conectar, com seu nome/hostname e o hash do proprio jar (para o servidor identificar quem e quem e se a versao esta desatualizada). */
    public static final byte CLIENT_HELLO = 50;

    /** Enviado pelo servidor ao client com uma nova versao do proprio client.jar, para auto-atualizacao. */
    public static final byte UPDATE_PUSH = 60;

    private Protocol() {
    }
}
