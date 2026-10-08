package com.transacao.agent;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpCodecCapability;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaType;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Uma sessao de acesso remoto com um operador: o agente cria a oferta WebRTC com a tela (video)
 * e dois canais de dados - "ctl" (confiavel e ordenado: teclado, cliques, viewport) e "mouse"
 * (nao ordenado, sem retransmissao: so a posicao mais recente importa, como em VNC/RDP).
 */
final class RemoteSession implements PeerConnectionObserver {

    interface ClipboardImageApplier {
        boolean apply(byte[] png, String sha256);
    }

    private static final String[] CODEC_PRIORITY = {"video/H264", "video/VP9", "video/AV1", "video/VP8"};

    private final String sessionId;
    private final List<Map<String, Object>> iceServers;
    private final PeerConnectionFactory factory;
    private final ScreenSource screen;
    private final AgentSettings settings;
    private final InputHandler inputHandler;
    private final Consumer<String> applyClipboard;
    private final ClipboardImageApplier applyClipboardImage;
    private final InputHandler.TraceListener trace;
    private final Consumer<Map<String, Object>> sendRtc;
    private final Consumer<String> log;

    private RTCPeerConnection pc;
    private RTCDataChannel ctl;
    private RTCDataChannel mouse;
    private RTCRtpSender videoSender;
    private RTCRtpTransceiver transceiver;
    private boolean screenAcquired;
    private volatile boolean closed;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Object encodingLock = new Object();
    /** Callbacks nativos NUNCA podem bloquear nem chamar close() direto: o close espera a thread de sinalizacao. */
    private final Executor async;
    private final Runnable onFailure;
    private volatile boolean connected;
    private int viewportW;
    private int viewportH;
    private ByteArrayOutputStream incomingClipboardImage;
    private int incomingClipboardImageSize;
    private long incomingClipboardImageSeq;
    private String incomingClipboardImageTrace;
    private String incomingClipboardImageSha;

    RemoteSession(String sessionId, List<Map<String, Object>> iceServers, PeerConnectionFactory factory, ScreenSource screen,
                  AgentSettings settings, InputHandler.InputSupplier input, Consumer<String> applyClipboard,
                  ClipboardImageApplier applyClipboardImage, Consumer<Map<String, Object>> sendRtc,
                  Consumer<String> log, InputHandler.TraceListener trace,
                  Executor async, Runnable onFailure) {
        this.async = async;
        this.onFailure = onFailure;
        this.sessionId = sessionId;
        this.iceServers = iceServers;
        this.factory = factory;
        this.screen = screen;
        this.settings = settings;
        this.applyClipboard = applyClipboard;
        this.applyClipboardImage = applyClipboardImage;
        this.trace = trace;
        this.inputHandler = new InputHandler(settings, input, (w, h) -> {
            viewportW = w;
            viewportH = h;
            applyEncoding();
        }, () -> { }, log, trace);
        this.sendRtc = sendRtc;
        this.log = log;
    }

    String id() {
        return sessionId;
    }

    boolean isConnected() {
        return connected;
    }

