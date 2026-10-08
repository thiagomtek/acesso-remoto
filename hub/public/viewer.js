// Visualizador de tela remota: recebe o video por WebRTC (P2P, ou via TURN se a rede exigir) e envia
// mouse/teclado por canais de dados. A sinalizacao passa pelo hub; a midia nao.

const clamp01 = (v) => Math.max(0, Math.min(1, v));
// Somente operacoes que precisam de confirmacao funcional recebem trace. Teclado e mouse seguem
// direto pelo transporte, sem telemetria, persistencia local ou log por comando.
const CONFIRMED_INPUT_EVENTS = new Set(['clip-image', 'clip-image-start']);
const TRACE_OUTBOX_KEY = 'remoteInputTraceOutbox.v1';

import { buildSettingsPanel } from './settings.js';
import { mountOrb } from './orb.js';

export class Viewer {
  /** @param {{send:(m:object)=>void, isAdmin:()=>boolean, onClosed:()=>void}} opts  send = mensagem ao hub */
  constructor({ send, isAdmin, onClosed }) {
    this.sendHub = send;
    this.isAdmin = isAdmin || (() => false);
    this.client = null;
    this.clientId = null;
    this.keyChain = Promise.resolve(); // teclas e colar saem na ordem (o colar le a area de transferencia antes)
    this.onClosed = onClosed;
    this.pc = null;
    this.ctl = null;
    this.mouse = null;
    this.sessionId = null;
    this.pendingCandidates = [];
    this.remoteSet = false;
    this.latest = null; // ultima posicao do mouse ainda nao enviada
    this.lastMouseSent = 0;
    this.statsTimer = null;
    this.prev = null;
    // Toque (celular/tablet): cursor virtual que o dedo ARRASTA (nunca pula pro ponto tocado), com
    // tap = clique esquerdo e toque de 2 dedos = clique direito - ver onTouchStart/Move/End.
    this.touch = { active: false, cursor: { x: 0.5, y: 0.5 }, pointers: new Map(), gesture: null };
    this.pressedCodes = new Set(); // codigos (kd) ja enviados e ainda sem o ku correspondente
    this.pendingDownloads = [];
    this.pendingUploads = new Set();
    this.filePasteGeneration = 0;
    this.suppressFilePasteKeyupUntil = 0;
    this.compatProbe = null;
    this.compatPingMs = null;
    this.compatJitterMs = null;
    this.compatPreviousPingMs = null;
    this.compatProbeSequence = 0;
    this.traceSequence = 0;
    try { window.localStorage?.removeItem(TRACE_OUTBOX_KEY); } catch { /* armazenamento indisponivel */ }
    this.traceResults = new Map();
    this.inputWaiters = new Map();
    this.clipboardImageV1 = false;
    this.build();
  }

