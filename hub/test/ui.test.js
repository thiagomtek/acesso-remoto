import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

// Testa o painel de configuracoes e o modo compativel do visualizador sem navegador nem tela (jsdom).
const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true });
const { window } = dom;
const ctxCalls = [];
const fakeCtx = new Proxy({}, { get: (_, name) => (...args) => { ctxCalls.push([String(name), args]); } });
window.HTMLCanvasElement.prototype.getContext = () => fakeCtx;
for (const k of ['window', 'document', 'HTMLElement', 'MutationObserver', 'Node', 'confirm']) {
  if (k === 'confirm') globalThis.confirm = () => true;
  else globalThis[k] = k === 'window' ? window : window[k] ?? globalThis[k];
}
globalThis.document = window.document;
globalThis.ResizeObserver = class { observe() {} disconnect() {} };
globalThis.requestAnimationFrame = () => 0;
globalThis.createImageBitmap = async () => ({ close() {} });
globalThis.Blob = window.Blob;
globalThis.RTCPeerConnection = class {
  constructor() { this.connectionState = 'new'; this.iceConnectionState = 'new'; }
  close() {}
  getStats() { return Promise.resolve(new Map()); }
};

const { buildSettingsPanel } = await import('../public/settings.js');
const { Viewer } = await import('../public/viewer.js');

const client = (over = {}) => ({
  id: 'c1', name: 'Recepcao', online: true, approved: true,
  settings: { allowRemoteControl: true, clipboardSync: true, keepAwake: false, teamsWatcher: false, startWithSystem: true, quality: 'auto', ...over },
});
const labelOf = (panel, text) => [...panel.querySelectorAll('label.opt')].find((l) => l.textContent.includes(text));
const stripTrace = ({ q, trace, ...command }) => command;

test('painel de configuracoes: reflete o estado e envia a mudanca de cada campo', () => {
  const sent = [];
  const panel = buildSettingsPanel(client({ keepAwake: true }), { admin: true, send: (m) => sent.push(m) });
  assert.equal(panel.querySelectorAll('input[type=checkbox]').length, 8);
  assert.equal(labelOf(panel, 'anti-suspensão').querySelector('input').checked, true);

  const lo = labelOf(panel, 'rede local').querySelector('input');
  lo.checked = true;
  lo.dispatchEvent(new window.Event('change'));
  assert.deepEqual(sent.at(-1), { type: 'update-client', clientId: 'c1', settings: { localOnly: true } });

  const tg = labelOf(panel, 'Avisar sobre atividade no Teams').querySelector('input');
  tg.checked = true;
  tg.dispatchEvent(new window.Event('change'));
  assert.deepEqual(sent.at(-1), { type: 'update-client', clientId: 'c1', settings: { teamsWatcher: true } });

  const q = panel.querySelector('select');
  q.value = 'economy';
  q.dispatchEvent(new window.Event('change'));
  assert.deepEqual(sent.at(-1).settings, { quality: 'economy' });

  assert.equal(panel.querySelector('input[type=text]'), null, 'nao ha mais campo de pasta de recebidos');
  assert.doesNotMatch(panel.textContent, /pasta compartilhada/i);
});

test('nao administrador ve as configuracoes mas nao altera', () => {
  const panel = buildSettingsPanel(client(), { admin: false, send: () => assert.fail('nao deveria enviar') });
  assert.ok([...panel.querySelectorAll('input,select')].every((e) => e.disabled));
  assert.match(panel.textContent, /Somente administradores/);
});

test('tela de visualizacao: painel de configuracoes abre, envia e atualiza ao vivo; selo de somente visualizacao', () => {
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's1', clientId: 'c1', name: 'Recepcao', iceServers: [] });
  v.setClient(client());
  const el = window.document.getElementById('viewer');
  const badge = el.querySelector('.vbadge');
  const panelEl = el.querySelector('.vsettings');
  assert.equal(badge.hidden, true);
  assert.equal(panelEl.hidden, true);

  el.querySelector('[data-act=cfg]').click(); // abre o painel
  assert.equal(panelEl.hidden, false);
  assert.match(panelEl.textContent, /Recepcao/);

  // mudar um campo na tela de visualizacao -> mensagem ao hub (que aplica na maquina na hora)
  const cb = labelOf(panelEl, 'Manter computador ativo').querySelector('input');
  cb.checked = true;
  cb.dispatchEvent(new window.Event('change'));
  assert.deepEqual(sent.at(-1), { type: 'update-client', clientId: 'c1', settings: { keepAwake: true } });

  // o hub transmite a mudanca (ex.: feita por outro operador): painel e selo acompanham em tempo real
  v.setClient(client({ keepAwake: true, allowRemoteControl: false }));
  assert.equal(labelOf(panelEl, 'Manter computador ativo').querySelector('input').checked, true);
  assert.equal(badge.hidden, false);
  assert.match(badge.textContent, /somente visualização/);

  // enquanto o operador edita um campo do painel, ele nao e reconstruido por baixo dele
  const sel = panelEl.querySelector('select');
  window.document.body.append(panelEl); // jsdom so atribui foco a elementos conectados
  sel.focus();
  const before = sel;
  v.setClient(client({ quality: 'max' }));
  assert.equal(panelEl.querySelector('select'), before, 'campo em edicao preservado');

  v.close(false);
  assert.equal(el.hidden, true);
});

test('bolinha: fechar o menu fecha os paineis filhos (configuracoes, teclado); o botao cfg continua alternando', () => {
  const v = new Viewer({ send: () => {}, isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's1b', clientId: 'c1', name: 'Recepcao', iceServers: [] });
  v.setClient(client());
  const el = v.el;
  const settingsEl = el.querySelector('.vsettings');
  const kbEl = el.querySelector('.vkeyboard');
  const stage = el.querySelector('.vstage');

  // Clicar em "cfg" abre as configuracoes; clicar de novo fecha (continua um toggle de verdade).
  el.querySelector('[data-act=cfg]').click();
  assert.equal(settingsEl.hidden, false);
  el.querySelector('[data-act=cfg]').click();
  assert.equal(settingsEl.hidden, true, 'segundo clique no cfg fecha de novo (nao reabre por causa do onClose)');

  // Abre de novo, depois fecha o MENU da bolinha de outro jeito (clicar na tela remota) - o painel
  // de configuracoes, que ficaria "orfao" flutuando sozinho, deve fechar junto.
  el.querySelector('[data-act=cfg]').click();
  assert.equal(settingsEl.hidden, false);
  // abre o menu da bolinha de novo (senao o clique na tela nao teria nada pra fechar)
  v.orb.open();
  stage.dispatchEvent(new window.Event('pointerdown', { bubbles: true }));
  assert.equal(settingsEl.hidden, true, 'fechar o menu da bolinha fecha o painel de configuracoes aberto por ele');

  // O mesmo vale pro teclado na tela.
  el.querySelector('[data-act=keyboard]').click();
  assert.equal(kbEl.hidden, false);
  v.orb.open();
  stage.dispatchEvent(new window.Event('pointerdown', { bubbles: true }));
  assert.equal(kbEl.hidden, true, 'fechar o menu da bolinha fecha o teclado na tela aberto por ele');

  v.close(false);
});

