// Painel do operador: gestao das maquinas (status, acesso, configuracoes, historico de atualizacao).

import { Viewer } from './viewer.js';
import { TeamsAlert, WebAudioSiren } from './teams-alert.js';
import { mountTeamsUi } from './teams-ui.js';
import { renderMachines, summarize, visibleClients } from './machines-ui.js';

const $ = (id) => document.getElementById(id);
const store = (() => { try { return window.localStorage; } catch { return null; } })();

// Alerta de atividade no Teams (sirene) - regra migrada do Servidor original
const teams = new TeamsAlert({ audio: new WebAudioSiren(), storage: store });
mountTeamsUi(teams);

const viewer = new Viewer({ send: (m) => send(m), isAdmin: () => admin, onClosed: () => {} });
let ws;
let admin = false;
let accountEmail = '';
let signingOut = false;
let clients = [];
let latestAgent = '';
let filter = store?.getItem('machinesFilter') || 'all';
let query = '';
const panels = new Map(); // id -> 'settings' | 'history'
let lanAttempted = false;

/** Aviso na parte de cima do painel (dispensavel). */
function notice(text) {
  const el = document.createElement('div');
  el.className = 'notice';
  el.append(Object.assign(document.createElement('span'), { textContent: text }));
  const x = Object.assign(document.createElement('button'), { textContent: 'Dispensar' });
  x.onclick = () => el.remove();
  el.append(x);
  $('notices').prepend(el);
}

function send(msg) {
  if (ws?.readyState !== 1) return false;
  ws.send(JSON.stringify(msg));
  return true;
}

function setConn(text, state) {
  $('conn-text').textContent = text;
  $('conn').dataset.state = state;
}

function connectLan(data) {
  if (lanAttempted || !data?.ticket || typeof data.url !== 'string') return;
  lanAttempted = true;
  let url;
  try {
    url = new URL(data.url);
    if (url.protocol !== 'wss:') return;
    url.searchParams.set('lanTicket', data.ticket);
  } catch { return; }
  const local = new WebSocket(url);
  bindSocket(local, 'lan');
}

function bindSocket(socket, route) {
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => {
    const previous = ws;
    ws = socket;
    if (previous && previous !== socket) previous.close();
    setConn(route === 'lan' ? 'conectado · rede local' : 'conectado · nuvem', 'ok');
    viewer.recoverInputTraces();
    if (route === 'cloud') socket.send(JSON.stringify({ type: 'lan-ticket' }));
  };
  socket.onclose = async () => {
    if (ws !== socket) return; // a conexao anterior foi substituida pela LAN com sucesso
    viewer.close(false);
    if (signingOut) return;
    try {
      const auth = await fetch('/api/auth/me', { cache: 'no-store' });
      if (auth.status === 401) { location.href = '/login.html'; return; }
    } catch { /* hub reiniciando: o ciclo normal tentara de novo */ }
    setConn('reconectando…', 'wait');
    setTimeout(connect, 2000);
  };
  socket.onmessage = (ev) => {
    if (typeof ev.data !== 'string') { viewer.onBinary(ev.data); return; }
    const m = JSON.parse(ev.data);
    if (m.type === 'hello') {
      admin = m.admin;
      accountEmail = m.email;
      $('who').textContent = m.email;
      $('account-email').value = m.email;
      render();
    } else if (m.type === 'lan-ticket') {
      if (route === 'cloud') connectLan(m);
    } else if (m.type === 'clients') {
      clients = m.clients;
      latestAgent = m.latestAgent || '';
      render();
      // Tela de visualizacao aberta: reflete na hora qualquer mudanca de configuracao.
      if (viewer.clientId) viewer.setClient(clients.find((x) => x.id === viewer.clientId));
    } else if (m.type === 'client-event') {
      const name = clients.find((x) => x.id === m.clientId)?.name || 'Máquina';
      if (m.event === 'update-rollback') {
        notice(`${name}: a atualização do agente (versão ${m.data?.badVersion || '?'}) não subiu e a máquina voltou para a versão anterior (${m.data?.currentVersion || '?'}).`);
      } else if (m.event === 'update-failed') {
        notice(`${name}: falha ao atualizar o agente${m.data?.error ? ` — ${m.data.error}` : ''}.`);
      } else if (m.event === 'teams') {
        teams.teamsEvent(m.clientId, name, !!m.data?.active);
      }
    } else if (m.type === 'session-ready') {
      const c = clients.find((x) => x.id === m.clientId);
      viewer.open({ sessionId: m.sessionId, clientId: m.clientId, name: c?.name || 'Máquina remota', iceServers: m.iceServers });
      viewer.setClient(c);
    } else if (m.type === 'rtc') {
      if (m.sessionId === viewer.sessionId) viewer.onRtc(m.data);
    } else if (m.type === 'compat-pong') {
      if (m.sessionId === viewer.sessionId) viewer.onCompatPong(m.data);
    } else if (m.type === 'telemetry-ack') {
      viewer.onTelemetryAck(m.ids);
    } else if (m.type === 'input-result') {
      viewer.onInputResult(m.data);
    } else if (m.type === 'file-ready') {
      if (m.sessionId === viewer.sessionId) viewer.onFileReady(m);
    } else if (m.type === 'file-status') {
      if (m.sessionId === viewer.sessionId) viewer.onFileStatus(m);
    } else if (m.type === 'session-closed') {
      if (m.sessionId === viewer.sessionId) viewer.close(false);
    } else if (m.type === 'error') {
      alert({ 'client-offline': 'A máquina está offline.', 'not-approved': 'Aprove a máquina antes de acessar.', forbidden: 'Sem permissão.' }[m.error] || m.error);
    }
  };
}