  build() {
    const el = document.createElement('div');
    el.id = 'viewer';
    el.hidden = true;
    el.innerHTML = `
      <div class="vstage" tabindex="0"><video autoplay playsinline muted></video><canvas hidden></canvas><div class="vmsg">Aguardando vídeo…</div><textarea class="vtext-capture" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" aria-label="Entrada de texto da sessão remota"></textarea></div>
      <div class="vhud" aria-hidden="true"><span class="vstats"></span></div>
      <div class="vcursor" hidden aria-hidden="true">
        <svg viewBox="0 0 24 24" width="26" height="26"><path d="M4 2 4 20 9 15 12.5 22 15 20.5 11.5 14 18 14Z" fill="#fff" stroke="#000" stroke-width="1.3" stroke-linejoin="round"/></svg>
      </div>
      <div class="vkeyboard" hidden>
        <input class="vkb-input" type="text" inputmode="text" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder="Digite para enviar à máquina remota…">
        <button class="vkb-close" type="button" aria-label="Fechar teclado">✕</button>
      </div>
      <input class="vshared-file" type="file" multiple hidden>
      <input class="vshared-folder" type="file" webkitdirectory directory multiple hidden>
      <button class="vorb" type="button" title="Menu (arraste para mover)" aria-label="Menu do acesso remoto" aria-expanded="false" data-state="wait">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>
      </button>
      <div class="vmenu" hidden>
        <div class="vmenu-head">
          <div class="vtitle"></div>
          <div class="vstate">conectando…</div>
          <span class="vbadge" hidden>somente visualização</span>
        </div>
        <button data-act="cfg" title="Configurações desta máquina (aplicadas na hora)">Configurações</button>
        <button data-act="keyboard" title="Abrir o teclado para digitar (útil no celular)">Teclado</button>
        <button data-act="shared" title="Gerenciar os arquivos sincronizados com recebimentos">Pasta compartilhada</button>
        <button data-act="compat" title="Usar o modo compatível (sem WebRTC)">Modo compatível</button>
        <button data-act="stats" title="Mostrar/ocultar as estatísticas na tela">Estatísticas</button>
        <button data-act="fs" title="Tela cheia">Tela cheia</button>
        <button data-act="close" class="danger">Encerrar acesso</button>
      </div>
      <div class="vsettings" hidden></div>
      <div class="vshared" hidden role="dialog" aria-modal="true" aria-label="Pasta compartilhada">
        <div class="vshared-head"><div><strong>Recebimentos</strong><span>Pasta compartilhada com esta máquina</span></div><button type="button" data-shared-close aria-label="Fechar">✕</button></div>
        <div class="vshared-toolbar">
          <button type="button" class="vshared-back" data-shared-back title="Voltar" aria-label="Voltar">‹</button>
          <nav class="vshared-breadcrumb" aria-label="Caminho da pasta"></nav>
          <div class="vshared-actions">
            <button type="button" data-shared-download hidden>Baixar</button>
            <button type="button" data-shared-delete class="danger" hidden>Excluir</button>
            <button type="button" data-shared-add>+ Arquivos</button>
            <button type="button" data-shared-add-folder>+ Pasta</button>
          </div>
        </div>
        <div class="vshared-columns" aria-hidden="true"><span>Nome</span><span>Tipo</span><span>Tamanho</span><span>Modificado</span></div>
        <div class="vshared-list" role="listbox" aria-live="polite"></div>
        <div class="vshared-status"></div>
      </div>
      <div class="vtoast" hidden></div>`;
    document.body.append(el);
    this.el = el;
    this.stage = el.querySelector('.vstage');
    this.textCapture = el.querySelector('.vtext-capture');
    this.video = el.querySelector('video');
    this.canvas = el.querySelector('canvas');
    this.cctx = this.canvas.getContext('2d');
    this.mode = 'rtc';
    this.msg = el.querySelector('.vmsg');
    this.stateEl = el.querySelector('.vstate');
    this.badge = el.querySelector('.vbadge');
    this.toastEl = el.querySelector('.vtoast');
    this.settingsEl = el.querySelector('.vsettings');
    this.statsEl = el.querySelector('.vstats');
    this.hudEl = el.querySelector('.vhud');
    this.orbEl = el.querySelector('.vorb');
    this.menuEl = el.querySelector('.vmenu');
    this.cursorEl = el.querySelector('.vcursor');
    this.kbEl = el.querySelector('.vkeyboard');
    this.kbInput = el.querySelector('.vkb-input');
    this.sharedFileInput = el.querySelector('.vshared-file');
    this.sharedFolderInput = el.querySelector('.vshared-folder');
    this.sharedEl = el.querySelector('.vshared');
    this.sharedList = el.querySelector('.vshared-list');
    this.sharedBreadcrumb = el.querySelector('.vshared-breadcrumb');
    this.sharedBack = el.querySelector('[data-shared-back]');
    this.sharedDownload = el.querySelector('[data-shared-download]');
    this.sharedDelete = el.querySelector('[data-shared-delete]');
    this.sharedStatus = el.querySelector('.vshared-status');
    const store = (() => { try { return window.localStorage; } catch { return null; } })();
    // Bolinha flutuante (no lugar da barra fixa); ao fechar o menu, fecha tambem os paineis "filhos"
    // que ela abre (Configuracoes, teclado na tela) - senao eles ficam flutuando sozinhos na tela sem
    // a bolinha que os abriu - e o foco volta para a tela remota.
    this.orb = mountOrb(this.orbEl, this.menuEl, {
      storage: store,
      onClose: () => {
        this.settingsEl.hidden = true;
        this.closeSharedFolder();
        this.toggleKeyboard(false);
        this.focusRemoteInput();
      },
    });
    const act = (name, fn) => { el.querySelector('[data-act=' + name + ']').onclick = () => { this.orb.close(); fn(); }; };
    // cfg/keyboard abrem um painel "filho" que o orb.close() acima acabou de forcar fechado (ver
    // onClose) - decide ANTES de fechar o menu se a intencao era abrir ou fechar, e aplica depois.
    el.querySelector('[data-act=cfg]').onclick = () => {
      const willOpen = this.settingsEl.hidden;
      this.orb.close();
      this.settingsEl.hidden = !willOpen;
      if (willOpen) this.renderSettings(true);
    };
    act('close', () => this.close(true));
    act('fs', () => this.toggleFullscreen());
    act('compat', () => this.requestCompat());
    el.querySelector('[data-act=keyboard]').onclick = () => {
      const willOpen = this.kbEl.hidden;
      this.orb.close();
      this.toggleKeyboard(willOpen);
    };
    act('shared', () => this.openSharedFolder());
    this.sharedFileInput.onchange = () => { this.uploadSharedFiles([...this.sharedFileInput.files], false); this.sharedFileInput.value = ''; };
    this.sharedFolderInput.onchange = () => { this.uploadSharedFiles([...this.sharedFolderInput.files], true); this.sharedFolderInput.value = ''; };
    el.querySelector('[data-shared-close]').onclick = () => this.closeSharedFolder();
    el.querySelector('[data-shared-add]').onclick = () => this.sharedFileInput.click();
    el.querySelector('[data-shared-add-folder]').onclick = () => this.sharedFolderInput.click();
    this.sharedBack.onclick = () => this.navigateSharedParent();
    this.sharedDownload.onclick = () => this.downloadSharedSelection();
    this.sharedDelete.onclick = () => this.deleteSharedSelection();
    this.kbEl.querySelector('.vkb-close').onclick = () => this.toggleKeyboard(false);
    this.wireKeyboardInput();
    // Estatisticas translucidas na tela (estilo indicador de FPS de jogos); preferencia lembrada
    const hudOn = () => { try { return store?.getItem('viewerHud') !== '0'; } catch { return true; } };
    this.hudEl.hidden = !hudOn();
    act('stats', () => {
      this.hudEl.hidden = !this.hudEl.hidden;
      try { store?.setItem('viewerHud', this.hudEl.hidden ? '0' : '1'); } catch { /* sem storage */ }
    });
    // Cor da bolinha acompanha o estado da conexao (verde / ambar / vermelho)
    const syncState = () => {
      const t = (this.stateEl.textContent || '').toLowerCase();
      this.orbEl.dataset.state = /falhou|erro|encerrad/.test(t) ? 'bad' : /conectado/.test(t) ? 'ok' : 'wait';
    };
    new MutationObserver(syncState).observe(this.stateEl, { childList: true, characterData: true, subtree: true });
    // Clicar na tela remota fecha o menu
    el.querySelector('.vstage').addEventListener('pointerdown', () => { if (this.orb.isOpen) this.orb.close(); }, true);

    this.video.addEventListener('loadedmetadata', () => { this.msg.hidden = true; this.reportViewport(); });
    new ResizeObserver(() => this.reportViewport()).observe(this.stage);
    document.addEventListener('fullscreenchange', () => {
      if (!document.fullscreenElement) navigator.keyboard?.unlock?.();
      this.reportViewport();
    });

    // Mouse (pointerType 'mouse'/'pen'): clique direto no ponto tocado, como ja era.
    // Toque (pointerType 'touch'): vira cursor virtual arrastavel - ver onTouchStart/Move/End.
    const s = this.stage;
    s.addEventListener('pointerdown', (e) => {
      if (e.pointerType !== 'touch') this.focusRemoteInput();
      if (e.pointerType === 'touch') { this.onTouchStart(e); return; }
      try { s.setPointerCapture(e.pointerId); } catch { /* navegadores/testes sem Pointer Capture */ }
      const p = this.norm(e);
      if (p) this.sendCtl({ t: 'md', b: e.button, x: p.x, y: p.y });
      e.preventDefault();
    });
    s.addEventListener('pointermove', (e) => {
      if (e.pointerType === 'touch') { this.onTouchMove(e); return; }
      this.latest = this.norm(e);
    });
    s.addEventListener('pointerup', (e) => {
      if (e.pointerType === 'touch') { this.onTouchEnd(e); return; }
      const p = this.norm(e);
      if (p) this.sendCtl({ t: 'mu', b: e.button, x: p.x, y: p.y });
      e.preventDefault();
    });
    s.addEventListener('pointercancel', (e) => { if (e.pointerType === 'touch') this.onTouchEnd(e, true); });
    s.addEventListener('contextmenu', (e) => e.preventDefault());
    // Safari pode iniciar selecao/arrasto nativos mesmo quando o contextmenu foi bloqueado.
    // O campo invisivel precisa continuar editavel para receber acentos e composicao.
    s.addEventListener('selectstart', (e) => { if (e.target !== this.textCapture) e.preventDefault(); });
    s.addEventListener('dragstart', (e) => e.preventDefault());
    s.addEventListener('mousedown', (e) => { if (e.button === 2) e.preventDefault(); });
    s.addEventListener('dragover', (e) => e.preventDefault());
    s.addEventListener('drop', (e) => {
      e.preventDefault();
      this.uploadFiles([...e.dataTransfer.files]);
    });
    s.addEventListener('wheel', (e) => {
      e.preventDefault();
      const notches = Math.max(1, Math.round(Math.abs(e.deltaY) / 100));
      this.sendCtl({ t: 'mw', d: Math.sign(e.deltaY) * notches });
    }, { passive: false });

    // Um elemento editavel recebe a composicao final do macOS/Windows (dead keys, Option e IME).
    // Um DIV focado recebe keydown, mas em Safari/Chrome pode nunca receber o texto composto.
    this.composing = false;
    this.compositionCommitted = false;
    this.textCapture.addEventListener('compositionstart', () => {
      this.composing = true;
      this.compositionCommitted = false;
    });
    this.textCapture.addEventListener('compositionend', (e) => {
      this.composing = false;
      const text = e.data;
      // Alguns navegadores emitem o input final antes, outros depois de compositionend.
      setTimeout(() => {
        if (!this.compositionCommitted && text && this.ready() && !this.el.hidden) this.sendRemoteText(text);
        this.textCapture.value = '';
      }, 0);
    });
    this.textCapture.addEventListener('input', (e) => {
      if (e.isComposing || this.composing) return;
      if ((!e.inputType || e.inputType.startsWith('insert')) && e.data) {
        this.compositionCommitted = true;
        this.sendRemoteText(e.data);
      }
      this.textCapture.value = '';
    });

    // Teclado: tudo vai para a maquina remota (inclusive Tab, F5, etc.), nao para o navegador.
    this.keyHandler = (e) => {
      if (this.el.hidden || !this.ready()) return;
      const down = e.type === 'keydown';
      const mac = this.operatorPlatform === 'mac' || /Mac|iPhone|iPad/.test(navigator.platform || '');
      // No Mac o Cmd faz o papel do Ctrl (Cmd+C/V/A...): na maquina remota vira Ctrl.
      let code = e.code;
      if (mac && (code === 'MetaLeft' || code === 'MetaRight')) {
        code = 'ControlLeft';
      }

      if (!down) {
        // O ClipboardEvent confirmou que este Ctrl/Cmd+V continha arquivo. Nem pressiona nem solta V
        // na maquina remota, para ela nao colar um conteudo antigo do proprio clipboard.
        if (e.code === 'KeyV' && performance.now() < this.suppressFilePasteKeyupUntil) {
          this.pressedCodes.delete(code);
          e.preventDefault();
          e.stopPropagation();
          return;
        }
        if (mac && (e.code === 'AltLeft' || e.code === 'AltRight') && this.deferredAltCode === e.code) {
          this.deferredAltCode = null;
          return;
        }
        // keyup: solta SEMPRE uma tecla que enviamos como pressionada, mesmo que o foco tenha saido
        // do stage nesse meio tempo (ex.: clicou no menu antes de soltar o Ctrl) - senao ela fica
        // "travada" (down) na maquina remota ate o usuario apertar Esc. So o keydown respeita o foco.
        if (this.pressedCodes.has(code)) {
          this.pressedCodes.delete(code);
          e.preventDefault();
          e.stopPropagation();
          this.queueKey(() => this.sendCtl({ t: 'ku', c: code }));
        }
        return;
      }

      if (this.settingsEl.contains(document.activeElement) || this.menuEl.contains(document.activeElement) || this.orbEl === document.activeElement || this.kbEl.contains(document.activeElement)) return;
      if (!(document.activeElement === this.stage || document.activeElement === this.textCapture || document.fullscreenElement)) return;

      const captureText = document.activeElement === this.textCapture;
      const altGraph = e.getModifierState?.('AltGraph') === true;
      if (captureText && e.key === 'AltGraph') {
        // No ABNT2, AltGr pode aparecer como Ctrl+Alt sinteticos. Nao deixe esses modificadores
        // ativos no Windows remoto enquanto o navegador entrega o caractere final em `input`.
        for (const modifier of ['ControlLeft', 'ControlRight']) {
          if (this.pressedCodes.delete(modifier)) this.queueKey(() => this.sendCtl({ t: 'ku', c: modifier }));
        }
        e.stopPropagation();
        return;
      }
      const mayInsert = !e.metaKey && (!e.ctrlKey || altGraph) &&
        ((!e.altKey || mac || altGraph) && (this.isTextKey(e, true) || e.key === 'Dead' || e.key === 'Process' || e.isComposing));
      if (captureText && mayInsert) {
        // Permite que o SO forme o caractere no textarea; o evento input envia apenas o texto final.
        // Option+dead key no Mac e AltGr no Windows passam por este caminho.
        e.stopPropagation();
        return;
      }
      if (mac && (e.code === 'AltLeft' || e.code === 'AltRight')) {
        // Option pode ser modificador de texto no Mac. So envia Alt ao Windows quando a proxima
        // tecla comprovar que a intencao era um atalho (Option+Tab/F4/seta etc.).
        this.deferredAltCode = e.code;
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      if (this.deferredAltCode) {
        const alt = this.deferredAltCode;
        this.deferredAltCode = null;
        this.pressedCodes.add(alt);
        this.queueKey(() => this.sendCtl({ t: 'kd', c: alt }));
      }
      e.preventDefault();
      e.stopPropagation();

      // Texto e tecla fisica sao contratos diferentes. `key` ja representa o texto composto pelo
      // teclado do operador (inclusive emoji/surrogate pair); ele vai como Unicode. `code` fica
      // reservado para atalhos, navegacao e modificadores, onde a posicao fisica importa.
      const printable = this.isTextKey(e);
      if (printable) {
        // Mantem `kt` para que agentes ainda atualizando entendam texto BMP. O agente novo aceita
        // a string inteira (inclusive surrogate pair), sem trocar o envelope em uma atualizacao
        // parcial do hub/agent.
        this.sendRemoteText(e.key);
        return;
      }
      if (e.key === 'Dead' || e.key === 'Process' || e.isComposing) {
        // Uma dead key nao e texto final. Envia-la como tecla fisica mudaria o estado do layout
        // remoto; esperamos o proximo evento composto, que chega como Unicode.
        return;
      }
      this.pressedCodes.add(code);
      const isPaste = e.code === 'KeyV' && (e.ctrlKey || e.metaKey);
      // Chama JA (fora do queueKey) para preservar a "user activation" exigida pelo Safari no
      // clipboard.readText() - encadeado numa Promise do queueKey, o navegador pode rejeitar por
      // nao ser mais chamado "direto" do gesto do usuario.
      const pasteGeneration = this.filePasteGeneration;
      const clipPromise = isPaste ? this.readLocalClipboard() : null;
      this.queueKey(async () => {
        if (clipPromise) {
          const clip = await clipPromise;
          // `paste` entrega arquivos pelo ClipboardEvent depois do keydown. Espera apenas o fim do
          // gesto para nao mandar Ctrl+V nem texto residual para o Windows quando a intencao era arquivo.
          await new Promise((resolve) => setTimeout(resolve, 0));
          if (this.filePasteGeneration !== pasteGeneration) return;
          if (clip.error) this.toast('Não foi possível ler a área de transferência local (permita o acesso ao colar)');
          else if (clip.text) this.sendCtl({ t: 'clip', text: clip.text });
        }
        this.sendCtl({ t: 'kd', c: code });
      });
    };
    window.addEventListener('keydown', this.keyHandler, true);
    window.addEventListener('keyup', this.keyHandler, true);
    this.pasteHandler = (e) => {
      if (this.el.hidden || !this.ready() || this.client?.settings?.clipboardSync === false) return;
      const files = [...(e.clipboardData?.files || [])].filter((file) => file?.size >= 0);
      // Safari pode expor a captura apenas em DataTransfer.items, enquanto Chromium normalmente
      // tambem preenche files. Consulte os dois sem duplicar o mesmo objeto.
      for (const item of [...(e.clipboardData?.items || [])]) {
        const file = item?.kind === 'file' ? item.getAsFile?.() : null;
        if (file && file.size >= 0 && !files.includes(file)) files.push(file);
      }
      if (!files.length) return;
      this.filePasteGeneration++;
      this.suppressFilePasteKeyupUntil = performance.now() + 1_000;
      e.preventDefault();
      e.stopPropagation();
      const image = files.find((file) => file.type === 'image/png');
      if (image && this.clipboardImageV1) {
        void this.sendClipboardImage(image, true);
      } else {
        this.uploadFiles(files, true); // agente antigo: preserva o comportamento anterior
      }
    };
    window.addEventListener('paste', this.pasteHandler, true);

    // Rede de seguranca: Alt+Tab/Cmd+Tab saindo do navegador (ou a aba ficar oculta) nao gera o
    // keyup correspondente aqui (o SO entrega o evento pro app que ganhou o foco, nao pra pagina) -
    // sem isso, modificadores como Ctrl ficam fisicamente "pressionados" na maquina remota.
    this.releaseAllKeys = () => {
      this.deferredAltCode = null;
      if (this.pressedCodes.size === 0) return;
      for (const code of this.pressedCodes) this.sendCtl({ t: 'ku', c: code });
      this.pressedCodes.clear();
    };
    window.addEventListener('blur', this.releaseAllKeys);
    document.addEventListener('visibilitychange', () => { if (document.hidden) this.releaseAllKeys(); });

    // So a posicao mais recente do mouse, numa taxa maxima (descarta as intermediarias).
    const tick = () => {
      const now = performance.now();
      if (this.latest && this.mode === 'compat' && this.sessionId && now - this.lastMouseSent >= 16) {
        this.sendHub({ type: 'cmd', sessionId: this.sessionId, data: { t: 'mm', x: this.latest.x, y: this.latest.y } });
        this.latest = null;
        this.lastMouseSent = now;
      } else if (this.latest && this.mode === 'rtc' && this.mouse?.readyState === 'open' && now - this.lastMouseSent >= 8) {
        this.mouse.send(`${this.latest.x.toFixed(5)},${this.latest.y.toFixed(5)}`);
        this.latest = null;
        this.lastMouseSent = now;
      }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }

  /** Area (em pixels de tela) onde a imagem remota realmente aparece, descontando as faixas pretas (letterbox). */
  imageRect() {
    const compat = this.mode === 'compat';
    const v = compat ? this.canvas : this.video;
    const nw = compat ? this.canvas.width : v.videoWidth;
    const nh = compat ? this.canvas.height : v.videoHeight;
    if (!nw || !nh) return null;
    const r = v.getBoundingClientRect();
    const scale = Math.min(r.width / nw, r.height / nh);
    const w = nw * scale;
    const h = nh * scale;
    return { left: r.left + (r.width - w) / 2, top: r.top + (r.height - h) / 2, width: w, height: h };
  }

  /** Posicao normalizada (0..1) de um evento sobre a imagem real. */
  norm(e) {
    const r = this.imageRect();
    if (!r) return null;
    return { x: clamp01((e.clientX - r.left) / r.width), y: clamp01((e.clientY - r.top) / r.height) };
  }

  /** Posicao em pixels de tela correspondente a uma posicao normalizada (inverso de norm()). */
  toScreen(nx, ny) {
    const r = this.imageRect();
    return r ? { x: r.left + nx * r.width, y: r.top + ny * r.height } : { x: 0, y: 0 };
  }

  reportViewport() {
    const r = this.stage.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.sendCtl({ t: 'vp', w: Math.round(r.width * dpr), h: Math.round(r.height * dpr) });
    if (this.touch.active) this.placeCursor(); // a tela mudou de tamanho (rotacao, tela cheia...): reposiciona
  }

  // ---------- toque: cursor virtual arrastavel (celular/tablet) ----------

  /** Mostra o cursor virtual (uma vez por sessao) e o posiciona onde estava. */
  showCursor() {
    if (this.touch.active) return;
    this.touch.active = true;
    this.cursorEl.hidden = false;
    this.placeCursor();
  }

  placeCursor() {
    const p = this.toScreen(this.touch.cursor.x, this.touch.cursor.y);
    this.cursorEl.style.left = `${p.x}px`;
    this.cursorEl.style.top = `${p.y}px`;
  }

  moveCursorTo(nx, ny) {
    this.touch.cursor = { x: nx, y: ny };
    this.latest = { x: nx, y: ny }; // reaproveita a fila ja existente (tick() envia no canal certo)
    this.placeCursor();
  }

  /** Media das posicoes Y dos dedos de uma lista de ids (para o gesto de rolagem com 2 dedos). */
  midY(ids) {
    const pts = ids.map((id) => this.touch.pointers.get(id)).filter(Boolean);
    return pts.length ? pts.reduce((sum, p) => sum + p.y, 0) / pts.length : 0;
  }

  onTouchStart(e) {
    e.preventDefault();
    try { this.stage.setPointerCapture(e.pointerId); } catch { /* sem suporte: segue sem capturar */ }
    this.showCursor();
    this.touch.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY, moved: false, hadSibling: false });
    if (this.touch.pointers.size > 1) {
      for (const p of this.touch.pointers.values()) p.hadSibling = true; // nenhum deles pode virar "tap" sozinho
    }
    if (this.touch.pointers.size === 2 && !this.touch.gesture) {
      const ids = [...this.touch.pointers.keys()];
      this.touch.gesture = { ids, moved: false, fired: false, lastMidY: this.midY(ids) };
    }
  }

