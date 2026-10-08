import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

// Testes da tela de gestao das maquinas e do painel do alerta do Teams (sem navegador): jsdom.
const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true });
const { window } = dom;
globalThis.window = window;
globalThis.document = window.document;
globalThis.HTMLElement = window.HTMLElement;
globalThis.Node = window.Node;
globalThis.confirm = () => true;

const mu = await import('../public/machines-ui.js');
const { TeamsAlert } = await import('../public/teams-alert.js');

const mc = (over = {}) => ({
  id: over.id || 'm1', name: 'Recepcao', online: true, approved: true, lastSeen: Date.now(),
  info: { hostname: 'RECEP-01', os: 'Windows 11 10.0', screen: { w: 1920, h: 1080 }, jarSha256: 'aaaaaaaa' + '0'.repeat(56) },
  settings: { allowRemoteControl: true, clipboardSync: true, keepAwake: false, teamsWatcher: false, startWithSystem: true, quality: 'auto' },
  history: [],
  ...over,
});
const ctxOf = (clients, extra = {}) => ({
  clients, filter: 'all', query: '', panels: new Map(), admin: true, send: () => {}, latestAgent: 'aaaaaaaa',
  onAccess: () => {}, onPanel: () => {}, ...extra,
});

test('gestao: tempo relativo, resumo, filtros, busca e ordenacao', () => {
  const now = 10_000_000;
  assert.equal(mu.ago(0, now), 'nunca');
  assert.equal(mu.ago(now - 10_000, now), 'agora há pouco');
  assert.equal(mu.ago(now - 5 * 60_000, now), 'há 5 min');
  assert.equal(mu.ago(now - 3 * 3600_000, now), 'há 3 h');
  assert.equal(mu.ago(now - 5 * 86400_000, now), 'há 5 d');

  const list = [
    mc({ id: 'a', name: 'Zeta', online: false }),
    mc({ id: 'b', name: 'Beta', online: true }),
    mc({ id: 'c', name: 'Alfa', online: true }),
    mc({ id: 'd', name: 'Nova', approved: false, online: true }),
  ];
  assert.deepEqual(mu.summarize(list), { total: 4, online: 2, offline: 1, pending: 1 });
  assert.deepEqual(mu.visibleClients(list).map((c) => c.id), ['d', 'c', 'b', 'a'], 'novas, online (por nome), offline');
  assert.deepEqual(mu.visibleClients(list, { filter: 'online' }).map((c) => c.id), ['c', 'b']);
  assert.deepEqual(mu.visibleClients(list, { filter: 'pending' }).map((c) => c.id), ['d']);
  assert.deepEqual(mu.visibleClients(list, { filter: 'offline' }).map((c) => c.id), ['a']);
  assert.deepEqual(mu.visibleClients(list, { query: 'ALF' }).map((c) => c.id), ['c'], 'busca sem diferenciar maiusculas');
  assert.equal(mu.visibleClients(list, { query: 'windows' }).length, 4, 'busca tambem no sistema/hostname');
});

