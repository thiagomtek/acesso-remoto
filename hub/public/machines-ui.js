// Tela de gestao de maquinas: cartoes com status, versao, acesso, configuracoes e historico de atualizacao.
// Funcoes de montagem sem estado proprio (testaveis em jsdom): quem chama guarda filtro/busca/paineis abertos.

import { buildSettingsPanel } from './settings.js';

const svg = (path, size = 16) =>
  `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
export const ICONS = {
  monitor: svg('<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>'),
  settings: svg('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
  history: svg('<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>'),
  log: svg('<path d="M4 4h16v16H4z"/><path d="M8 9h8M8 13h8M8 17h5"/>'),
  check: svg('<path d="M20 6 9 17l-5-5"/>', 14),
  up: svg('<path d="M12 19V5M5 12l7-7 7 7"/>', 14),
  play: svg('<path d="M6 4v16l14-8z"/>', 14),
  alert: svg('<path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/>', 14),
  undo: svg('<path d="M9 14 4 9l5-5"/><path d="M4 9h10a6 6 0 0 1 0 12h-3"/>', 14),
  info: svg('<circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v5h1"/>', 14),
  edit: svg('<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>', 14),
  cpu: svg('<rect x="6" y="6" width="12" height="12" rx="1"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/>', 14),
};

/** "agora", "ha 5 min", "ha 3 h", "ha 2 d" */
export function ago(t, now = Date.now()) {
  if (!t) return 'nunca';
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 45) return 'agora há pouco';
  const m = Math.round(s / 60);
  if (m < 60) return `há ${m} min`;
  const h = Math.round(m / 60);
  if (h < 48) return `há ${h} h`;
  return `há ${Math.round(h / 24)} d`;
}

/** Texto, tom e icone de cada evento do historico de atualizacao. */
export function describeHistory(e) {
  switch (e.type) {
    case 'version':
      return e.from
        ? { tone: 'ok', icon: 'check', title: 'Versão atualizada', detail: `${e.from} → ${e.to}` }
        : { tone: 'info', icon: 'info', title: 'Versão informada', detail: e.to };
    case 'offered':
      return { tone: 'info', icon: 'up', title: 'Atualização oferecida', detail: `→ ${e.to}` };
    case 'applying':
      return { tone: 'info', icon: 'play', title: 'Aplicando a atualização', detail: `${e.from} → ${e.to}` };
    case 'failed':
      return { tone: 'bad', icon: 'alert', title: 'Falha na atualização', detail: [e.to && `(${e.to})`, e.text].filter(Boolean).join(' ') };
    case 'rollback':
      return { tone: 'bad', icon: 'undo', title: 'Atualização desfeita', detail: `${e.from} não subiu; voltou para ${e.to}` };
    case 'diagnostic':
      return { tone: 'bad', icon: 'alert', title: 'Diagnóstico técnico do agente', detail: e.text || '' };
    default:
      return { tone: 'info', icon: 'info', title: e.type || 'Evento', detail: e.text || '' };
  }
}

export function summarize(clients) {
  const s = { total: clients.length, online: 0, offline: 0, pending: 0 };
  for (const c of clients) {
    if (!c.approved) s.pending++;
    else if (c.online) s.online++;
    else s.offline++;
  }
  return s;
}

export const stateOf = (c) => (!c.approved ? 'pending' : c.online ? 'online' : 'offline');

/** Filtra por estado e busca; ordena: pendentes, online, offline, e por nome. */
export function visibleClients(clients, { filter = 'all', query = '' } = {}) {
  const q = query.trim().toLowerCase();
  const rank = { pending: 0, online: 1, offline: 2 };
  return clients
    .filter((c) => filter === 'all' || stateOf(c) === filter)
    .filter((c) => !q || [c.name, c.info?.hostname, c.info?.os].filter(Boolean).join(' ').toLowerCase().includes(q))
    .sort((a, b) => rank[stateOf(a)] - rank[stateOf(b)] || a.name.localeCompare(b.name, 'pt-BR'));
}

const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  for (const k of kids) if (k != null) e.append(k);
  return e;
};
const html = (tag, className, markup) => Object.assign(el(tag, { className }), { innerHTML: markup });

function versionChip(c, latestAgent) {
  const ver = c.info?.jarSha256 ? c.info.jarSha256.slice(0, 8) : '';
  if (!ver) {
    return c.info?.version ? el('span', { className: 'chip warn', title: 'Versão antiga do agente: reinstale uma vez para habilitar a atualização automática', textContent: 'Versão antiga' }) : null;
  }
  const tone = !latestAgent ? 'muted' : ver === latestAgent ? 'ok' : 'warn';
  const label = !latestAgent ? `v ${ver}` : ver === latestAgent ? 'Atualizado' : 'Desatualizado';
  return el('span', { className: `chip ${tone}`, title: `Versão do agente: ${ver}${latestAgent ? ` (publicada: ${latestAgent})` : ''}`, textContent: label });
}

function buildHistory(c) {
  const events = [...(c.history || [])].reverse();
  const wrap = el('div', { className: 'history' });
  if (!events.length) {
    wrap.append(el('p', { className: 'muted small', textContent: 'Ainda não há eventos de atualização para esta máquina.' }));
    return wrap;
  }
  const ul = el('ul', { className: 'timeline' });
  for (const e of events) {
    const d = describeHistory(e);
    const li = el('li', { className: `tl ${d.tone}` });
    li.append(
      html('span', 'tl-icon', ICONS[d.icon] || ICONS.info),
      el('div', { className: 'tl-body' },
        el('div', { className: 'tl-title', textContent: d.title }),
        d.detail ? el('div', { className: 'tl-detail', textContent: d.detail }) : null),
      el('time', { className: 'tl-time', textContent: new Date(e.t).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }), title: ago(e.t) }),
    );
    ul.append(li);
  }
  wrap.append(ul);
  return wrap;
}

/**
 * Cartao de uma maquina.
 * @param ctx {admin, send, latestAgent, panel: 'settings'|'history'|null, onPanel(id, name), onAccess(id), now}
 */
export function buildMachineCard(c, ctx) {
  const state = stateOf(c);
  const now = ctx.now ?? Date.now();
  const card = el('article', { className: 'machine' });
  card.dataset.id = c.id;
  card.dataset.state = state;

  const meta = [c.info?.hostname && c.info.hostname !== c.name ? c.info.hostname : null, c.info?.os, c.info?.screen ? `${c.info.screen.w}×${c.info.screen.h}` : null].filter(Boolean).join(' · ');
  const seen = state === 'online' ? 'online agora' : c.lastSeen ? `visto ${ago(c.lastSeen, now)}` : 'nunca conectou';

  const nameEl = el('h3', { textContent: c.name });
  const nameWrap = el('div', { className: 'm-name' }, nameEl);
  if (ctx.admin) {
    const editBtn = el('button', { className: 'btn ghost icon', title: 'Renomear esta máquina' });
    editBtn.innerHTML = ICONS.edit;
    editBtn.onclick = () => {
      const input = Object.assign(document.createElement('input'), { type: 'text', value: c.name, className: 'm-name-input', maxLength: 80 });
      const finish = (commit) => {
        if (commit) {
          const next = input.value.trim();
          if (next && next !== c.name) ctx.send({ type: 'update-client', clientId: c.id, name: next });
        }
        nameWrap.replaceChildren(nameEl, editBtn);
      };
      input.onkeydown = (e) => {
        if (e.key === 'Enter') finish(true);
        else if (e.key === 'Escape') finish(false);
      };
      input.onblur = () => finish(true);
      nameWrap.replaceChildren(input);
      input.focus();
      input.select();
    };
    nameWrap.append(editBtn);
  }

  const statsLine = c.stats && (c.stats.cpuPct != null || c.stats.memUsedMb != null)
    ? [c.stats.cpuPct != null ? `CPU ${c.stats.cpuPct.toFixed(0)}%` : null,
       c.stats.memUsedMb != null && c.stats.memTotalMb ? `RAM ${(c.stats.memUsedMb / 1024).toFixed(1)}/${(c.stats.memTotalMb / 1024).toFixed(1)} GB` : null]
        .filter(Boolean).join(' · ')
    : null;

  card.append(
    el('header', { className: 'm-head' },
      el('span', { className: 'dot', title: state === 'online' ? 'Online' : state === 'pending' ? 'Aguardando aprovação' : 'Offline' }),
      el('div', { className: 'm-title' },
        nameWrap,
        el('div', { className: 'm-sub', textContent: meta || 'Sem informações ainda' })),
      state === 'pending' ? el('span', { className: 'chip warn', textContent: 'Nova' }) : versionChip(c, ctx.latestAgent)),
    el('div', { className: 'm-status', textContent: seen }),
    ...(statsLine ? [html('div', 'm-stats', `${ICONS.cpu}<span>${statsLine}</span>`)] : []),
  );

  if (state === 'pending') {
    const approve = el('button', { className: 'btn primary', textContent: 'Aprovar', disabled: !ctx.admin });
    approve.onclick = () => ctx.send({ type: 'approve-client', clientId: c.id });
    const refuse = el('button', { className: 'btn ghost danger', textContent: 'Recusar', disabled: !ctx.admin });
    refuse.onclick = () => { if (confirm(`Recusar e remover "${c.name}"?`)) ctx.send({ type: 'revoke-client', clientId: c.id }); };
    card.append(
      el('p', { className: 'm-pending', textContent: 'Esta máquina se registrou sozinha. Aprove para permitir o acesso.' }),
      el('div', { className: 'm-actions' }, approve, refuse),
    );
    return card;
  }

  const access = el('button', { className: 'btn primary access', disabled: !c.online, title: c.online ? 'Abrir a tela desta máquina' : 'A máquina está offline' });
  access.innerHTML = `${ICONS.monitor}<span>Acessar</span>`;
  access.onclick = () => ctx.onAccess(c.id);
  const tab = (name, label, icon) => {
    const b = el('button', { className: `btn ghost tab${ctx.panel === name ? ' active' : ''}`, title: label });
    b.setAttribute('aria-expanded', String(ctx.panel === name));
    b.innerHTML = `${icon}<span>${label}</span>`;
    b.onclick = () => ctx.onPanel(c.id, name);
    return b;
  };
  const actions = [access, tab('settings', 'Configurações', ICONS.settings), tab('history', 'Histórico', ICONS.history)];
  card.append(el('div', { className: 'm-actions' }, ...actions));

  if (ctx.panel === 'settings') {
    card.append(el('div', { className: 'm-panel' }, buildSettingsPanel(c, { admin: ctx.admin, send: ctx.send, withRemove: true })));
  } else if (ctx.panel === 'history') {
    card.append(el('div', { className: 'm-panel' }, buildHistory(c)));
  }
  return card;
}

/** Monta/atualiza a lista. Preserva o cartao em que o operador esta mexendo (foco dentro dele). */
export function renderMachines(listEl, ctx) {
  const { clients, filter, query, panels } = ctx;
  const shown = visibleClients(clients, { filter, query });
  const editing = document.activeElement?.closest?.('.machine')?.dataset.id;
  const old = new Map([...listEl.querySelectorAll('.machine')].map((n) => [n.dataset.id, n]));
  const nodes = shown.map((c) =>
    c.id === editing && old.has(c.id)
      ? old.get(c.id)
      : buildMachineCard(c, { ...ctx, panel: panels.get(c.id) || null }));
  listEl.replaceChildren(...nodes);
  return shown.length;
}