function connect() {
  lanAttempted = false;
  const cloud = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/operator`);
  ws = cloud;
  bindSocket(cloud, 'cloud');
}

function render() {
  const s = summarize(clients);
  const chips = [
    s.online ? `<span class="chip ok">${s.online} online</span>` : '',
    s.offline ? `<span class="chip">${s.offline} offline</span>` : '',
    s.pending ? `<span class="chip warn">${s.pending} ${s.pending === 1 ? 'nova' : 'novas'}</span>` : '',
  ].join('');
  $('summary').innerHTML = chips || '<span class="muted small">Nenhuma máquina</span>';

  renderMachines($('list'), {
    clients, filter, query, panels, admin, send, latestAgent,
    onAccess: (id) => send({ type: 'session-open', clientId: id }),
    onPanel: (id, name) => {
      if (panels.get(id) === name) panels.delete(id); else panels.set(id, name);
      render();
    },
  });
}

function setAccountMessage(text, ok = false) {
  $('account-message').textContent = text;
  $('account-message').classList.toggle('ok', ok);
}

function openAccount() {
  $('account-email').value = accountEmail;
  setAccountMessage('');
  $('password-form').reset();
  $('account-email').value = accountEmail;
  $('account-modal').hidden = false;
  $('current-password').focus();
}

function closeAccount() {
  $('account-modal').hidden = true;
  $('password-form').reset();
  setAccountMessage('');
}

$('account-settings').onclick = openAccount;
for (const button of document.querySelectorAll('[data-account-close]')) button.onclick = closeAccount;

$('logout').onclick = async () => {
  if (signingOut) return;
  signingOut = true;
  $('logout').disabled = true;
  try {
    await fetch('/api/auth/logout', { method: 'POST', cache: 'no-store' });
    location.replace('/login.html');
  } catch {
    signingOut = false;
    $('logout').disabled = false;
    notice('Não foi possível sair agora. Verifique a conexão e tente novamente.');
  }
};

$('password-form').onsubmit = async (event) => {
  event.preventDefault();
  const currentPassword = $('current-password').value;
  const newPassword = $('new-password').value;
  if (newPassword !== $('confirm-password').value) { setAccountMessage('A confirmação não corresponde à nova senha.'); return; }
  if (newPassword.length < 7) { setAccountMessage('A nova senha precisa ter pelo menos 7 caracteres.'); return; }
  $('save-password').disabled = true;
  setAccountMessage('Salvando…');
  try {
    const response = await fetch('/api/auth/password', {
      method: 'POST', headers: { 'content-type': 'application/json' }, cache: 'no-store',
      body: JSON.stringify({ currentPassword, newPassword }),
    });
    if (response.status === 401) { location.href = '/login.html'; return; }
    const data = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (!response.ok) {
      setAccountMessage(data.error === 'senha-atual-invalida' ? 'A senha atual está incorreta.' : 'Não foi possível alterar a senha.');
      return;
    }
    $('current-password').value = '';
    $('new-password').value = '';
    $('confirm-password').value = '';
    setAccountMessage('Senha alterada com sucesso.', true);
  } catch {
    setAccountMessage('Falha de conexão. Tente novamente.');
  } finally {
    $('save-password').disabled = false;
  }
};

document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  if (!$('account-modal').hidden) closeAccount();
});

// filtros e busca
for (const b of document.querySelectorAll('#filters button')) {
  b.setAttribute('aria-selected', String(b.dataset.filter === filter));
  b.onclick = () => {
    filter = b.dataset.filter;
    try { store?.setItem('machinesFilter', filter); } catch { /* sem storage */ }
    for (const o of document.querySelectorAll('#filters button')) o.setAttribute('aria-selected', String(o === b));
    render();
  };
}
$('search').oninput = (e) => { query = e.target.value; render(); };

setInterval(() => { if (clients.length) render(); }, 30000); // "visto ha X min" se mantem atual
render();
connect();