  onTouchMove(e) {
    const p = this.touch.pointers.get(e.pointerId);
    if (!p) return;
    e.preventDefault();
    const dx = e.clientX - p.x;
    const dy = e.clientY - p.y;
    p.x = e.clientX;
    p.y = e.clientY;
    if (Math.hypot(e.clientX - p.startX, e.clientY - p.startY) > 6) p.moved = true;

    if (this.touch.pointers.size === 1 && !this.touch.gesture) {
      // Arrasto de 1 dedo: o cursor anda pelo DESLOCAMENTO do dedo, nunca pula pro ponto tocado
      // (e o que deixava dificil clicar certo e o sistema "nao entender" o toque como clique).
      const r = this.imageRect();
      if (r && r.width && r.height) {
        const sensitivity = 1.6;
        this.moveCursorTo(
          clamp01(this.touch.cursor.x + (dx * sensitivity) / r.width),
          clamp01(this.touch.cursor.y + (dy * sensitivity) / r.height),
        );
      }
    } else if (this.touch.gesture && this.touch.gesture.ids.includes(e.pointerId)) {
      // 2 dedos se movendo juntos para cima/baixo: rola a tela remota em vez de abrir o menu de contexto.
      const midY = this.midY(this.touch.gesture.ids);
      const d = midY - this.touch.gesture.lastMidY;
      this.touch.gesture.lastMidY = midY;
      if (Math.abs(d) > 3) {
        this.touch.gesture.moved = true;
        this.sendCtl({ t: 'mw', d: -Math.sign(d) * Math.max(1, Math.round(Math.abs(d) / 40)) });
      }
    }
  }