/** Monta um frame binario do modo compativel: [0x01][sessionId 16 bytes] + mensagens. */
const frame = (...parts) => {
  const body = Buffer.concat(parts);
  const buf = Buffer.concat([Buffer.from([1]), Buffer.alloc(16, 9), body]);
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
};
const i32 = (...n) => { const b = Buffer.alloc(4 * n.length); n.forEach((v, i) => b.writeInt32BE(v, 4 * i)); return b; };

test('modo compativel: tamanho do stream, CopyRect e ack do quadro (controle de fluxo)', async () => {
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's2', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  ctxCalls.length = 0;

  v.onBinary(frame(Buffer.from([73]), i32(800, 600))); // tamanho transmitido
  assert.equal(v.canvas.width, 800);
  assert.equal(v.canvas.height, 600);

  // CopyRect (16) + fim de quadro (17) com id 7
  v.onBinary(frame(Buffer.from([16]), i32(10, 100, 300, 200, 10, 140), Buffer.from([17]), i32(7)));
  await v.drawChain;
  const draws = ctxCalls.filter(([n]) => n === 'drawImage');
  assert.equal(draws.length, 2, 'copia a regiao para um canvas temporario e de la para o destino');
  assert.deepEqual(draws[1][1].slice(1), [10, 140], 'destino do CopyRect');
  assert.deepEqual(sent.at(-1), { type: 'cmd', sessionId: 's2', data: { t: 'ack', n: 7 } }, 'confirma o quadro depois de desenhar');

  // mensagem desconhecida descarta o resto do quadro sem quebrar
  v.onBinary(frame(Buffer.from([99, 1, 2, 3])));
  v.close(false);
});

test('modo compativel: comandos de mouse e teclado vao pelo hub (cmd)', () => {
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's3', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  assert.equal(v.ready(), true);
  v.sendCtl({ t: 'kt', ch: 'a' });
  assert.deepEqual(stripTrace(sent.at(-1).data), { t: 'kt', ch: 'a' });
  v.close(false);
});

test('teclado: texto Unicode, inclusive emoji, nao depende do layout remoto; dead key nao vira tecla fisica', async () => {
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's-text', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  v.stage.focus();

  const key = (key, code) => ({ type: 'keydown', key, code, ctrlKey: false, altKey: false, metaKey: false, isComposing: false, preventDefault() {}, stopPropagation() {} });
  v.keyHandler(key(',', 'Comma'));
  await v.keyChain;
  assert.deepEqual(stripTrace(sent.at(-1).data), { t: 'kt', ch: ',' });

  v.keyHandler(key('😀', 'KeyA'));
  await v.keyChain;
  assert.deepEqual(stripTrace(sent.at(-1).data), { t: 'kt', ch: '😀' });

  const commands = () => sent.filter((m) => m.type === 'cmd').length;
  const count = commands();
  v.keyHandler(key('Dead', 'Quote'));
  await v.keyChain;
  assert.equal(commands(), count, 'estado de dead key do teclado local nunca e reproduzido no layout remoto');
  v.close(false);
});

test('Mac US: Option e dead key geram texto composto uma vez, sem Alt remoto', async () => {
  Object.defineProperty(globalThis, 'navigator', { value: { platform: 'MacIntel', userAgent: 'Mozilla/5.0 (Macintosh) Version/18.0 Safari/605.1.15' }, configurable: true });
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 'mac-us', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  const environment = sent.find((m) => m.data?.event === 'input-environment')?.data;
  assert.equal(environment?.browser, 'safari');
  assert.equal(environment?.platform, 'mac');
  assert.equal(typeof environment?.input, 'boolean');
  assert.equal(typeof environment?.composition, 'boolean');
  assert.equal(typeof environment?.layoutMap, 'boolean');
  v.textCapture.focus();
  const key = (key, code, altKey) => v.textCapture.dispatchEvent(new window.KeyboardEvent('keydown', { key, code, altKey, bubbles: true, cancelable: true }));
  key('Alt', 'AltLeft', true);
  key('Dead', 'KeyE', true);
  v.textCapture.dispatchEvent(new window.CompositionEvent('compositionstart', { bubbles: true }));
  v.textCapture.dispatchEvent(new window.InputEvent('input', { inputType: 'insertCompositionText', data: 'e', isComposing: true, bubbles: true }));
  v.textCapture.dispatchEvent(new window.CompositionEvent('compositionend', { data: 'é', bubbles: true }));
  v.textCapture.dispatchEvent(new window.InputEvent('input', { inputType: 'insertText', data: 'é', bubbles: true }));
  await new Promise((resolve) => setTimeout(resolve, 1));
  await v.keyChain;
  assert.deepEqual(sent.filter((m) => m.type === 'cmd').map((m) => stripTrace(m.data)).filter((m) => m.t !== 'vp'), [{ t: 'kt', ch: 'é' }]);
  v.close(false);
});