    /** Avisa o operador do motivo da falha (aparece no painel em vez de ficar em "conectando"). */
    void reportError(String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "error");
        d.put("message", message);
        sendRtc.accept(d);
    }

    @SuppressWarnings("unchecked")
    void start() throws Exception {
        RTCConfiguration cfg = new RTCConfiguration();
        cfg.iceServers = new ArrayList<>();
        for (Map<String, Object> s : iceServers) {
            RTCIceServer ice = new RTCIceServer();
            Object urls = s.get("urls");
            ice.urls = new ArrayList<>();
            if (urls instanceof List) {
                for (Object u : (List<Object>) urls) {
                    ice.urls.add(String.valueOf(u));
                }
            } else if (urls != null) {
                ice.urls.add(String.valueOf(urls));
            }
            ice.username = Json.str(s, "username");
            ice.password = Json.str(s, "credential");
            cfg.iceServers.add(ice);
        }
        pc = factory.createPeerConnection(cfg, this);

        RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
        init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        RTCRtpTransceiver tr = pc.addTransceiver(screen.acquire(settings.maxFps()), init);
        screenAcquired = true;
        transceiver = tr;
        videoSender = tr.getSender();
        preferCodecs(tr);

        RTCDataChannelInit ordered = new RTCDataChannelInit();
        ctl = pc.createDataChannel("ctl", ordered);
        ctl.registerObserver(new ChannelObserver(ctl, true));
        RTCDataChannelInit lossy = new RTCDataChannelInit();
        lossy.ordered = false;
        lossy.maxRetransmits = 0;
        mouse = pc.createDataChannel("mouse", lossy);
        mouse.registerObserver(new ChannelObserver(mouse, false));

        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription offer) {
                pc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("type", "offer");
                        d.put("sdp", offer.sdp);
                        sendRtc.accept(d);
                    }

                    @Override
                    public void onFailure(String error) {
                        log.accept("Sessao " + shortId() + ": falha ao definir oferta: " + error);
                        async.execute(onFailure);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                log.accept("Sessao " + shortId() + ": falha ao criar oferta: " + error);
                async.execute(onFailure);
            }
        });
    }

    /** Prefere H.264 (decodificacao por hardware no navegador, pouca CPU); mantem rtx/red/fec. */
    private void preferCodecs(RTCRtpTransceiver tr) {
        try {
            List<RTCRtpCodecCapability> all = factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs();
            List<RTCRtpCodecCapability> ordered = new ArrayList<>();
            for (String mime : CODEC_PRIORITY) {
                for (RTCRtpCodecCapability c : all) {
                    if (c.getMimeType().equalsIgnoreCase(mime)) {
                        ordered.add(c);
                    }
                }
            }
            for (RTCRtpCodecCapability c : all) {
                if (!ordered.contains(c)) {
                    ordered.add(c);
                }
            }
            tr.setCodecPreferences(ordered);
        } catch (Exception e) {
            log.accept("Preferencia de codec ignorada: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    void onRemoteMessage(Map<String, Object> data) {
        if (closed || pc == null) {
            return;
        }
        String type = Json.str(data, "type");
        if ("answer".equals(type)) {
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, Json.str(data, "sdp")), new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onFailure(String error) {
                    log.accept("Sessao " + shortId() + ": resposta invalida: " + error);
                    async.execute(onFailure);
                }
            });
        } else if ("candidate".equals(type)) {
            String cand = Json.str(data, "candidate");
            if (cand != null && !cand.isEmpty()) {
                pc.addIceCandidate(new RTCIceCandidate(Json.str(data, "sdpMid"), (int) Json.num(data, "sdpMLineIndex", 0), cand));
            }
        }
    }

    void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        try {
            if (ctl != null) {
                ctl.unregisterObserver();
                ctl.close();
                ctl.dispose();
            }
            if (mouse != null) {
                mouse.unregisterObserver();
                mouse.close();
                mouse.dispose();
            }
            if (pc != null) {
                pc.close();
            }
        } catch (Throwable e) {
            log.accept("Erro ao encerrar sessao " + shortId() + ": " + e);
        }
        // O transceiver e o sender seguram uma referencia nativa da faixa de video: precisam ser
        // liberados ANTES da faixa, senao o dispose dela falha ("Native object was not deleted").
        try {
            if (videoSender != null) {
                videoSender.dispose();
            }
            if (transceiver != null) {
                transceiver.dispose();
            }
        } catch (Throwable e) {
            log.accept("Aviso ao liberar o transceiver da sessao " + shortId() + ": " + e);
        }
        if (screenAcquired) {
            screenAcquired = false;
            try {
                screen.release();
            } catch (Throwable e) {
                log.accept("Aviso ao liberar a captura da sessao " + shortId() + ": " + e);
            }
        }
        log.accept("Sessao " + shortId() + " encerrada.");
    }

    // ---------- PeerConnectionObserver ----------

    @Override
    public void onIceCandidate(RTCIceCandidate c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "candidate");
        d.put("candidate", c.sdp);
        d.put("sdpMid", c.sdpMid);
        d.put("sdpMLineIndex", c.sdpMLineIndex);
        sendRtc.accept(d);
    }

    @Override
    public void onConnectionChange(RTCPeerConnectionState state) {
        log.accept("Sessao " + shortId() + ": " + state);
        if (state == RTCPeerConnectionState.CONNECTED) {
            connected = true;
            applyEncoding();
        } else if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
            if (state == RTCPeerConnectionState.FAILED) {
                async.execute(onFailure); // o AgentApp decide: cai para o modo compativel
            } else {
                async.execute(this::close);
            }
        }
    }

    // ---------- qualidade ----------

    /** Teto de bitrate/fps conforme a qualidade escolhida e escala conforme o tamanho da tela de quem ve. */
    /** Reaplica teto/minimo de bitrate, fps e escala (ex.: a qualidade mudou no painel durante a sessao). */
    void applyEncoding() {
        if (closed || videoSender == null) {
            return;
        }
        synchronized (encodingLock) {
        try {
            RTCRtpSendParameters p = videoSender.getParameters();
            if (p.encodings == null || p.encodings.isEmpty()) {
                return;
            }
            RTCRtpEncodingParameters e = p.encodings.get(0);
            e.maxBitrate = settings.maxBitrate();
            e.minBitrate = settings.minBitrate();
            e.maxFramerate = (double) settings.maxFps();
            e.scaleResolutionDownBy = scaleFor(viewportW, viewportH);
            videoSender.setParameters(p);
        } catch (Exception ex) {
            log.accept("Nao foi possivel ajustar a qualidade: " + ex.getMessage());
        }
        }
    }

    /**
     * Reduz a resolucao so quando quem ve tem uma tela bem menor que a nativa (ex.: client 4K visto
     * num notebook 1080p) - nunca amplia, e perto da nativa nao vale pagar o redimensionamento.
     */
    static double scaleFor(int viewW, int viewH) {
        int[] nat = ScreenGeometry.physicalSize();
        if (viewW <= 0 || viewH <= 0 || (long) viewW * viewH >= 0.9 * nat[0] * nat[1]) {
            return 1.0;
        }
        return Math.max(1.0, Math.min((double) nat[0] / viewW, (double) nat[1] / viewH));
    }

    // ---------- canais de dados ----------

    private final class ChannelObserver implements RTCDataChannelObserver {
        private final RTCDataChannel channel;
        private final boolean control;

        ChannelObserver(RTCDataChannel channel, boolean control) {
            this.channel = channel;
            this.control = control;
        }

        @Override
        public void onBufferedAmountChange(long previousAmount) {
        }

        @Override
        public void onStateChange() {
            if (control && channel.getState() == dev.onvoid.webrtc.RTCDataChannelState.OPEN) {
                int[] phys = ScreenGeometry.physicalSize();
                Rectangle lb = ScreenGeometry.logicalBounds();
                Map<String, Object> hello = new LinkedHashMap<>();
                hello.put("t", "screen");
                hello.put("w", phys[0]);
                hello.put("h", phys[1]);
                hello.put("lw", lb.width);
                hello.put("lh", lb.height);
                hello.put("control", settings.allowRemoteControl);
                hello.put("clipboardImageV1", true);
                sendCtl(hello);
            }
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            if (buffer.binary) {
                if (control) receiveClipboardImageChunk(buffer.data);
                return;
            }
            ByteBuffer b = buffer.data;
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            try {
                if (control) {
                    Map<String, Object> command = Json.parseObject(text);
                    // Clipboard e teclado precisam compartilhar este canal confiavel e ordenado:
                    // assim o texto chega ao Windows antes do Ctrl+V, mesmo quando a sinalizacao
                    // pelo hub tem latencia diferente do WebRTC.
                    if ("clip".equals(Json.str(command, "t"))) {
                        applyClipboard.accept(Json.str(command, "text"));
                        if (trace != null) trace.onResult((long) Json.num(command, "q", 0), Json.str(command, "trace"), "clip", "applied");
                    } else if ("clip-image-start".equals(Json.str(command, "t"))) {
                        beginClipboardImage(command);
                    } else {
                        inputHandler.handleControl(command);
                    }
                } else {
                    inputHandler.handleMouseText(text);
                }
            } catch (Exception e) {
                log.accept("Comando remoto ignorado: " + e.getMessage());
            }
        }
    }

    private synchronized void beginClipboardImage(Map<String, Object> command) {
        if (incomingClipboardImage != null) finishClipboardImage("replaced");
        int size = (int) Json.num(command, "size", 0);
        String sha = Json.str(command, "sha256");
        long seq = (long) Json.num(command, "q", 0);
        String traceId = Json.str(command, "trace");
        if (!settings.clipboardSync || size < 1 || size > ClipboardImage.MAX_BYTES || sha == null
                || !sha.matches("[a-fA-F0-9]{64}") || seq < 1 || traceId == null) {
            if (trace != null) trace.onResult(seq, traceId, "clip-image-start", "invalid");
            return;
        }
        incomingClipboardImage = new ByteArrayOutputStream(size);
        incomingClipboardImageSize = size;
        incomingClipboardImageSeq = seq;
        incomingClipboardImageTrace = traceId;
        incomingClipboardImageSha = sha;
    }

    private synchronized void receiveClipboardImageChunk(ByteBuffer source) {
        if (incomingClipboardImage == null) return;
        byte[] chunk = new byte[source.remaining()];
        source.get(chunk);
        if (incomingClipboardImage.size() + chunk.length > incomingClipboardImageSize) {
            finishClipboardImage("invalid");
            return;
        }
        incomingClipboardImage.write(chunk, 0, chunk.length);
        if (incomingClipboardImage.size() == incomingClipboardImageSize) {
            byte[] png = incomingClipboardImage.toByteArray();
            long seq = incomingClipboardImageSeq;
            String traceId = incomingClipboardImageTrace;
            String sha = incomingClipboardImageSha;
            clearClipboardImage();
            async.execute(() -> {
                boolean ok = settings.clipboardSync && applyClipboardImage.apply(png, sha);
                if (trace != null) trace.onResult(seq, traceId, "clip-image-start", ok ? "applied" : "rejected");
            });
        }
    }

    private void finishClipboardImage(String result) {
        long seq = incomingClipboardImageSeq;
        String traceId = incomingClipboardImageTrace;
        clearClipboardImage();
        if (trace != null) trace.onResult(seq, traceId, "clip-image-start", result);
    }

    private void clearClipboardImage() {
        incomingClipboardImage = null;
        incomingClipboardImageSize = 0;
        incomingClipboardImageSeq = 0;
        incomingClipboardImageTrace = null;
        incomingClipboardImageSha = null;
    }

    /**
     * Envia um comando ao operador pelo canal de controle WebRTC. Retorna falso quando a sessao
     * ainda nao tem canal aberto, para o chamador poder usar o relay do modo compativel sem perder
     * uma notificacao de clipboard durante a negociacao.
     */
    boolean sendCtl(Map<String, Object> msg) {
        RTCDataChannel c = ctl;
        if (c == null || closed || c.getState() != dev.onvoid.webrtc.RTCDataChannelState.OPEN) {
            return false;
        }
        try {
            c.send(new RTCDataChannelBuffer(ByteBuffer.wrap(Json.stringify(msg).getBytes(StandardCharsets.UTF_8)), false));
            return true;
        } catch (Exception e) {
            log.accept("Falha ao enviar ao operador: " + e.getMessage());
            return false;
        }
    }

    private String shortId() {
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }
}