  onTouchEnd(e, cancelled = false) {
    const p = this.touch.pointers.get(e.pointerId);
    if (!p) return;
    this.touch.pointers.delete(e.pointerId);
    const gesture = this.touch.gesture;
    if (gesture && gesture.ids.includes(e.pointerId)) {
      // Toque com 2 dedos, sem arrastar = clique direito (menu de contexto) na posicao do cursor.
      if (!gesture.moved && !gesture.fired && !cancelled) {
        gesture.fired = true;
        this.clickAt(this.touch.cursor.x, this.touch.cursor.y, 2);
      }
      if (this.touch.pointers.size === 0) this.touch.gesture = null;
      return;
    }
    // Toque de 1 dedo, sem arrastar (e sem ter tido um 2o dedo junto) = clique esquerdo na posicao do cursor.
    if (!p.moved && !p.hadSibling && !cancelled) {
      this.clickAt(this.touch.cursor.x, this.touch.cursor.y, 0);
    }
  }

  /** Clique sintetico (toque) na posicao do cursor virtual, nao no ponto onde o dedo tocou. */
  clickAt(nx, ny, button) {
    this.sendCtl({ t: 'md', b: button, x: nx, y: ny });
    setTimeout(() => this.sendCtl({ t: 'mu', b: button, x: nx, y: ny }), 40);
  }

  // ---------- teclado na tela (celular) ----------

  toggleKeyboard(force) {
    const show = force !== undefined ? force : this.kbEl.hidden;
    this.kbEl.hidden = !show;
    if (show) {
      this.kbInput.value = '';
      this.kbInput.focus();
    } else {
      this.kbInput.blur();
    }
  }

  wireKeyboardInput() {
    const sendChars = (text) => { if (text) this.sendCtl({ t: 'kt', ch: text }); };
    const tap = (code) => { this.sendCtl({ t: 'kd', c: code }); this.sendCtl({ t: 'ku', c: code }); };
    this.kbInput.addEventListener('keydown', (e) => {
      // O teclado do celular normalmente nao dispara keydown para letras/numeros (fica por conta do
      // evento "input" abaixo); Enter e Backspace costumam disparar os dois - evita duplicar.
      if (e.key === 'Enter') {
        e.preventDefault();
        tap('Enter');
        this.kbInput.value = '';
      } else if (e.key === 'Backspace' && !this.kbInput.value) {
        e.preventDefault();
        tap('Backspace');
      }
    });
    this.kbInput.addEventListener('input', (e) => {
      const type = e.inputType || '';
      if (type.startsWith('insert') && e.data) {
        sendChars(e.data);
        this.kbInput.value = '';
      } else if (type === 'insertLineBreak') {
        tap('Enter');
        this.kbInput.value = '';
      } else if (type === 'deleteContentBackward') {
        tap('Backspace');
      } else if (this.kbInput.value) {
        // Entrada que nao reconhecemos pelo tipo (ex: autocorrecao trocando a palavra): manda tudo e limpa.
        sendChars(this.kbInput.value);
        this.kbInput.value = '';
      }
    });
  }

  queueKey(fn) {
    this.keyChain = this.keyChain.then(fn).catch(() => {});
  }

  /** Envia o texto da area de transferencia local (se a configuracao da maquina permite). */
  async sendLocalClipboard() {
    const result = await this.readLocalClipboard();
    if (result.error) {
      this.toast('Não foi possível ler a área de transferência local (permita o acesso ao colar)');
      return;
    }
    if (result.text) this.sendCtl({ t: 'clip', text: result.text });
  }

  /** Le o texto ainda dentro do gesto do usuario; o envio fica a cargo do chamador. */
  async readLocalClipboard() {
    if (this.client?.settings?.clipboardSync === false || !this.sessionId) return { text: '' };
    try {
      const text = await navigator.clipboard.readText();
      // Usa o mesmo canal confiavel/ordenado dos atalhos. No WebRTC, mandar o texto pelo hub e
      // o Ctrl+V pelo data channel parecia correto no navegador, mas os dois transportes podem
      // chegar invertidos no Windows. No modo compativel sendCtl continua passando pelo hub.
      return { text: typeof text === 'string' ? text : '' };
    } catch {
      return { text: '', error: true };
    }
  }

  /** Texto copiado na maquina remota: vai para a area de transferencia local. */
  async onClip(text) {
    if (typeof text !== 'string' || this.client?.settings?.clipboardSync === false) return;
    try {
      await navigator.clipboard.writeText(text);
      this.toast('Área de transferência recebida da máquina remota');
    } catch {
      this.toast('Texto copiado na máquina remota (permita o acesso à área de transferência)');
    }
  }

  /** Detecta a plataforma localmente para mapear atalhos, sem enviar telemetria ao hub. */
  reportInputEnvironment() {
    const ua = navigator.userAgent || '';
    const platformText = `${navigator.userAgentData?.platform || ''} ${navigator.platform || ''} ${ua}`;
    const platform = /Mac|iPhone|iPad/i.test(platformText) ? 'mac'
      : /Win/i.test(platformText) ? 'windows'
        : /Android/i.test(platformText) ? 'android'
          : /Linux/i.test(platformText) ? 'linux' : 'other';
    this.operatorPlatform = platform;
  }

  /** Retorna texto confirmado pelo navegador, nunca nomes de teclas de controle. */
  isTextKey(e, allowModifiers = false) {
    if ((!allowModifiers && (e.ctrlKey || e.altKey || e.metaKey)) || !e.key || e.key === 'Dead' || e.key === 'Process' || e.key === 'Unidentified') return false;
    return !new Set(['Alt', 'AltGraph', 'CapsLock', 'ContextMenu', 'Control', 'Enter', 'Escape', 'Meta', 'NumLock', 'Pause', 'ScrollLock', 'Shift', 'Tab', 'Backspace', 'Delete', 'Insert', 'Home', 'End', 'PageUp', 'PageDown', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'PrintScreen']).has(e.key) && !/^F(?:[1-9]|1[0-9]|2[0-4])$/.test(e.key);
  }