test('entrada: identifica familias de navegador e plataforma sem depender de APIs exclusivas', async () => {
  const cases = [
    ['safari', 'mac', 'Mozilla/5.0 (Macintosh) Version/18.0 Safari/605.1.15'],
    ['chrome', 'windows', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129.0.0.0 Safari/537.36'],
    ['edge', 'windows', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0'],
    ['firefox', 'linux', 'Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0'],
    ['other', 'other', 'navegador sem identificacao'],
  ];
  for (const [browser, platform, userAgent] of cases) {
    Object.defineProperty(globalThis, 'navigator', { value: { platform: '', userAgent }, configurable: true });
    const sent = [];
    const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
    v.open({ sessionId: 'browser-profile', clientId: 'c1', name: 'X', iceServers: [] });
    assert.deepEqual(
      [sent.find((m) => m.data?.event === 'input-environment')?.data?.browser, v.operatorPlatform],
      [browser, platform],
    );
    v.close(false);
  }
});

test('Windows ABNT2: composicao e texto comum seguem Unicode; atalho Ctrl continua fisico', async () => {
  Object.defineProperty(globalThis, 'navigator', { value: { platform: 'Win32' }, configurable: true });
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 'win-abnt2', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  v.textCapture.focus();
  const key = (type, key, code, opts = {}) => v.textCapture.dispatchEvent(new window.KeyboardEvent(type, { key, code, bubbles: true, cancelable: true, ...opts }));
  key('keydown', 'Dead', 'BracketLeft');
  v.textCapture.dispatchEvent(new window.CompositionEvent('compositionstart', { bubbles: true }));
  v.textCapture.dispatchEvent(new window.CompositionEvent('compositionend', { data: 'ã', bubbles: true }));
  await new Promise((resolve) => setTimeout(resolve, 1));
  v.textCapture.dispatchEvent(new window.InputEvent('input', { inputType: 'insertText', data: 'ç', bubbles: true }));
  key('keydown', 'Control', 'ControlLeft', { ctrlKey: true });
  key('keydown', 'c', 'KeyC', { ctrlKey: true });
  key('keyup', 'c', 'KeyC', { ctrlKey: true });
  key('keyup', 'Control', 'ControlLeft');
  await v.keyChain;
  assert.deepEqual(sent.filter((m) => m.type === 'cmd').map((m) => stripTrace(m.data)).filter((m) => m.t !== 'vp'), [
    { t: 'kt', ch: 'ã' }, { t: 'kt', ch: 'ç' },
    { t: 'kd', c: 'ControlLeft' }, { t: 'kd', c: 'KeyC' }, { t: 'ku', c: 'KeyC' }, { t: 'ku', c: 'ControlLeft' },
  ]);
  v.close(false);
});

test('Windows ABNT2: AltGr produz texto sem deixar Ctrl remoto pressionado', async () => {
  Object.defineProperty(globalThis, 'navigator', { value: { platform: 'Win32' }, configurable: true });
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 'altgr', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  v.textCapture.focus();
  const key = (key, code, opts = {}) => v.textCapture.dispatchEvent(new window.KeyboardEvent('keydown', { key, code, bubbles: true, cancelable: true, ...opts }));
  key('Control', 'ControlLeft', { ctrlKey: true });
  key('AltGraph', 'AltRight', { ctrlKey: true, altKey: true });
  const altGraph = new window.KeyboardEvent('keydown', { key: '@', code: 'KeyQ', ctrlKey: true, altKey: true, bubbles: true });
  Object.defineProperty(altGraph, 'getModifierState', { value: (name) => name === 'AltGraph' });
  v.textCapture.dispatchEvent(altGraph);
  v.textCapture.dispatchEvent(new window.InputEvent('input', { inputType: 'insertText', data: '@', bubbles: true }));
  await v.keyChain;
  assert.deepEqual(sent.filter((m) => m.type === 'cmd').map((m) => stripTrace(m.data)).filter((m) => m.t !== 'vp'), [
    { t: 'kd', c: 'ControlLeft' }, { t: 'ku', c: 'ControlLeft' }, { t: 'kt', ch: '@' },
  ]);
  v.close(false);
});

// ---------- alerta do Teams (regras migradas do Servidor original) ----------
const { decide, TeamsAlert, SNOOZE_MINUTES } = await import('../public/teams-alert.js');

const fakeAudio = () => ({ ready: true, playing: false, start() { this.playing = true; return true; }, stop() { this.playing = false; }, unlock() { return true; } });
const mkAlert = (prefs = {}) => {
  const clock = { t: 1_000_000 };
  const logs = [];
  const audio = fakeAudio();
  const a = new TeamsAlert({ audio, now: () => clock.t, log: (m) => logs.push(m) });
  a.prefs = { enabled: true, idleCheck: true, awayMinutes: 5, ...prefs };
  return { a, clock, logs, audio };
};
const min = (n) => n * 60000;

test('teams: decisao pura (desativado, silenciado, sem verificar ausencia, presente, ausente)', () => {
  const base = { enabled: true, idleCheck: true, away: true, snoozedUntil: 0, now: 100 };
  assert.equal(decide({ ...base, enabled: false }).play, false);
  assert.equal(decide({ ...base, snoozedUntil: 100 + min(10) }).play, false);
  assert.equal(decide({ ...base, idleCheck: false, away: false }).play, true, 'sem verificar inatividade toca sempre');
  assert.equal(decide({ ...base, away: false }).play, false, 'presente: so registra no log');
  assert.equal(decide(base).play, true, 'ausente: toca');
});

test('teams: presente nao toca (so log); ausente toca; voltar a mexer para a sirene', () => {
  const { a, clock, logs, audio } = mkAlert();
  a.teamsEvent('m1', 'Recepcao', true);
  assert.equal(audio.playing, false);
  assert.ok(logs.some((l) => /presente - sem sirene/.test(l)));

  clock.t += min(6); // 6 min sem mexer: ausente (limite configurado: 5)
  a.teamsEvent('m2', 'Financeiro', true);
  assert.equal(audio.playing, true, 'ausente: toca a sirene');
  assert.equal(a.alertShown, true);

  a.input(); // voltou a mexer no mouse/teclado
  assert.equal(audio.playing, false, 'a sirene para quando o operador volta');
  assert.ok(logs.some((l) => /Sirene parada: voce voltou/.test(l)));
});

test('teams: atividade lida na maquina para a sirene; pausar e fechar a janela', () => {
  const { a, clock, audio, logs } = mkAlert();
  clock.t += min(10);
  a.teamsEvent('m1', 'Recepcao', true);
  assert.equal(audio.playing, true);
  a.teamsEvent('m1', 'Recepcao', false); // atividade lida no client
  assert.equal(audio.playing, false);
  assert.equal(a.alertShown, false);
  assert.ok(logs.some((l) => /atividade do Teams foi lida/.test(l)));

  a.teamsEvent('m1', 'Recepcao', true);
  assert.equal(audio.playing, true);
  a.pause();
  assert.equal(audio.playing, false);
  assert.equal(a.alertShown, false);
});

test(`teams: silenciar por ${SNOOZE_MINUTES} min suprime e depois volta a tocar`, () => {
  const { a, clock, audio } = mkAlert();
  clock.t += min(10);
  a.teamsEvent('m1', 'Recepcao', true);
  a.snooze();
  assert.equal(audio.playing, false);
  clock.t += min(10);
  a.teamsEvent('m2', 'Financeiro', true);
  assert.equal(audio.playing, false, 'dentro dos 30 min: silenciado');
  clock.t += min(25); // passou dos 30 min
  a.teamsEvent('m3', 'Estoque', true);
  assert.equal(audio.playing, true, 'apos o prazo volta a tocar');
});

test('teams: alerta desligado nao toca; sem verificar inatividade toca mesmo presente; IdleDetector prevalece', () => {
  let { a, audio } = mkAlert({ enabled: false });
  a.teamsEvent('m1', 'X', true);
  assert.equal(audio.playing, false);

  ({ a, audio } = mkAlert({ idleCheck: false }));
  a.teamsEvent('m1', 'X', true);
  assert.equal(audio.playing, true, 'presente, mas sem verificar inatividade');

  const t = mkAlert();
  t.clock.t += min(30); // sem input ha muito tempo...
  t.a.setIdleState('active'); // ...mas o sistema diz que o usuario esta ativo (IdleDetector)
  assert.equal(t.a.away, false);
  t.a.teamsEvent('m1', 'X', true);
  assert.equal(t.audio.playing, false);
  t.a.setIdleState('idle');
  assert.equal(t.a.away, true);
});

test('teams: som bloqueado pelo navegador gera aviso no log (sem quebrar)', () => {
  const { a, clock, audio, logs } = mkAlert();
  audio.ready = false;
  audio.start = () => false;
  clock.t += min(10);
  a.teamsEvent('m1', 'X', true);
  assert.ok(logs.some((l) => /Som bloqueado/.test(l)));
  assert.ok(logs.some((l) => /Sirene nao iniciou/.test(l)));
});

test('teams: interface monta o cartao, abre a janela de alerta e os botoes funcionam', async () => {
  const { mountTeamsUi } = await import('../public/teams-ui.js');
  window.document.body.innerHTML = '<main></main>';
  const { a, clock, audio } = mkAlert();
  a.log = () => {};
  const ui = mountTeamsUi(a, window.document.body);
  const modal = window.document.getElementById('teams-modal');
  assert.ok(window.document.querySelector('.teams-card'), 'cartao do alerta existe');
  assert.equal(modal.hidden, true);
  assert.match(window.document.getElementById('teams-status').textContent, /ativo/);

  clock.t += min(10); // operador ausente
  a.teamsEvent('m1', 'Recepcao', true);
  assert.equal(modal.hidden, false, 'janela de alerta aparece');
  assert.match(modal.textContent, /Recepcao/);
  assert.equal(audio.playing, true);

  window.document.getElementById('teams-snooze').click();
  assert.equal(modal.hidden, true);
  assert.equal(audio.playing, false);
  assert.match(window.document.getElementById('teams-status').textContent, /silenciado/);

  // opcoes persistem e refletem
  const away = window.document.getElementById('teams-away');
  away.value = '12';
  away.dispatchEvent(new window.Event('change'));
  assert.equal(a.prefs.awayMinutes, 12);
  const idle = window.document.getElementById('teams-idle');
  idle.checked = false;
  idle.dispatchEvent(new window.Event('change'));
  assert.equal(a.prefs.idleCheck, false);
  assert.equal(window.document.getElementById('teams-away').disabled, true);
  ui.render();
});

test('teams: botao de teste espera a liberacao assincrona do audio antes de tocar', async () => {
  const { mountTeamsUi } = await import('../public/teams-ui.js');
  window.document.body.innerHTML = '<main></main>';
  const audio = { ready: false, playing: false, async unlock() { this.ready = true; return true; }, start() { this.playing = this.ready; return this.playing; }, stop() { this.playing = false; } };
  const a = new TeamsAlert({ audio });
  mountTeamsUi(a, window.document.body);
  window.document.getElementById('teams-test').click();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(audio.playing, true);
});

test('area de transferencia: colar envia o texto local ANTES do Ctrl+V; Cmd vira Ctrl no Mac; texto remoto chega na area local', async () => {
  const written = [];
  Object.defineProperty(globalThis, 'navigator', {
    value: { platform: 'MacIntel', clipboard: { readText: async () => 'texto local', writeText: async (t) => { written.push(t); } } },
    configurable: true,
  });
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's9', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  v.setClient(client());
  v.stage.focus();
  const key = (type, code, extra = {}) =>
    window.dispatchEvent(new window.KeyboardEvent(type, { code, key: extra.key ?? code, bubbles: true, cancelable: true, ...extra }));
  key('keydown', 'MetaLeft', { key: 'Meta', metaKey: true });
  key('keydown', 'KeyV', { key: 'v', metaKey: true });
  key('keyup', 'KeyV', { key: 'v', metaKey: true });
  key('keyup', 'MetaLeft', { key: 'Meta' });
  await v.keyChain;
  const seq = sent.filter((m) => m.type === 'cmd' && m.data.t !== 'vp').map((m) => stripTrace(m.data));
  assert.deepEqual(seq, [
    { t: 'kd', c: 'ControlLeft' },
    { t: 'clip', text: 'texto local' },
    { t: 'kd', c: 'KeyV' },
    { t: 'ku', c: 'KeyV' },
    { t: 'ku', c: 'ControlLeft' },
  ]);

  // texto copiado na maquina remota -> area de transferencia local
  await v.onRtc({ type: 'clip', text: 'copiado la' });
  assert.deepEqual(written, ['copiado la']);

  // configuracao da maquina desliga a sincronizacao: nada entra nem sai
  v.setClient(client({ clipboardSync: false }));
  await v.onRtc({ type: 'clip', text: 'ignorado' });
  assert.deepEqual(written, ['copiado la']);
  sent.length = 0;
  key('keydown', 'KeyV', { key: 'v', ctrlKey: true });
  await v.keyChain;
  assert.equal(sent.some((m) => m.data?.t === 'clip'), false);
  v.close(false);
});

test('area de transferencia: no WebRTC, texto e Ctrl+V usam o mesmo canal ordenado', async () => {
  const written = [];
  Object.defineProperty(globalThis, 'navigator', {
    value: { platform: 'MacIntel', clipboard: { readText: async () => 'texto local', writeText: async (text) => written.push(text) } },
    configurable: true,
  });
  const hub = [];
  const control = [];
  const v = new Viewer({ send: (m) => hub.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's11', clientId: 'c1', name: 'X', iceServers: [] });
  v.setClient(client());
  v.ctl = { readyState: 'open', send: (text) => control.push(JSON.parse(text)) };
  v.stage.focus();
  const key = (type, code, extra = {}) =>
    window.dispatchEvent(new window.KeyboardEvent(type, { code, key: extra.key ?? code, bubbles: true, cancelable: true, ...extra }));
  key('keydown', 'MetaLeft', { key: 'Meta', metaKey: true });
  key('keydown', 'KeyV', { key: 'v', metaKey: true });
  key('keyup', 'KeyV', { key: 'v', metaKey: true });
  key('keyup', 'MetaLeft', { key: 'Meta' });
  await v.keyChain;
  assert.deepEqual(control.map(({ q, trace, ...command }) => command), [
    { t: 'kd', c: 'ControlLeft' },
    { t: 'clip', text: 'texto local' },
    { t: 'kd', c: 'KeyV' },
    { t: 'ku', c: 'KeyV' },
    { t: 'ku', c: 'ControlLeft' },
  ]);
  assert.equal(hub.some((m) => m.data?.t === 'clip'), false, 'clipboard nao pode correr pelo hub em paralelo');
  await v.onCtl({ t: 'clip', text: 'texto remoto' });
  assert.deepEqual(written, ['texto remoto'], 'clip recebido pelo data channel WebRTC vai para a area local');
  v.close(false);
});

test('pasta compartilhada substitui botoes avulsos e envia arquivos pelo modal', async () => {
  const calls = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url, opts });
    if (String(url).startsWith('/shared/manifest')) return { ok: true, json: async () => ({ files: [] }) };
    return { ok: true, status: 201, json: async () => ({ size: 3 }) };
  };
  const v = new Viewer({ send: () => {}, isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's-files', clientId: 'c1', name: 'X', iceServers: [] });
  v.setClient(client({ sharedFolderSync: true }));
  assert.equal(v.el.querySelector('[data-act=files]'), null);
  assert.equal(v.el.querySelector('[data-act=downloads]'), null);
  assert.ok(v.el.querySelector('[data-act=shared]'));
  await v.openSharedFolder();
  assert.equal(v.sharedEl.hidden, false);
  assert.ok(v.sharedFolderInput.hasAttribute('webkitdirectory'), 'seletor de pasta mantem estrutura no Safari/Chrome');
  const file = new window.File(['abc'], 'relatorio.txt', { type: 'text/plain' });
  await v.uploadSharedFiles([file]);
  const upload = calls.find((c) => c.opts.method === 'PUT');
  assert.equal(upload.url, '/shared/file?clientId=c1&path=relatorio.txt');
  assert.equal(upload.opts.body, file);
  const nested = new window.File(['x'], 'dados.txt', { type: 'text/plain' });
  Object.defineProperty(nested, 'webkitRelativePath', { value: 'Projeto/sub/dados.txt' });
  await v.uploadSharedFiles([nested], true);
  assert.ok(calls.some((c) => c.opts.method === 'PUT' && c.url === '/shared/file?clientId=c1&path=Projeto%2Fsub%2Fdados.txt'));
  v.sharedFiles = [
    { path: 'Projeto/sub/dados.txt', size: 12, mtime: 1000 },
    { path: 'Projeto/leia-me.md', size: 4, mtime: 2000 },
    { path: 'raiz.pdf', size: 20, mtime: 3000 },
  ];
  v.navigateShared('');
  assert.deepEqual([...v.sharedList.querySelectorAll('.vshared-name')].map((e) => e.textContent), ['Projeto', 'raiz.pdf'], 'raiz mostra somente filhos imediatos');
  const folder = v.sharedList.querySelector('.vshared-row.folder');
  folder.click();
  assert.equal(v.sharedDownload.hidden, false);
  assert.match(v.sharedDownload.textContent, /ZIP/);
  folder.dispatchEvent(new window.MouseEvent('dblclick', { bubbles: true }));
  assert.equal(v.sharedCurrentPath, 'Projeto');
  assert.deepEqual([...v.sharedList.querySelectorAll('.vshared-name')].map((e) => e.textContent), ['sub', 'leia-me.md']);
  assert.match(v.sharedBreadcrumb.textContent, /Recebimentos.*Projeto/);
  globalThis.fetch = previousFetch;
  v.close(false);
});