test('gestao: cartoes por estado (online, offline, nova), acesso e chip de versao', () => {
  window.document.body.innerHTML = '<div id="list"></div>';
  const list = window.document.getElementById('list');
  const sent = [];
  const accessed = [];
  const n = mu.renderMachines(list, ctxOf([
    mc({ id: 'on', name: 'Online' }),
    mc({ id: 'off', name: 'Offline', online: false, lastSeen: Date.now() - 3 * 3600_000 }),
    mc({ id: 'new', name: 'Pendente', approved: false }),
  ], { send: (m) => sent.push(m), onAccess: (id) => accessed.push(id) }));
  assert.equal(n, 3);
  const card = (id) => list.querySelector(`[data-id="${id}"]`);

  assert.equal(card('on').dataset.state, 'online');
  assert.equal(card('on').querySelector('.access').disabled, false);
  card('on').querySelector('.access').click();
  assert.deepEqual(accessed, ['on']);
  assert.match(card('on').textContent, /online agora/);
  assert.match(card('on').textContent, /1920×1080/);

  assert.equal(card('off').dataset.state, 'offline');
  assert.equal(card('off').querySelector('.access').disabled, true, 'offline nao permite acessar');
  assert.match(card('off').textContent, /visto há 3 h/);

  assert.equal(card('new').dataset.state, 'pending');
  assert.equal(card('new').querySelector('.access'), null, 'nova: sem acesso ate aprovar');
  [...card('new').querySelectorAll('button')].find((b) => b.textContent === 'Aprovar').click();
  assert.deepEqual(sent.at(-1), { type: 'approve-client', clientId: 'new' });

  // chip de versao: atualizado / desatualizado / versao antiga (sem hash)
  assert.match(card('on').querySelector('.chip').textContent, /Atualizado/);
  const old = mc({ id: 'o', info: { hostname: 'x', os: 'Windows', jarSha256: 'bbbbbbbb' + '0'.repeat(56) } });
  const legacy = mc({ id: 'l', info: { hostname: 'y', os: 'Windows', version: 'agent-1' } });
  mu.renderMachines(list, ctxOf([old, legacy]));
  assert.match(list.querySelector('[data-id="o"] .chip').textContent, /Desatualizado/);
  assert.match(list.querySelector('[data-id="l"] .chip').textContent, /Versão antiga/);
});

test('gestao: renomear maquina (so admin) e exibir estatisticas de CPU/RAM quando chegam', () => {
  window.document.body.innerHTML = '<div id="list"></div>';
  const list = window.document.getElementById('list');
  const sent = [];
  mu.renderMachines(list, ctxOf([
    mc({ id: 'a', name: 'Recepcao', stats: { cpuPct: 12.4, memUsedMb: 3276, memTotalMb: 16384 } }),
  ], { send: (m) => sent.push(m) }));
  const card = list.querySelector('[data-id="a"]');

  assert.match(card.querySelector('.m-stats')?.textContent, /CPU 12%/);
  assert.match(card.querySelector('.m-stats')?.textContent, /RAM 3\.2\/16\.0 GB/);

  const editBtn = card.querySelector('.m-name .btn');
  editBtn.click();
  const input = card.querySelector('.m-name-input');
  assert.ok(input, 'clicar no lapis troca o nome por um campo editavel');
  assert.equal(input.value, 'Recepcao');
  input.value = 'Recepção - Loja 2';
  input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter' }));
  assert.deepEqual(sent.at(-1), { type: 'update-client', clientId: 'a', name: 'Recepção - Loja 2' });
  assert.equal(card.querySelector('.m-name-input'), null, 'volta a mostrar o nome (nao o campo) depois de confirmar');

  // Esc cancela sem enviar nada
  card.querySelector('.m-name .btn').click();
  const input2 = card.querySelector('.m-name-input');
  input2.value = 'Nome que nao deveria ir';
  input2.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
  assert.equal(sent.length, 1, 'Esc nao manda update-client');

  // Nao-admin nao ve o lapis
  mu.renderMachines(list, ctxOf([mc({ id: 'a', stats: null })], { admin: false }));
  assert.equal(list.querySelector('[data-id="a"] .m-name .btn'), null);
  assert.equal(list.querySelector('[data-id="a"] .m-stats'), null, 'sem stats ainda: nada e mostrado');
});