  focusRemoteInput() {
    if (this.textCapture && !this.kbEl.hidden) return;
    (this.textCapture || this.stage).focus();
  }

  sendRemoteText(text) {
    if (!text || !this.ready() || this.el.hidden) return;
    this.queueKey(() => this.sendCtl({ t: 'kt', ch: text }));
  }

  /** Arquivos do operador vao pelo hub autenticado, sem depender do clipboard que navegadores restringem. */
  async uploadFiles(files, fromClipboard = false) {
    if (!this.sessionId || !files?.length) return;
    if (fromClipboard && this.client?.settings?.clipboardSync === false) {
      this.toast('Sincronização da área de transferência está desativada nesta máquina');
      return;
    }
    for (const file of files) {
      if (file.size > 512 * 1024 * 1024) { this.toast('Arquivo maior que 512 MB não foi enviado'); continue; }
      try {
        this.toast('Enviando arquivo para recebimentos da máquina…');
        const r = await fetch('/transfers/upload', {
          method: 'POST', credentials: 'same-origin', body: file,
          headers: { 'x-transfer-session': this.sessionId, 'x-transfer-name': encodeURIComponent(file.name) },
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const receipt = await r.json();
        if (receipt?.id) this.pendingUploads.add(receipt.id);
        this.toast('Arquivo enviado ao hub; aguardando confirmação da máquina…');
      } catch {
        this.toast('Não foi possível enviar o arquivo à máquina');
      }
    }
  }

  /** Imagem copiada: WebRTC envia direto; compatibilidade usa blob temporario autenticado no hub. */
  async sendClipboardImage(blob, pasteAfter = false) {
    if (!this.sessionId || !this.clipboardImageV1 || blob?.type !== 'image/png') return false;
    if (blob.size < 1 || blob.size > 25 * 1024 * 1024) {
      this.toast('A imagem copiada excede o limite de 25 MB');
      return false;
    }
    try {
      this.toast('Enviando imagem para a área de transferência remota…');
      const bytes = new Uint8Array(await blob.arrayBuffer());
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      const sha256 = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
      let trace;
      if (this.mode === 'compat') {
        const upload = await fetch('/transfers/upload', {
          method: 'POST', credentials: 'same-origin', body: blob,
          headers: { 'x-transfer-session': this.sessionId, 'x-transfer-name': 'clipboard.png', 'x-transfer-kind': 'clipboard-image' },
        });
        if (!upload.ok) throw new Error('upload');
        const receipt = await upload.json();
        trace = this.sendCtl({ t: 'clip-image', transferId: receipt.id, size: bytes.length, sha256 });
      } else {
        trace = this.sendCtl({ t: 'clip-image-start', size: bytes.length, sha256 });
        if (typeof trace !== 'string') throw new Error('channel');
        for (let offset = 0; offset < bytes.length; offset += 16 * 1024) {
          while (this.ctl?.bufferedAmount > 1024 * 1024) await new Promise((resolve) => setTimeout(resolve, 10));
          if (this.ctl?.readyState !== 'open') throw new Error('channel');
          const chunk = bytes.slice(offset, Math.min(bytes.length, offset + 16 * 1024));
          this.ctl.send(chunk.buffer);
        }
      }
      const result = await this.waitInputResult(trace, 30_000);
      if (result !== 'applied') throw new Error(result || 'timeout');
      this.toast('Imagem recebida pela área de transferência da máquina');
      if (pasteAfter) this.pasteRemoteClipboard();
      return true;
    } catch {
      this.toast('Não foi possível enviar a imagem para a área de transferência remota');
      return false;
    }
  }

  pasteRemoteClipboard() {
    const macRemote = /mac/i.test(this.client?.info?.os || '');
    const modifier = macRemote ? 'MetaLeft' : 'ControlLeft';
    this.queueKey(() => this.sendCtl({ t: 'kd', c: modifier }));
    this.queueKey(() => this.sendCtl({ t: 'kd', c: 'KeyV' }));
    this.queueKey(() => this.sendCtl({ t: 'ku', c: 'KeyV' }));
    this.queueKey(() => this.sendCtl({ t: 'ku', c: modifier }));
  }

  /** Arquivo copiado no Windows: fica disponivel para download, para o usuario salvar no Desktop do Mac. */
  onFileReady({ transferId, name, size }) {
    if (typeof transferId !== 'string') return;
    this.pendingDownloads.push({ transferId, name: typeof name === 'string' ? name : 'arquivo', size: Number(size) || 0 });
    // Compatibilidade com agentes antigos: como o botao avulso foi substituido pela pasta
    // compartilhada, inicia o download legado imediatamente em vez de deixar um arquivo inacessivel.
    this.downloadReadyFiles();
    this.toast('Arquivo da máquina enviado ao navegador');
  }

  /** Confirmacao fim-a-fim: o agente gravou (ou nao conseguiu gravar) em recebimentos. */
  onFileStatus({ transferId, delivered }) {
    if (typeof transferId !== 'string') return;
    this.pendingUploads.delete(transferId);
    this.toast(delivered ? 'Arquivo recebido em recebimentos da máquina' : 'A máquina não conseguiu gravar o arquivo; veja o diagnóstico');
  }

  downloadReadyFiles() {
    const items = this.pendingDownloads.splice(0);
    for (const item of items) {
      const a = document.createElement('a');
      a.href = `/transfers/${encodeURIComponent(item.transferId)}`;
      a.download = item.name;
      a.click();
    }
  }

  toast(text) {
    this.toastEl.textContent = text;
    this.toastEl.hidden = false;
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => { this.toastEl.hidden = true; }, 2500);
    this.toastTimer.unref?.();
  }

  /** A maquina (ou suas configuracoes) mudou: atualiza o painel e o selo na hora. */
  setClient(client) {
    this.client = client || null;
    this.badge.hidden = !client || client.settings.allowRemoteControl !== false;
    this.orbEl.classList.toggle('viewonly', !this.badge.hidden);
    this.renderSettings(false);
  }

  /** Rota agente→hub, confirmada pelo proprio agente; nao e inferida por DNS ou por ping. */
  hubRouteLabel() {
    const route = this.client?.stats?.hubRoute || this.client?.info?.hubRoute;
    if (route === 'lan') return 'LAN direta';
    if (route === 'cloud') return 'Internet · Cloudflare';
    return 'rota do agente: medindo…';
  }

  renderSettings(force) {
    if (this.settingsEl.hidden || !this.client) return;
    // Nao reconstroi enquanto o operador esta mexendo num campo do painel.
    if (!force && this.settingsEl.contains(document.activeElement)) return;
    const title = Object.assign(document.createElement('div'), { className: 'vsettings-title', textContent: 'Configurações — ' + this.client.name });
    this.settingsEl.replaceChildren(title, buildSettingsPanel(this.client, { admin: this.isAdmin(), send: this.sendHub }));
  }

  closeSharedFolder() {
    this.sharedEl.hidden = true;
    clearInterval(this.sharedRefreshTimer);
    this.sharedRefreshTimer = null;
    this.sharedSelected = null;
  }

  async openSharedFolder() {
    if (!this.client) return;
    this.settingsEl.hidden = true;
    this.sharedEl.hidden = false;
    this.sharedCurrentPath = '';
    this.sharedSelected = null;
    await this.refreshSharedFolder();
    clearInterval(this.sharedRefreshTimer);
    this.sharedRefreshTimer = setInterval(() => { if (!this.sharedEl.hidden) this.refreshSharedFolder(true); }, 3000);
  }

  async refreshSharedFolder(silent = false) {
    if (!this.client) return;
    if (!silent) this.sharedList.replaceChildren(Object.assign(document.createElement('div'), { className: 'sub', textContent: 'Carregando arquivos…' }));
    try {
      const r = await fetch(`/shared/manifest?clientId=${encodeURIComponent(this.client.id)}`, { credentials: 'same-origin', cache: 'no-store' });
      if (!r.ok) throw new Error();
      const { files } = await r.json();
      this.sharedFiles = files || [];
      this.renderSharedFolder();
    } catch { this.sharedList.replaceChildren(Object.assign(document.createElement('div'), { className: 'sub', textContent: 'Não foi possível carregar a pasta compartilhada.' })); }
  }

  renderSharedFolder() {
    const current = this.sharedCurrentPath || '';
    const prefix = current ? current + '/' : '';
    const folders = new Map(); const files = [];
    for (const raw of this.sharedFiles || []) {
      const path = String(raw.path || '');
      if (!path.startsWith(prefix)) continue;
      const rest = path.slice(prefix.length); const slash = rest.indexOf('/');
      if (slash >= 0) {
        const name = rest.slice(0, slash); const folderPath = prefix + name;
        const folder = folders.get(folderPath) || { path: folderPath, name, folder: true, size: 0, mtime: 0 };
        folder.size += Number(raw.size) || 0; folder.mtime = Math.max(folder.mtime, Number(raw.mtime) || 0);
        folders.set(folderPath, folder);
      } else if (rest) files.push({ ...raw, path, name: rest, folder: false });
    }
    const byName = (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
    this.sharedVisibleItems = [...folders.values()].sort(byName).concat(files.sort(byName));
    if (this.sharedSelected && !this.sharedVisibleItems.some((item) => item.path === this.sharedSelected.path && item.folder === this.sharedSelected.folder)) this.sharedSelected = null;
    this.renderSharedBreadcrumb();
    this.sharedList.replaceChildren();
    if (!this.sharedVisibleItems.length) this.sharedList.append(Object.assign(document.createElement('div'), { className: 'vshared-empty', textContent: 'Esta pasta está vazia' }));
    for (const item of this.sharedVisibleItems) this.sharedList.append(this.sharedRow(item));
    const fileCount = this.sharedVisibleItems.filter((item) => !item.folder).length;
    const folderCount = this.sharedVisibleItems.length - fileCount;
    this.sharedStatus.textContent = `${folderCount} pasta${folderCount === 1 ? '' : 's'} · ${fileCount} arquivo${fileCount === 1 ? '' : 's'}`;
    this.syncSharedSelection();
  }

  renderSharedBreadcrumb() {
    const parts = (this.sharedCurrentPath || '').split('/').filter(Boolean);
    const buttons = [];
    const root = Object.assign(document.createElement('button'), { type: 'button', textContent: 'Recebimentos' });
    root.onclick = () => this.navigateShared(''); buttons.push(root);
    let path = '';
    for (const part of parts) {
      buttons.push(Object.assign(document.createElement('span'), { textContent: '›' }));
      path = path ? path + '/' + part : part;
      const target = path;
      const button = Object.assign(document.createElement('button'), { type: 'button', textContent: part });
      button.onclick = () => this.navigateShared(target); buttons.push(button);
    }
    this.sharedBreadcrumb.replaceChildren(...buttons);
    this.sharedBack.disabled = !parts.length;
  }

  sharedRow(item) {
    const row = document.createElement('div'); row.className = 'vshared-row'; row.tabIndex = 0; row.setAttribute('role', 'option');
    row.classList.toggle('folder', !!item.folder);
    row.dataset.path = item.path;
    const icon = document.createElement('span'); icon.className = 'vshared-icon';
    icon.innerHTML = item.folder
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6.5h7l2 2h9v10H3z"/><path d="M3 8.5h18"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v5h5"/></svg>';
    const label = Object.assign(document.createElement('span'), { className: 'vshared-name', textContent: item.name, title: item.name });
    const nameCell = document.createElement('div'); nameCell.className = 'vshared-name-cell'; nameCell.append(icon, label);
    const type = Object.assign(document.createElement('span'), { className: 'vshared-type', textContent: item.folder ? 'Pasta' : this.sharedFileType(item.name) });
    const size = Object.assign(document.createElement('span'), { className: 'vshared-size', textContent: item.folder ? '—' : this.sharedFormatSize(Number(item.size) || 0) });
    const modified = Object.assign(document.createElement('span'), { className: 'vshared-modified', textContent: item.mtime ? new Date(item.mtime).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' }) : '—' });
    row.append(nameCell, type, size, modified);
    row.onclick = () => this.selectShared(item);
    row.ondblclick = () => item.folder ? this.navigateShared(item.path) : this.downloadShared(item);
    row.onkeydown = (e) => {
      if (e.key === 'Enter') { e.preventDefault(); item.folder ? this.navigateShared(item.path) : this.downloadShared(item); }
      if (e.key === ' ') { e.preventDefault(); this.selectShared(item); }
    };
    return row;
  }

  navigateShared(path) { this.sharedCurrentPath = path || ''; this.sharedSelected = null; this.renderSharedFolder(); }
  navigateSharedParent() {
    const parts = (this.sharedCurrentPath || '').split('/').filter(Boolean); parts.pop(); this.navigateShared(parts.join('/'));
  }
  selectShared(item) { this.sharedSelected = item; this.syncSharedSelection(); }
  syncSharedSelection() {
    for (const row of this.sharedList.querySelectorAll('.vshared-row')) {
      const selected = !!this.sharedSelected && row.dataset.path === this.sharedSelected.path;
      row.classList.toggle('selected', selected); row.setAttribute('aria-selected', String(selected));
    }
    const selected = !!this.sharedSelected;
    this.sharedDownload.hidden = !selected; this.sharedDelete.hidden = !selected;
    if (selected) this.sharedDownload.textContent = this.sharedSelected.folder ? 'Baixar ZIP' : 'Baixar';
  }
  downloadSharedSelection() { if (this.sharedSelected) this.downloadShared(this.sharedSelected); }
  downloadShared(item) {
    const a = document.createElement('a'); const endpoint = item.folder ? 'archive' : 'file';
    a.href = `/shared/${endpoint}?clientId=${encodeURIComponent(this.client.id)}&path=${encodeURIComponent(item.path)}`;
    a.download = item.name + (item.folder ? '.zip' : ''); a.click();
  }
  async deleteSharedSelection() {
    const item = this.sharedSelected; if (!item) return;
    if (!confirm(`Excluir ${item.folder ? 'a pasta e todo o seu conteúdo' : 'o arquivo'} “${item.name}”?`)) return;
    const r = await fetch(`/shared/file?clientId=${encodeURIComponent(this.client.id)}&path=${encodeURIComponent(item.path)}`, { method: 'DELETE', credentials: 'same-origin' });
    if (!r.ok) this.toast(`Não foi possível excluir ${item.folder ? 'a pasta' : 'o arquivo'}`);
    this.sharedSelected = null; await this.refreshSharedFolder();
  }
  sharedFileType(name) { const dot = name.lastIndexOf('.'); return dot > 0 ? name.slice(dot + 1).toUpperCase() : 'Arquivo'; }
  sharedFormatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
  }

  async uploadSharedFiles(files, preserveFolders = false) {
    if (!this.client || !files?.length) return;
    const pending = files.map((file) => {
      const raw = preserveFolders ? (file.webkitRelativePath || file.name) : file.name;
      const relative = String(raw || '').replaceAll('\\', '/').split('/').filter(Boolean).join('/');
      const path = [this.sharedCurrentPath, relative].filter(Boolean).join('/');
      return { file, path };
    }).filter(({ path }) => path && !path.split('/').some((part) => part === '.' || part === '..' || /[<>:"|?*\x00-\x1f]/.test(part)));
    let cursor = 0; let completed = 0; let failed = files.length - pending.length;
    const worker = async () => {
      while (cursor < pending.length) {
        const { file, path } = pending[cursor++];
        if (file.size > 512 * 1024 * 1024) { failed++; continue; }
        this.toast(`Enviando ${++completed} de ${pending.length}…`);
        try {
          const r = await fetch(`/shared/file?clientId=${encodeURIComponent(this.client.id)}&path=${encodeURIComponent(path)}`, { method: 'PUT', credentials: 'same-origin', body: file });
          if (!r.ok) throw new Error();
        } catch { failed++; }
      }
    };
    await Promise.all(Array.from({ length: Math.min(3, pending.length) }, worker));
    if (failed) this.toast(`${failed} item(ns) não puderam ser enviados`);
    else this.toast(`${pending.length} item(ns) adicionados à pasta compartilhada`);
    await this.refreshSharedFolder();
  }

  /** Canal pronto para comandos: canal de dados (WebRTC) ou o proprio hub (modo compativel). */
  ready() {
    return this.mode === 'compat' ? !!this.sessionId : this.ctl?.readyState === 'open';
  }

  sendCtl(msg) {
    let traceId = null;
    if (CONFIRMED_INPUT_EVENTS.has(msg?.t) && this.sessionId && this.clientId) {
      const seq = ++this.traceSequence;
      const trace = `${this.sessionId}:${seq}`;
      traceId = trace;
      msg = { ...msg, q: seq, trace };
    }
    if (this.mode === 'compat') {
      if (this.sessionId) {
        const sent = this.sendHub({ type: 'cmd', sessionId: this.sessionId, data: msg });
        return traceId || sent;
      }
    } else if (this.ctl?.readyState === 'open') {
      this.ctl.send(JSON.stringify(msg));
      return traceId || true;
    }
    return false;
  }

  onTelemetryAck() {}

  onInputResult(data) {
    if (typeof data?.trace !== 'string') return;
    this.traceResults.set(data.trace, { result: data.result, at: Date.now() });
    const waiter = this.inputWaiters.get(data.trace);
    if (waiter) { this.inputWaiters.delete(data.trace); waiter(data.result); }
    if (this.traceResults.size > 500) this.traceResults.delete(this.traceResults.keys().next().value);
  }

  waitInputResult(trace, timeoutMs) {
    if (typeof trace !== 'string') return Promise.resolve('not-sent');
    const known = this.traceResults.get(trace);
    if (known) return Promise.resolve(known.result);
    return new Promise((resolve) => {
      const timer = setTimeout(() => { this.inputWaiters.delete(trace); resolve('timeout'); }, timeoutMs);
      timer.unref?.();
      this.inputWaiters.set(trace, (result) => { clearTimeout(timer); resolve(result); });
    });
  }

  recoverInputTraces() {
    try { window.localStorage?.removeItem(TRACE_OUTBOX_KEY); } catch { /* armazenamento indisponivel */ }
  }

  /** Pede ao agente para abandonar o WebRTC e usar o modo compativel (Java puro). */
  requestCompat() {
    if (!this.sessionId || this.mode === 'compat') return;
    this.msg.textContent = 'Mudando para o modo compatível…';
    this.msg.hidden = false;
    this.sendHub({ type: 'cmd', sessionId: this.sessionId, data: { t: 'use-compat' } });
  }

  /** O agente trocou de modo: para o WebRTC e passa a desenhar os blocos recebidos pelo hub. */
  switchToCompat(reason) {
    if (this.mode === 'compat') return;
    this.mode = 'compat';
    clearInterval(this.statsTimer);
    try { this.ctl?.close(); this.mouse?.close(); this.pc?.close(); } catch { /* ja fechado */ }
    this.ctl = this.mouse = this.pc = null;
    this.video.srcObject = null;
    this.video.hidden = true;
    this.canvas.hidden = false;
    this.drawChain = Promise.resolve();
    this.rxBytes = 0;
    this.rxFrames = 0;
    this.prevRx = { bytes: 0, frames: 0, t: performance.now() };
    this.compatProbe = null;
    this.compatPingMs = null;
    this.compatJitterMs = null;
    this.compatPreviousPingMs = null;
    this.stateEl.textContent = 'conectado · modo compatível';
    this.msg.textContent = 'Modo compatível: ' + (reason || 'WebRTC indisponível') + '. Aguardando imagem…';
    this.msg.hidden = false;
    clearTimeout(this.watch);
    clearInterval(this.statsTimer);
    this.statsTimer = setInterval(() => {
      const now = performance.now();
      const kbs = ((this.rxBytes - this.prevRx.bytes) / 1024) / ((now - this.prevRx.t) / 1000);
      const fps = (this.rxFrames - this.prevRx.frames) / ((now - this.prevRx.t) / 1000);
      this.prevRx = { bytes: this.rxBytes, frames: this.rxFrames, t: now };
      this.probeCompatLatency();
      const ping = this.compatPingMs == null ? 'medindo…' : `${Math.round(this.compatPingMs)} ms`;
      const lines = [
        'modo compatível',
        this.hubRouteLabel(),
        `${this.canvas.width}×${this.canvas.height}`,
        `${kbs.toFixed(0)} KB/s`,
        `${fps.toFixed(0)} quadros/s`,
        `ping ${ping}`,
        `jitter ${jitter}`,
      ];
      this.statsEl.textContent = lines.join('\n');
    }, 1000);
    this.reportViewport();
  }

  /** Frames binarios do hub: [0x01][sessionId 16 bytes] + mensagens do protocolo de blocos (big-endian). */
  onBinary(buf) {
    if (this.mode !== 'compat' || buf.byteLength < 18) return;
    this.rxBytes += buf.byteLength;
    this.rxFrames++;
    const dv = new DataView(buf);
    let o = 17;
    while (o < buf.byteLength) {
      const t = dv.getUint8(o++);
      if (t === 12) { // tamanho nativo + escala do Windows (so informativo aqui)
        o += 16;
      } else if (t === 73) { // tamanho transmitido agora: redimensiona a area de desenho
        const w = dv.getInt32(o);
        const h = dv.getInt32(o + 4);
        o += 8;
        if (w > 0 && h > 0 && (this.canvas.width !== w || this.canvas.height !== h)) {
          this.canvas.width = w;
          this.canvas.height = h;
        }
      } else if (t === 16) { // CopyRect: copia uma regiao ja desenhada (rolagem/arraste), sem reenviar pixels
        const [sx, sy, w, h, dx, dy] = [0, 4, 8, 12, 16, 20].map((k) => dv.getInt32(o + k));
        o += 24;
        this.drawChain = this.drawChain.then(() => {
          const tmp = document.createElement('canvas');
          tmp.width = w;
          tmp.height = h;
          tmp.getContext('2d').drawImage(this.canvas, sx, sy, w, h, 0, 0, w, h);
          this.cctx.drawImage(tmp, dx, dy);
        });
      } else if (t === 17) { // fim do quadro: confirma ao agente depois de desenhar tudo (controle de fluxo)
        const n = dv.getInt32(o);
        o += 4;
        this.drawChain = this.drawChain.then(() => this.sendHub({ type: 'cmd', sessionId: this.sessionId, data: { t: 'ack', n } }));
      } else if (t === 14 || t === 15) { // bloco PNG (sem perdas) ou JPEG (em movimento) da regiao que mudou
        const x = dv.getInt32(o);
        const y = dv.getInt32(o + 4);
        const len = dv.getInt32(o + 16);
        const png = buf.slice(o + 20, o + 20 + len);
        o += 20 + len;
        // Decodifica em paralelo, mas desenha na ordem em que chegaram.
        const decoded = createImageBitmap(new Blob([png], { type: t === 15 ? 'image/jpeg' : 'image/png' }));
        this.drawChain = this.drawChain
          .then(() => decoded)
          .then((bmp) => { this.cctx.drawImage(bmp, x, y); bmp.close(); this.msg.hidden = true; })
          .catch(() => {});
      } else {
        break; // mensagem desconhecida: descarta o resto do frame
      }
    }
  }

  /** Sonda ponta-a-ponta no modo compatível: operador → hub → agente → hub → operador. */
  probeCompatLatency() {
    if (this.mode !== 'compat' || !this.sessionId || this.compatProbe) return;
    const n = ++this.compatProbeSequence;
    const started = performance.now();
    this.compatProbe = { n, started };
    this.sendHub({ type: 'cmd', sessionId: this.sessionId, data: { t: 'latency-ping', n } });
    const timeout = setTimeout(() => {
      if (this.compatProbe?.n === n) this.compatProbe = null;
    }, 5_000);
    // Nos testes Node, um timeout de sonda nao deve manter o processo vivo.
    timeout.unref?.();
  }

  /** Resposta da sonda do agente. Jitter e a variacao suavizada entre RTTs consecutivos. */
  onCompatPong(data) {
    const probe = this.compatProbe;
    if (this.mode !== 'compat' || !probe || !Number.isInteger(data?.n) || data.n !== probe.n) return;
    this.compatProbe = null;
    const ping = Math.max(0, performance.now() - probe.started);
    if (this.compatPreviousPingMs != null) {
      const variation = Math.abs(ping - this.compatPreviousPingMs);
      this.compatJitterMs = this.compatJitterMs == null ? variation : this.compatJitterMs + (variation - this.compatJitterMs) / 16;
    } else {
      this.compatJitterMs = 0;
    }
    this.compatPreviousPingMs = ping;
    this.compatPingMs = ping;
  }

  async toggleFullscreen() {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
      return;
    }
    await this.el.requestFullscreen();
    // Em tela cheia o Chromium deixa capturar Alt+Tab, Esc, teclas de sistema etc.
    try { await navigator.keyboard?.lock?.(); } catch { /* sem suporte */ }
    this.focusRemoteInput();
  }

  // ---------- sessao ----------

  open({ sessionId, clientId, name, iceServers }) {
    this.close(false);
    this.sessionId = sessionId;
    this.clientId = clientId || null;
    this.recoverInputTraces();
    this.reportInputEnvironment();
    this.mode = 'rtc';
    this.clipboardImageV1 = false;
    this.video.hidden = false;
    this.canvas.hidden = true;
    this.remoteSet = false;
    this.pendingCandidates = [];
    this.orb.close();
    this.toggleKeyboard(false);
    this.touch = { active: false, cursor: { x: 0.5, y: 0.5 }, pointers: new Map(), gesture: null };
    this.cursorEl.hidden = true;
    this.el.querySelector('.vtitle').textContent = name;
    this.stateEl.textContent = 'conectando…';
    this.statsEl.textContent = '';
    this.msg.textContent = 'Pedindo vídeo à máquina remota…';
    this.msg.hidden = false;
    this.gotOffer = false;
    clearTimeout(this.watch);
    this.watch = setTimeout(() => this.diagnose(), 15000);
    this.el.hidden = false;
    document.body.classList.add('viewing');

    const pc = new RTCPeerConnection({ iceServers });
    this.pc = pc;
    pc.onicecandidate = (e) => {
      if (e.candidate) {
        this.sendHub({ type: 'rtc', sessionId, data: { type: 'candidate', candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex } });
      }
    };
    pc.ontrack = (e) => {
      this.video.srcObject = e.streams[0] || new MediaStream([e.track]);
      // Sem atraso extra de reproducao: prioriza latencia (a rede ja e adaptada pelo WebRTC).
      try { e.receiver.playoutDelayHint = 0; } catch { /* nao suportado */ }
      try { e.receiver.jitterBufferTarget = 0; } catch { /* nao suportado */ }
      this.video.play().catch(() => {});
    };
    pc.ondatachannel = (e) => {
      const ch = e.channel;
      if (ch.label === 'ctl') {
        this.ctl = ch;
        ch.onopen = () => this.reportViewport();
        ch.onmessage = (m) => this.onCtl(JSON.parse(m.data));
      } else if (ch.label === 'mouse') {
        this.mouse = ch;
      }
    };
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'checking') this.msg.textContent = 'Negociando a rota de rede com a máquina remota…';
    };
    pc.onconnectionstatechange = () => {
      const s = pc.connectionState;
      this.stateEl.textContent = { connected: 'conectado', connecting: 'conectando…', disconnected: 'instável…', failed: 'falhou', closed: 'encerrado' }[s] || s;
      if (s === 'connected') this.startStats();
      if (s === 'failed') this.msg.textContent = 'Não foi possível conectar. Verifique a rede da máquina remota.', this.msg.hidden = false;
    };
  }

  async onRtc(data) {
    if (data.type === 'clip') { // area de transferencia da maquina remota (vale nos dois modos)
      await this.onClip(data.text);
      return;
    }
    const pc = this.pc;
    if (!pc) return;
    if (data.type === 'mode' && data.mode === 'compat') {
      this.clipboardImageV1 = data.clipboardImageV1 === true;
      this.switchToCompat(data.reason);
      return;
    }
    if (this.mode === 'compat') return;
    if (data.type === 'error') {
      this.msg.textContent = data.message || 'Erro na máquina remota.';
      this.msg.hidden = false;
      this.stateEl.textContent = 'erro';
      return;
    }
    if (data.type === 'offer') {
      this.gotOffer = true;
      this.msg.textContent = 'Negociando a rota de rede com a máquina remota…';
      await pc.setRemoteDescription({ type: 'offer', sdp: data.sdp });
      this.remoteSet = true;
      for (const c of this.pendingCandidates) await pc.addIceCandidate(c).catch(() => {});
      this.pendingCandidates = [];
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      this.sendHub({ type: 'rtc', sessionId: this.sessionId, data: { type: 'answer', sdp: answer.sdp } });
    } else if (data.type === 'candidate') {
      const c = { candidate: data.candidate, sdpMid: data.sdpMid, sdpMLineIndex: data.sdpMLineIndex };
      if (this.remoteSet) await pc.addIceCandidate(c).catch(() => {});
      else this.pendingCandidates.push(c);
    }
  }

  /** Passou muito tempo sem conectar: diz em que etapa travou. */
  diagnose() {
    if (!this.pc || this.pc.connectionState === 'connected') return;
    if (!this.gotOffer) {
      this.msg.textContent = 'A máquina remota não respondeu ao pedido. O agente pode estar com problema (veja o agent.log dela em %USERPROFILE%\.transacao-agent).';
    } else {
      this.msg.textContent = `Não foi possível abrir a rota de rede (ICE: ${this.pc.iceConnectionState}). Provável firewall/NAT bloqueando UDP ou o servidor TURN inacessível.`;
    }
    this.msg.textContent += ' Você pode tentar o botão "Modo compatível" acima.';
    this.msg.hidden = false;
  }

  async onCtl(m) {
    if (m.t === 'clip') { // WebRTC: area de transferencia chega pelo mesmo canal da sessao
      await this.onClip(m.text);
      return;
    }
    if (m.t === 'screen') {
      this.remoteScreen = m;
      this.clipboardImageV1 = m.clipboardImageV1 === true;
      if (!m.control) this.msg.textContent = 'Controle remoto desativado nesta máquina (somente visualização).';
    }
  }

  // ---------- estatisticas ----------

  startStats() {
    clearInterval(this.statsTimer);
    this.statsTimer = setInterval(async () => {
      if (!this.pc) return;
      const report = await this.pc.getStats();
      this.updateRtcStats(report);
    }, 1000);
  }

  /** Renderiza as estatisticas WebRTC. RTT e jitter sao segundos no getStats e viram ms no HUD. */
  updateRtcStats(report, now = performance.now()) {
    let inbound; let pair; const codecs = {};
    report.forEach((r) => {
      if (r.type === 'inbound-rtp' && r.kind === 'video') inbound = r;
      if (r.type === 'candidate-pair' && (r.nominated || r.selected) && r.state === 'succeeded') pair = r;
      if (r.type === 'codec') codecs[r.id] = r;
    });
    if (!inbound) return;
    let mbps = 0;
    if (this.prev) mbps = ((inbound.bytesReceived - this.prev.bytes) * 8) / ((now - this.prev.t) / 1000) / 1e6;
    this.prev = { bytes: inbound.bytesReceived, t: now };
    let path = '';
    if (pair) {
      const local = report.get(pair.localCandidateId);
      const remote = report.get(pair.remoteCandidateId);
      const relay = local?.candidateType === 'relay' || remote?.candidateType === 'relay';
      path = relay ? 'via TURN' : 'direto';
    }
    const codec = (codecs[inbound.codecId]?.mimeType || '').replace('video/', '');
    const ms = (seconds) => Number.isFinite(seconds) ? `${Math.round(seconds * 1000)} ms` : 'n/d';
    const ping = ms(pair?.currentRoundTripTime);
    // `jitter` e a variacao de chegada dos pacotes de video, medida pelo receptor WebRTC.
    const jitter = ms(inbound.jitter);
    const lost = inbound.packetsLost ? `perda ${inbound.packetsLost}` : null;
    const lines = [
      this.hubRouteLabel(),
      codec,
      `${inbound.frameWidth || '?'}×${inbound.frameHeight || '?'}`,
      `${Math.round(inbound.framesPerSecond || 0)} fps`,
      `${mbps.toFixed(1)} Mbps`,
      `ping ${ping}`,
      `jitter ${jitter}`,
      path,
      lost,
    ].filter(Boolean);
    this.statsEl.textContent = lines.join('\n');
  }

  close(notify) {
    clearInterval(this.statsTimer);
    clearTimeout(this.watch);
    // Fecha cada tecla antes de encerrar o canal. Isso cobre encerrar a sessao no meio de Ctrl,
    // Shift ou Alt e complementa a protecao de blur/visibilitychange.
    this.releaseAllKeys?.();
    this.pressedCodes.clear();
    this.deferredAltCode = null;
    this.composing = false;
    this.compositionCommitted = false;
    this.textCapture.value = '';
    this.pendingUploads.clear();
    for (const resolve of this.inputWaiters.values()) resolve('session-closed');
    this.inputWaiters.clear();
    this.clientId = null;
    this.client = null;
    this.settingsEl.hidden = true;
    this.closeSharedFolder();
    this.kbEl.hidden = true;
    this.prev = null;
    if (notify && this.sessionId) this.sendHub({ type: 'session-close', sessionId: this.sessionId });
    try { this.ctl?.close(); this.mouse?.close(); this.pc?.close(); } catch { /* ja fechado */ }
    this.pc = this.ctl = this.mouse = null;
    this.sessionId = null;
    this.video.srcObject = null;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (!this.el.hidden) {
      this.el.hidden = true;
      document.body.classList.remove('viewing');
      this.onClosed?.();
    }
  }
}