test('arquivos: Ctrl/Cmd+V de um arquivo usa o ClipboardEvent e nao cola V na maquina remota', async () => {
  const calls = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: true, status: 201, json: async () => ({ id: 'upload-1' }) };
  };
  const { v, sent } = openTouchViewer();
  v.setClient(client());
  Object.defineProperty(globalThis, 'navigator', { value: { platform: 'Win32', clipboard: { readText: async () => 'nao deve ir' } }, configurable: true });
  v.stage.focus();
  const key = (type, code, extra = {}) => window.dispatchEvent(new window.KeyboardEvent(type, {
    code, key: extra.key ?? code, bubbles: true, cancelable: true, ...extra,
  }));
  key('keydown', 'ControlLeft', { key: 'Control', ctrlKey: true });
  key('keydown', 'KeyV', { key: 'v', ctrlKey: true });
  const file = new window.File(['arquivo'], 'copiado.txt', { type: 'text/plain' });
  const paste = new window.Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(paste, 'clipboardData', { value: { files: [file] } });
  window.dispatchEvent(paste);
  key('keyup', 'KeyV', { key: 'v', ctrlKey: true });
  key('keyup', 'ControlLeft', { key: 'Control' });
  await sleep(0);
  await v.keyChain;
  assert.equal(paste.defaultPrevented, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.headers['x-transfer-name'], 'copiado.txt');
  assert.deepEqual(cmds(sent).filter((d) => d.t === 'kd' || d.t === 'ku').map(({ t, c }) => ({ t, c })), [
    { t: 'kd', c: 'ControlLeft' }, { t: 'ku', c: 'ControlLeft' },
  ], 'o V nao chega a maquina remota quando o ClipboardEvent confirmou um arquivo');
  globalThis.fetch = previousFetch;
  v.close(false);
});