test('gestao: abas Configuracoes e Historico; historico mostra o mais recente primeiro com tom por evento', () => {
  window.document.body.innerHTML = '<div id="list"></div>';
  const list = window.document.getElementById('list');
  const now = Date.now();
  const m = mc({
    id: 'h1',
    history: [
      { t: now - 5000, type: 'version', to: 'aaaaaaaa' },
      { t: now - 4000, type: 'offered', from: 'aaaaaaaa', to: 'bbbbbbbb' },
      { t: now - 3000, type: 'applying', from: 'aaaaaaaa', to: 'bbbbbbbb' },
      { t: now - 2000, type: 'failed', to: 'bbbbbbbb', text: 'ASSINATURA INVALIDA' },
      { t: now - 1000, type: 'rollback', from: 'bbbbbbbb', to: 'aaaaaaaa' },
    ],
  });
  const panels = new Map();
  const toggles = [];
  const ctx = ctxOf([m], { panels, onPanel: (id, name) => toggles.push([id, name]) });

  mu.renderMachines(list, ctx);
  assert.equal(list.querySelector('.m-panel'), null, 'painel fechado por padrao');
  const tabs = [...list.querySelectorAll('button.tab')];
  assert.deepEqual(tabs.map((b) => b.textContent.trim()), ['Configurações', 'Histórico']);
  tabs[1].click();
  assert.deepEqual(toggles.at(-1), ['h1', 'history']);

  panels.set('h1', 'history');
  mu.renderMachines(list, ctx);
  const items = [...list.querySelectorAll('.timeline .tl')];
  assert.equal(items.length, 5);
  assert.match(items[0].textContent, /Atualização desfeita/, 'mais recente primeiro');
  assert.ok(items[0].classList.contains('bad'));
  assert.match(items[0].textContent, /bbbbbbbb não subiu; voltou para aaaaaaaa/);
  assert.ok(items[1].classList.contains('bad') && /ASSINATURA INVALIDA/.test(items[1].textContent));
  assert.match(items[4].textContent, /Versão informada/);
  assert.ok(list.querySelector('button.tab.active').textContent.includes('Histórico'));

  panels.set('h1', 'settings');
  mu.renderMachines(list, ctx);
  assert.ok(list.querySelector('.m-panel .settings'), 'aba de configuracoes mostra os interruptores');
  assert.equal(list.querySelector('.m-panel .timeline'), null);

  panels.set('h1', 'history');
  mu.renderMachines(list, ctxOf([mc({ id: 'h1', history: [] })], { panels }));
  assert.match(list.querySelector('.history').textContent, /Ainda não há eventos/);
  for (const [type, tone] of [['version', 'info'], ['offered', 'info'], ['applying', 'info'], ['failed', 'bad'], ['rollback', 'bad']]) {
    assert.equal(mu.describeHistory({ type, from: type === 'version' ? '' : 'a', to: 'b' }).tone, tone, type);
  }
  assert.equal(mu.describeHistory({ type: 'version', from: 'a', to: 'b' }).tone, 'ok', 'mudanca de versao = sucesso');
});

test('gestao: nao reconstroi o cartao em que o operador esta mexendo', () => {
  window.document.body.innerHTML = '<div id="list"></div>';
  const list = window.document.getElementById('list');
  const panels = new Map([['k1', 'settings']]);
  mu.renderMachines(list, ctxOf([mc({ id: 'k1' }), mc({ id: 'k2', name: 'Outra' })], { panels }));
  const sel = list.querySelector('[data-id="k1"] select');
  sel.focus();
  const before = list.querySelector('[data-id="k1"]');
  mu.renderMachines(list, ctxOf([mc({ id: 'k1', online: false }), mc({ id: 'k2', name: 'Outra' })], { panels }));
  assert.equal(list.querySelector('[data-id="k1"]'), before, 'cartao em edicao preservado');
});

test('teams: com espaco no topo vira botao + painel suspenso que fecha ao clicar fora', async () => {
  const { mountTeamsUi } = await import('../public/teams-ui.js');
  window.document.body.innerHTML = '<div id="teams-slot"></div><main></main>';
  const audio = { ready: true, playing: false, start() { this.playing = true; return true; }, stop() { this.playing = false; }, unlock() {} };
  const a = new TeamsAlert({ audio, now: () => 1_000_000 });
  a.prefs = { enabled: true, idleCheck: true, awayMinutes: 5 };
  mountTeamsUi(a, window.document.body);
  const btn = window.document.getElementById('teams-btn');
  const pop = window.document.querySelector('.teams-pop');
  assert.ok(btn && pop);
  assert.equal(pop.hidden, true, 'painel fechado por padrao');
  assert.equal(btn.dataset.on, 'true', 'alerta ativo: indicador verde');

  btn.click();
  assert.equal(pop.hidden, false);
  assert.ok(window.document.getElementById('teams-enabled'), 'opcoes ja visiveis no painel');
  window.document.dispatchEvent(new window.Event('pointerdown'));
  assert.equal(pop.hidden, true, 'fecha ao clicar fora');

  a.setPrefs({ enabled: false });
  assert.equal(btn.dataset.on, 'false');
});
