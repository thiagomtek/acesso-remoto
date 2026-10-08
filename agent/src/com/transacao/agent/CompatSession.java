package com.transacao.agent;

import com.transacao.common.remote.RemoteMessageSender;
import com.transacao.common.remote.ScreenStreamer;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Modo compativel (alternativa ao WebRTC): Java puro, sem nenhuma biblioteca nativa, para maquinas
 * onde o Windows bloqueia DLLs sem assinatura (Smart App Control / WDAC). Reaproveita o ScreenStreamer
 * do client original: captura na resolucao fisica, so os blocos que mudaram, comprimidos sem perdas
 * (PNG), taxa adaptativa e adaptacao ao tamanho da tela de quem ve. Os blocos vao pelo hub
 * (WebSocket) em frames binarios; os comandos do operador voltam pelo hub como JSON.
 */
final class CompatSession {

    private final String sessionId;
    private final AgentSettings settings;
    private final InputHandler.InputSupplier input;
    private final Predicate<byte[]> sendBinary;
    private final Consumer<String> log;
    private final InputHandler.TraceListener trace;
    private final Object writeLock = new Object();

    // Dois streamers com a mesma interface: o novo (CopyRect + JPEG/refinamento + fluxo) e o original
    // (PNG puro), mantido para comparacao/emergencia com TRANSACAO_COMPAT_V1=1.
    private Runnable runner;
    private java.util.function.Consumer<Dimension> viewportSetter;
    private Runnable wake;
    private Runnable stopper;
    private java.util.function.IntConsumer ackHandler = n -> { };
    private java.util.function.Consumer<String> profileSetter = q -> { };
    private Thread thread;
    private InputHandler inputHandler;
    private volatile boolean closed;

    CompatSession(String sessionId, AgentSettings settings, InputHandler.InputSupplier input,
                  Predicate<byte[]> sendBinary, Consumer<String> log, InputHandler.TraceListener trace) {
        this.sessionId = sessionId;
        this.settings = settings;
        this.input = input;
        this.sendBinary = sendBinary;
        this.log = log;
        this.trace = trace;
    }

    String id() {
        return sessionId;
    }

    void start() throws Exception {
        DataOutputStream out = new DataOutputStream(new FrameSink(uuidBytes(sessionId)));
        Consumer<Dimension> sizeListener = size -> {
            try {
                RemoteMessageSender.sendStreamSize(out, writeLock, size);
            } catch (IOException e) {
                log.accept("Falha ao informar o tamanho da transmissao: " + e.getMessage());
            }
        };
        Dimension screenSize;
        double dpi;
        if ("1".equals(System.getenv("TRANSACAO_COMPAT_V1"))) {
            ScreenStreamer s = new ScreenStreamer(out, writeLock);
            s.setErrorListener(log);
            s.setStreamSizeListener(sizeListener);
            runner = s;
            viewportSetter = s::setTargetViewport;
            wake = s::requestImmediateCapture;
            stopper = s::stop;
            screenSize = s.getScreenSize();
            dpi = s.getDpiScale();
        } else {
            TileStreamer s = new TileStreamer(out, writeLock);
            s.setErrorListener(log);
            s.setStreamSizeListener(sizeListener);
            runner = s;
            viewportSetter = s::setTargetViewport;
            wake = s::requestImmediateCapture;
            stopper = s::stop;
            ackHandler = s::ack;
            profileSetter = s::setProfile;
            screenSize = s.getScreenSize();
            dpi = s.getDpiScale();
        }
        inputHandler = new InputHandler(settings, input,
                (w, h) -> {
                    if (w > 0 && h > 0) {
                        viewportSetter.accept(new Dimension(w, h));
                    }
                },
                // Acorda a captura na hora, em vez de esperar o intervalo ocioso: e o que faz o
                // resultado de um clique aparecer logo para quem esta controlando.
                () -> wake.run(), log, trace);
        profileSetter.accept(settings.quality);
        RemoteMessageSender.sendScreenSize(out, writeLock, screenSize, dpi);
        thread = new Thread(runner, "compat-streamer-" + sessionId.substring(0, 8));
        thread.setDaemon(true);
        thread.start();
        log.accept("Sessao " + sessionId.substring(0, 8) + " em MODO COMPATIVEL (sem WebRTC).");
    }

    /** Configuracoes mudaram no painel: aplica na hora na sessao em andamento. */
    void applySettings() {
        profileSetter.accept(settings.quality);
    }

    void onCommand(Map<String, Object> data) {
        if ("ack".equals(Json.str(data, "t"))) { // confirmacao de quadro desenhado (controle de fluxo)
            ackHandler.accept((int) Json.num(data, "n", 0));
            return;
        }
        if (!closed && inputHandler != null) {
            inputHandler.handleControl(data);
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (stopper != null) {
            stopper.run();
        }
        log.accept("Sessao " + sessionId.substring(0, 8) + " (compativel) encerrada.");
    }

    private static byte[] uuidBytes(String id) {
        UUID u = UUID.fromString(id);
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (56 - 8 * i));
            b[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return b;
    }

    /**
     * Cada mensagem do ScreenStreamer e escrita e descarregada (flush) de uma vez: o flush marca o
     * fim de uma mensagem, que vira um frame binario [0x01][sessionId 16 bytes][mensagem].
     */
    private final class FrameSink extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        private final byte[] idBytes;

        FrameSink(byte[] idBytes) {
            this.idBytes = idBytes;
        }

        @Override
        public void write(int b) {
            buf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (buf.size() == 0) {
                return;
            }
            byte[] payload = buf.toByteArray();
            buf.reset();
            byte[] frame = new byte[17 + payload.length];
            frame[0] = 0x01;
            System.arraycopy(idBytes, 0, frame, 1, 16);
            System.arraycopy(payload, 0, frame, 17, payload.length);
            if (!sendBinary.test(frame)) {
                throw new IOException("sem conexao com o hub");
            }
        }
    }
}