test('clipboard de imagem: compatibilidade envia blob temporario e so cola depois da confirmacao do agente', async () => {
  const calls = [];
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: true, status: 201, json: async () => ({ id: '11111111-1111-1111-1111-111111111111', size: 8 }) };
  };
  const { v, sent } = openTouchViewer();
  v.setClient(client());
  v.clipboardImageV1 = true;
  v.waitInputResult = async () => 'applied';
  const bytes = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const image = { type: 'image/png', size: bytes.length, arrayBuffer: async () => bytes.buffer };

  assert.equal(await v.sendClipboardImage(image, true), true);
  await v.keyChain;
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/transfers/upload');
  assert.equal(calls[0].opts.headers['x-transfer-kind'], 'clipboard-image');
  const commands = cmds(sent);
  const imageCommand = commands.find((d) => d.t === 'clip-image');
  assert.equal(imageCommand.transferId, '11111111-1111-1111-1111-111111111111');
  assert.equal(imageCommand.size, 8);
  assert.match(imageCommand.sha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(commands.filter((d) => d.t === 'kd' || d.t === 'ku').map(({ t, c }) => ({ t, c })), [
    { t: 'kd', c: 'ControlLeft' }, { t: 'kd', c: 'KeyV' },
    { t: 'ku', c: 'KeyV' }, { t: 'ku', c: 'ControlLeft' },
  ]);
  globalThis.fetch = previousFetch;
  v.close(false);
});

test('clipboard de imagem: WebRTC envia inicio JSON e bytes pelo canal confiavel ordenado', async () => {
  const hub = [];
  const control = [];
  const v = new Viewer({ send: (m) => hub.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's-image', clientId: 'c1', name: 'X', iceServers: [] });
  v.setClient(client());
  v.clipboardImageV1 = true;
  v.waitInputResult = async () => 'applied';
  v.ctl = {
    readyState: 'open', bufferedAmount: 0,
    send: (value) => control.push(typeof value === 'string' ? JSON.parse(value) : new Uint8Array(value)),
    close() {},
  };
  const bytes = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4]);
  const image = { type: 'image/png', size: bytes.length, arrayBuffer: async () => bytes.buffer };

  assert.equal(await v.sendClipboardImage(image, false), true);
  assert.equal(control[0].t, 'clip-image-start');
  assert.equal(control[0].size, bytes.length);
  assert.match(control[0].sha256, /^[a-f0-9]{64}$/);
  assert.deepEqual([...control[1]], [...bytes]);
  assert.equal(hub.some((m) => m.type === 'cmd' && m.data?.t === 'clip-image-start'), false,
    'payload WebRTC nao percorre o relay de comandos do hub');
  v.close(false);
});

test('tecla modificadora nao fica "travada": keyup solta mesmo se o foco saiu do stage, e blur solta tudo', async () => {
  Object.defineProperty(globalThis, 'navigator', { value: { platform: 'Win32' }, configurable: true });
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's10', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste');
  v.setClient(client());
  v.stage.focus();
  const key = (type, code, extra = {}) =>
    window.dispatchEvent(new window.KeyboardEvent(type, { code, key: extra.key ?? code, bubbles: true, cancelable: true, ...extra }));

  // Ctrl pressionado com foco no stage, depois o foco sai (ex: clicou nas configuracoes) ANTES
  // de soltar - o ku do Ctrl nao pode ser descartado so porque o foco nao esta mais no stage.
  key('keydown', 'ControlLeft', { key: 'Control', ctrlKey: true });
  await v.keyChain;
  sent.length = 0;
  v.settingsEl.hidden = false;
  v.settingsEl.focus();
  key('keyup', 'ControlLeft', { key: 'Control' });
  await v.keyChain;
  assert.deepEqual(sent.filter((m) => m.data?.t).map((m) => stripTrace(m.data)), [{ t: 'ku', c: 'ControlLeft' }]);

  // Alt+Tab para fora do navegador: a pagina perde foco (blur) com uma tecla ainda fisicamente
  // "pressionada" do lado remoto - sem rede de seguranca, ela ficaria travada ate o Esc.
  v.stage.focus();
  key('keydown', 'AltLeft', { key: 'Alt', altKey: true });
  await v.keyChain;
  sent.length = 0;
  window.dispatchEvent(new window.Event('blur'));
  assert.deepEqual(sent.filter((m) => m.data?.t).map((m) => stripTrace(m.data)), [{ t: 'ku', c: 'AltLeft' }]);
  assert.equal(v.pressedCodes.size, 0);

  // Depois do blur, um keyup repetido da mesma tecla (ex: duplicado pelo SO) nao manda nada de novo.
  sent.length = 0;
  key('keyup', 'AltLeft', { key: 'Alt' });
  await v.keyChain;
  assert.deepEqual(sent, []);
  v.close(false);
});

// ---------- bolinha flutuante e HUD (no lugar da barra fixa) ----------
const { mountOrb } = await import('../public/orb.js');
const ptr = (el, type, x, y) => {
  const e = new window.Event(type, { bubbles: true, cancelable: true });
  Object.assign(e, { clientX: x, clientY: y, pointerId: 1 });
  el.dispatchEvent(e);
};
const memStore = () => {
  const m = new Map();
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, v), raw: m };
};

test('bolinha: clique abre/fecha o menu; arrastar move sem abrir; posicao lembrada e limitada a tela', () => {
  window.document.body.innerHTML = '<button class="vorb"></button><div class="vmenu" hidden></div>';
  const orb = window.document.querySelector('.vorb');
  const menu = window.document.querySelector('.vmenu');
  const storage = memStore();
  const o = mountOrb(orb, menu, { storage });

  ptr(orb, 'pointerdown', 990, 310);
  ptr(orb, 'pointerup', 990, 310);
  assert.equal(menu.hidden, false, 'clique simples abre');
  ptr(orb, 'pointerdown', 990, 310);
  ptr(orb, 'pointerup', 990, 310);
  assert.equal(menu.hidden, true, 'segundo clique fecha');

  // tremor pequeno (< 5px) ainda e clique
  ptr(orb, 'pointerdown', 990, 310);
  ptr(orb, 'pointermove', 992, 311);
  ptr(orb, 'pointerup', 992, 311);
  assert.equal(menu.hidden, false, 'tremor de 2px nao e arrasto');
  o.close();

  const before = orb.style.left;
  ptr(orb, 'pointerdown', 990, 310);
  ptr(orb, 'pointermove', 500, 310); // arrasta ~490px para a esquerda
  ptr(orb, 'pointerup', 500, 310);
  assert.notEqual(orb.style.left, before, 'a bolinha se moveu');
  assert.equal(menu.hidden, true, 'arrastar nao abre o menu');
  assert.ok(storage.raw.has('viewerOrbPos'), 'posicao guardada');

  // arrastar para fora da tela: limitada pela margem
  ptr(orb, 'pointerdown', 500, 310);
  ptr(orb, 'pointermove', -9000, -9000);
  ptr(orb, 'pointerup', -9000, -9000);
  assert.equal(orb.style.left, '8px');
  assert.equal(orb.style.top, '8px');

  // recarregar: volta onde o usuario deixou
  window.document.body.innerHTML = '<button class="vorb"></button><div class="vmenu" hidden></div>';
  const orb2 = window.document.querySelector('.vorb');
  mountOrb(orb2, window.document.querySelector('.vmenu'), { storage });
  assert.equal(orb2.style.left, '8px');
  assert.equal(orb2.style.top, '8px');
});

test('visualizador sem barra: tela cheia para a imagem, HUD translucido que ignora cliques, estado e selo na bolinha', async () => {
  const v = new Viewer({ send: () => {}, isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 's5', clientId: 'c1', name: 'Recepcao', iceServers: [] });
  const el = window.document.getElementById('viewer');
  assert.equal(el.querySelector('.vbar'), null, 'nao ha mais barra fixa');
  assert.ok(el.querySelector('.vorb') && el.querySelector('.vhud'));

  // menu: abre pela bolinha e mostra titulo/estado/acoes
  const orb = v.orbEl;
  ptr(orb, 'pointerdown', 10, 10);
  ptr(orb, 'pointerup', 10, 10);
  assert.equal(v.menuEl.hidden, false);
  assert.match(v.menuEl.textContent, /Recepcao/);
  for (const t of ['Configurações', 'Modo compatível', 'Estatísticas', 'Tela cheia', 'Encerrar acesso']) {
    assert.ok([...v.menuEl.querySelectorAll('button')].some((b) => b.textContent.trim() === t), t);
  }

  // clicar na tela remota fecha o menu
  ptr(v.stage, 'pointerdown', 300, 300);
  assert.equal(v.menuEl.hidden, true);

  // HUD (estatisticas translucidas): alterna pelo menu
  assert.equal(v.hudEl.hidden, false);
  v.orb.open();
  v.menuEl.querySelector('[data-act=stats]').click();
  assert.equal(v.hudEl.hidden, true);
  assert.equal(v.menuEl.hidden, true, 'o menu fecha depois da acao');
  v.orb.open();
  v.menuEl.querySelector('[data-act=stats]').click();
  assert.equal(v.hudEl.hidden, false);

  // cor da bolinha acompanha o estado da conexao
  v.stateEl.textContent = 'conectado';
  await Promise.resolve();
  assert.equal(v.orbEl.dataset.state, 'ok');
  v.stateEl.textContent = 'falhou';
  await Promise.resolve();
  assert.equal(v.orbEl.dataset.state, 'bad');
  v.stateEl.textContent = 'conectando…';
  await Promise.resolve();
  assert.equal(v.orbEl.dataset.state, 'wait');

  // somente visualizacao: anel na bolinha (visivel mesmo com o menu fechado)
  v.setClient(client({ allowRemoteControl: false }));
  assert.equal(v.orbEl.classList.contains('viewonly'), true);
  v.setClient(client());
  assert.equal(v.orbEl.classList.contains('viewonly'), false);

  // "Encerrar acesso" fecha a sessao e avisa o hub
  v.orb.open();
  v.menuEl.querySelector('[data-act=close]').click();
  assert.equal(el.hidden, true);
});

// ---------- toque: cursor virtual arrastavel, tap=clique, 2 dedos=clique direito (celular) ----------

const touchEv = (type, { x, y, id = 1 }) => {
  const e = new window.Event(type, { bubbles: true, cancelable: true });
  Object.assign(e, { clientX: x, clientY: y, pointerId: id, pointerType: 'touch', button: 0 });
  return e;
};
const mouseEv = (type, { x, y, button = 0 }) => {
  const e = new window.Event(type, { bubbles: true, cancelable: true });
  Object.assign(e, { clientX: x, clientY: y, pointerId: 99, pointerType: 'mouse', button });
  return e;
};

function openTouchViewer() {
  const sent = [];
  const v = new Viewer({ send: (m) => sent.push(m), isAdmin: () => true, onClosed: () => {} });
  v.open({ sessionId: 'tch', clientId: 'c1', name: 'X', iceServers: [] });
  v.switchToCompat('teste'); // modo compativel: os comandos saem sem depender de canais WebRTC
  v.canvas.width = 1920;
  v.canvas.height = 1080;
  // imageRect() le o retangulo do proprio <canvas> (preenche o stage via CSS no navegador real; no
  // jsdom, sem layout, precisa ser simulado aqui onde o codigo realmente le).
  const rect = { left: 0, top: 0, width: 960, height: 540, right: 960, bottom: 540 };
  v.canvas.getBoundingClientRect = () => rect;
  v.video.getBoundingClientRect = () => rect;
  sent.length = 0; // descarta o "vp" que switchToCompat() ja disparou (rect do stage, sem relacao com o teste)
  return { v, sent };
}
const cmds = (sent) => sent.filter((m) => m.type === 'cmd').map((m) => stripTrace(m.data));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test('toque: um dedo ARRASTA o cursor (nao pula pro ponto tocado) e mostra o cursor virtual', () => {
  const { v, sent } = openTouchViewer();
  assert.equal(v.cursorEl.hidden, true, 'cursor comeca oculto');
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 500, y: 300 }));
  assert.equal(v.cursorEl.hidden, false, 'primeiro toque mostra o cursor');
  assert.equal(v.touch.cursor.x, 0.5, 'o toque NAO pulou pro ponto tocado (cursor seguiu de onde estava)');
  v.stage.dispatchEvent(touchEv('pointermove', { x: 600, y: 300 })); // arrasta 100px para a direita
  assert.ok(v.touch.cursor.x > 0.5, 'o cursor andou para a direita com o arrasto (nao com o toque)');
  assert.ok(v.latest && v.latest.x === v.touch.cursor.x, 'a posicao fica na fila de envio (o loop de quadros manda ao agente)');
  v.stage.dispatchEvent(touchEv('pointerup', { x: 600, y: 300 }));
  assert.ok(!cmds(sent).some((d) => d.t === 'md'), 'arrastar nao clica');
  v.close(false);
});

test('toque: tap (sem arrastar) clica na posicao ATUAL do cursor, nao onde o dedo tocou', async () => {
  const { v, sent } = openTouchViewer();
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 100, y: 100 }));
  v.stage.dispatchEvent(touchEv('pointermove', { x: 300, y: 200 })); // posiciona o cursor arrastando
  const cursorAfterDrag = { ...v.touch.cursor };
  v.stage.dispatchEvent(touchEv('pointerup', { x: 300, y: 200 }));
  sent.length = 0;
  // novo toque em outro canto da tela, sem mover: deve clicar onde o cursor estava, nao nesse canto
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 900, y: 10, id: 2 }));
  v.stage.dispatchEvent(touchEv('pointerup', { x: 900, y: 10, id: 2 }));
  await sleep(60); // o "mu" do clique sintetico sai um pouco depois do "md" (ver clickAt)
  const c = cmds(sent);
  assert.deepEqual([c[0].t, c[0].b], ['md', 0]);
  assert.deepEqual([c[1].t, c[1].b], ['mu', 0]);
  assert.ok(Math.abs(c[0].x - cursorAfterDrag.x) < 1e-6 && Math.abs(c[0].y - cursorAfterDrag.y) < 1e-6, 'clicou na posicao do cursor, nao do toque');
  v.close(false);
});

test('toque: 2 dedos sem arrastar = clique direito (menu de contexto) na posicao do cursor', async () => {
  const { v, sent } = openTouchViewer();
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 400, y: 400, id: 1 }));
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 420, y: 420, id: 2 }));
  v.stage.dispatchEvent(touchEv('pointerup', { x: 420, y: 420, id: 2 }));
  await sleep(60);
  const c = cmds(sent);
  assert.deepEqual([c[0].t, c[0].b], ['md', 2], 'botao direito');
  assert.deepEqual([c[1].t, c[1].b], ['mu', 2]);
  sent.length = 0;
  v.stage.dispatchEvent(touchEv('pointerup', { x: 400, y: 400, id: 1 }));
  await sleep(60);
  assert.equal(cmds(sent).length, 0, 'o segundo dedo a soltar nao dispara outro clique');
  v.close(false);
});

test('toque: 2 dedos arrastando rola a tela (sem disparar clique direito ao soltar)', async () => {
  const { v, sent } = openTouchViewer();
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 400, y: 400, id: 1 }));
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 420, y: 400, id: 2 }));
  v.stage.dispatchEvent(touchEv('pointermove', { x: 400, y: 350, id: 1 }));
  v.stage.dispatchEvent(touchEv('pointermove', { x: 420, y: 350, id: 2 })); // os 2 dedos sobem juntos
  v.stage.dispatchEvent(touchEv('pointerup', { x: 400, y: 350, id: 1 }));
  v.stage.dispatchEvent(touchEv('pointerup', { x: 420, y: 350, id: 2 }));
  await sleep(60);
  const c = cmds(sent);
  assert.ok(c.some((d) => d.t === 'mw'), 'rolou a tela');
  assert.ok(!c.some((d) => d.t === 'md'), 'nao clicou com o botao direito depois de arrastar');
  v.close(false);
});

test('toque: um dedo que teve companhia (gesto de 2 dedos) nao vira tap ao soltar sozinho depois', async () => {
  const { v, sent } = openTouchViewer();
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 400, y: 400, id: 1 }));
  v.stage.dispatchEvent(touchEv('pointerdown', { x: 420, y: 420, id: 2 }));
  v.stage.dispatchEvent(touchEv('pointerup', { x: 420, y: 420, id: 2 })); // dispara o clique direito
  await sleep(60);
  sent.length = 0;
  v.stage.dispatchEvent(touchEv('pointerup', { x: 400, y: 400, id: 1 })); // solta o outro dedo depois
  await sleep(60);
  assert.equal(cmds(sent).length, 0, 'nao gera um clique esquerdo extra');
  v.close(false);
});

test('mouse/caneta continuam com o clique direto no ponto (nao usam o cursor virtual)', () => {
  const { v, sent } = openTouchViewer();
  v.stage.setPointerCapture = () => {}; // jsdom nao implementa; so para nao quebrar
  v.stage.dispatchEvent(mouseEv('pointerdown', { x: 480, y: 270 })); // centro da area (960x540)
  v.stage.dispatchEvent(mouseEv('pointerup', { x: 480, y: 270 }));
  const c = cmds(sent);
  assert.equal(c[0].t, 'md');
  assert.ok(Math.abs(c[0].x - 0.5) < 0.02 && Math.abs(c[0].y - 0.5) < 0.02, 'clicou direto onde o mouse estava');
  assert.equal(v.cursorEl.hidden, true, 'mouse nao ativa o cursor virtual de toque');
  v.close(false);
});

test('clique direito remoto nao seleciona nem arrasta a imagem no navegador', () => {
  const { v, sent } = openTouchViewer();
  v.stage.setPointerCapture = () => {};
  v.canvas.dispatchEvent(mouseEv('pointerdown', { x: 480, y: 270, button: 2 }));
  v.canvas.dispatchEvent(mouseEv('pointerup', { x: 480, y: 270, button: 2 }));
  assert.deepEqual(cmds(sent).filter((d) => d.t === 'md' || d.t === 'mu').map(({ t, b }) => ({ t, b })),
    [{ t: 'md', b: 2 }, { t: 'mu', b: 2 }], 'o botao direito continua chegando a maquina remota');

  for (const target of [v.canvas, v.video]) {
    for (const type of ['contextmenu', 'selectstart', 'dragstart']) {
      const e = new window.Event(type, { bubbles: true, cancelable: true });
      target.dispatchEvent(e);
      assert.equal(e.defaultPrevented, true, `${type} nao deve atuar na imagem remota`);
    }
  }
  const mouseDown = mouseEv('mousedown', { x: 480, y: 270, button: 2 });
  v.canvas.dispatchEvent(mouseDown);
  assert.equal(mouseDown.defaultPrevented, true, 'Safari nao deve iniciar selecao no mousedown direito');
  const textSelection = new window.Event('selectstart', { bubbles: true, cancelable: true });
  v.textCapture.dispatchEvent(textSelection);
  assert.equal(textSelection.defaultPrevented, false, 'composicao de texto continua editavel');
  v.close(false);
});

test('estatisticas WebRTC mostram ping e jitter em milissegundos', () => {
  const v = new Viewer({ send: () => {}, isAdmin: () => true, onClosed: () => {} });
  v.setClient({ ...client(), stats: { hubRoute: 'lan' } });
  const report = new Map([
    ['inbound-video', {
      id: 'inbound-video', type: 'inbound-rtp', kind: 'video', bytesReceived: 1_000_000,
      frameWidth: 1920, frameHeight: 1080, framesPerSecond: 60, codecId: 'codec-video',
      jitter: 0.0047, packetsLost: 2,
    }],
    ['pair', {
      id: 'pair', type: 'candidate-pair', state: 'succeeded', selected: true,
      currentRoundTripTime: 0.0234, localCandidateId: 'local', remoteCandidateId: 'remote',
    }],
    ['local', { id: 'local', type: 'local-candidate', candidateType: 'host' }],
    ['remote', { id: 'remote', type: 'remote-candidate', candidateType: 'srflx' }],
    ['codec-video', { id: 'codec-video', type: 'codec', mimeType: 'video/H264' }],
  ]);
  v.updateRtcStats(report, 1_000);
  assert.match(v.statsEl.textContent, /ping 23 ms/);
  assert.match(v.statsEl.textContent, /jitter 5 ms/);
  assert.match(v.statsEl.textContent, /LAN direta/);
  assert.match(v.statsEl.textContent, /direto/);
  assert.match(v.statsEl.textContent, /perda 2/);

  report.get('inbound-video').jitter = undefined;
  report.get('pair').currentRoundTripTime = undefined;
  v.updateRtcStats(report, 2_000);
  assert.match(v.statsEl.textContent, /ping n\/d/);
  assert.match(v.statsEl.textContent, /jitter n\/d/);
  v.close(false);
});

test('estatisticas mostram a rota persistida no hello antes do primeiro timer do agente', () => {
  const v = new Viewer({ send: () => {}, isAdmin: () => true, onClosed: () => {} });
  v.setClient({ ...client(), info: { hubRoute: 'cloud' } });
  assert.equal(v.hubRouteLabel(), 'Internet · Cloudflare');
  v.setClient({ ...client(), info: { hubRoute: 'lan' } });
  assert.equal(v.hubRouteLabel(), 'LAN direta');
  v.close(false);
});

test('modo compativel mede RTT e jitter por sonda confirmada pelo agente', () => {
  const { v, sent } = openTouchViewer();
  v.probeCompatLatency();
  const first = sent.find((m) => m.data?.t === 'latency-ping');
  assert.ok(first, 'envia uma sonda pelo mesmo caminho de controle da sessao');
  v.onCompatPong({ n: first.data.n });
  assert.equal(typeof v.compatPingMs, 'number');
  assert.equal(v.compatJitterMs, 0, 'a primeira amostra inicia o jitter em zero');

  v.compatProbe = { n: first.data.n + 1, started: performance.now() - 25 };
  v.onCompatPong({ n: first.data.n + 1 });
  assert.ok(v.compatJitterMs >= 0, 'a segunda amostra calcula a variacao de RTT');
  v.close(false);
});

// ---------- teclado na tela (celular) ----------

Object.defineProperty(window.Event.prototype, 'inputType', { configurable: true, get() { return this._it; } });
Object.defineProperty(window.Event.prototype, 'data', { configurable: true, get() { return this._d; } });
const inputEv = (data, inputType) => { const e = new window.Event('input', { bubbles: true }); e._it = inputType; e._d = data; return e; };

test('teclado na tela: abre focado, envia caracteres digitados e Enter/Backspace', () => {
  const { v, sent } = openTouchViewer();
  assert.equal(v.kbEl.hidden, true);
  v.toggleKeyboard();
  assert.equal(v.kbEl.hidden, false);
  assert.equal(window.document.activeElement, v.kbInput, 'o campo recebe o foco (abre o teclado do celular)');

  sent.length = 0;
  v.kbInput.value = 'á';
  v.kbInput.dispatchEvent(inputEv('á', 'insertText'));
  assert.deepEqual(cmds(sent), [{ t: 'kt', ch: 'á' }]);
  assert.equal(v.kbInput.value, '', 'o campo e esvaziado a cada tecla');

  sent.length = 0;
  v.kbInput.value = '';
  v.kbInput.dispatchEvent(inputEv(null, 'deleteContentBackward'));
  assert.deepEqual(cmds(sent), [{ t: 'kd', c: 'Backspace' }, { t: 'ku', c: 'Backspace' }]);

  sent.length = 0;
  const enter = new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
  v.kbInput.dispatchEvent(enter);
  assert.deepEqual(cmds(sent), [{ t: 'kd', c: 'Enter' }, { t: 'ku', c: 'Enter' }]);

  v.toggleKeyboard(false);
  assert.equal(v.kbEl.hidden, true);
  v.close(false);
});

test('teclado na tela: enquanto focado, o teclado fisico global nao repete a tecla', () => {
  const { v } = openTouchViewer();
  v.toggleKeyboard();
  const spy = [];
  const orig = v.sendCtl.bind(v);
  v.sendCtl = (m) => { spy.push(m); orig(m); };
  window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'x', bubbles: true, cancelable: true }));
  assert.equal(spy.length, 0, 'o listener global de teclado ignora eventos com foco no teclado na tela');
  v.close(false);
});
