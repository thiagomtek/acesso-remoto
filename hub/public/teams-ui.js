// Interface do alerta do Teams: cartao de opcoes + janela de alerta (modal) + ligacao com o navegador
// (eventos de mouse/teclado para a presenca, notificacao do sistema, IdleDetector quando disponivel).

import { SNOOZE_MINUTES } from './teams-alert.js';

export function mountTeamsUi(teams, root = document.body) {
  const logLines = [];
  const slot = root.querySelector('#teams-slot');
  const card = document.createElement('div');
  card.className = 'card teams-card' + (slot ? ' teams-pop' : '');
  card.innerHTML = `
    <div class="row">
      <div class="grow">
        <div class="name">Alerta do Teams</div>
        <div class="sub" id="teams-status"></div>
      </div>
      <button id="teams-test">Testar sirene</button>
    </div>
    <div class="teams-opts">
      <label class="opt"><input type="checkbox" id="teams-enabled"> Tocar sirene quando chegar atividade no Teams de uma máquina</label>
      <label class="opt"><input type="checkbox" id="teams-idle"> Verificar inatividade (só tocar quando eu estiver ausente)</label>
      <label class="opt">Considerar ausente após <input type="number" id="teams-away" min="1" max="240" style="width:5em"> min sem mexer no mouse/teclado</label>
      <div class="row"><button id="teams-idledetector" hidden>Detectar ausência do computador inteiro</button></div>
      <div class="sub">A sirene toca no volume máximo do navegador. Mantenha esta página aberta (pode ficar em segundo plano).</div>
      <pre class="teams-log" id="teams-log"></pre>
    </div>`;
  let btn = null;
  if (slot) {
    // Modo painel: botao compacto no topo que abre o painel suspenso (nao ocupa espaco na pagina)
    btn = document.createElement('button');
    btn.id = 'teams-btn';
    btn.className = 'pillbtn';
    btn.type = 'button';
    btn.setAttribute('aria-haspopup', 'dialog');
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg><span>Alerta do Teams</span><span class="status-dot"></span>';
    card.hidden = true;
    slot.append(btn);
    root.append(card);
    btn.onclick = (e) => { e.stopPropagation(); card.hidden = !card.hidden; };
    document.addEventListener('pointerdown', (e) => { if (!card.hidden && !card.contains(e.target) && !btn.contains(e.target)) card.hidden = true; });
  } else {
    root.querySelector('main')?.prepend(card) ?? root.append(card);
  }

  const modal = document.createElement('div');
  modal.id = 'teams-modal';
  modal.hidden = true;
  modal.innerHTML = `
    <div class="teams-modal-box">
      <h2>Nova atividade no Teams!</h2>
      <p id="teams-modal-detail"></p>
      <div class="row">
        <button class="primary" id="teams-pause">Pausar sirene e alerta</button>
        <button id="teams-snooze">Silenciar alertas por ${SNOOZE_MINUTES} min</button>
      </div>
    </div>`;
  root.append(modal);

  const $ = (sel) => (sel.startsWith('#') ? (card.querySelector(sel) || modal.querySelector(sel)) : null);
  const q = (id) => card.querySelector('#' + id);

  const render = () => {
    q('teams-enabled').checked = teams.prefs.enabled;
    q('teams-idle').checked = teams.prefs.idleCheck;
    if (document.activeElement !== q('teams-away')) q('teams-away').value = teams.prefs.awayMinutes;
    q('teams-away').disabled = !teams.prefs.idleCheck;
    const parts = [
      teams.prefs.enabled ? 'ativo' : 'desativado',
      teams.away ? 'você: ausente' : 'você: presente',
      teams.audio.ready ? 'som liberado' : 'som bloqueado (clique na página)',
    ];
    if (teams.snoozedUntil > teams.now()) parts.push(`silenciado até ${new Date(teams.snoozedUntil).toLocaleTimeString()}`);
    q('teams-status').textContent = parts.join(' · ');
    q('teams-log').textContent = logLines.join('\n');
    if (btn) {
      btn.dataset.on = String(teams.prefs.enabled && teams.snoozedUntil <= teams.now());
      btn.dataset.warn = String(teams.prefs.enabled && (teams.snoozedUntil > teams.now() || !teams.audio.ready));
      btn.title = q('teams-status').textContent;
    }
    const names = [...teams.active.values()].join(', ');
    modal.hidden = !teams.alertShown;
    modal.querySelector('#teams-modal-detail').textContent = teams.playing
      ? (names ? `Máquina(s): ${names}` : 'Teste da sirene.')
      : 'Sirene parada. ' + (names ? `Pendente em: ${names}` : '');
  };
  teams.onChange = render;
  teams.log = (msg) => {
    logLines.push(`[${new Date().toLocaleTimeString()}] ${msg}`);
    if (logLines.length > 30) logLines.shift();
    render();
  };

  q('teams-enabled').onchange = (e) => { teams.setPrefs({ enabled: e.target.checked }); askNotifyPermission(); };
  q('teams-idle').onchange = (e) => teams.setPrefs({ idleCheck: e.target.checked });
  q('teams-away').onchange = (e) => teams.setPrefs({ awayMinutes: Math.max(1, Math.min(240, Number(e.target.value) || 5)) });
  q('teams-test').onclick = async () => {
    const unlocked = await teams.audio.unlock();
    if (!unlocked) teams.log('O navegador bloqueou o audio. Verifique se esta aba/site nao esta silenciado.');
    askNotifyPermission();
    teams.test();
  };
  modal.querySelector('#teams-pause').onclick = () => teams.pause();
  modal.querySelector('#teams-snooze').onclick = () => teams.snooze();

  // Presenca do operador + liberar o som no primeiro gesto (politica de autoplay)
  for (const ev of ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart']) {
    window.addEventListener(ev, () => { void teams.audio.unlock(); teams.input(); }, { passive: true, capture: true });
  }
  window.addEventListener('focus', () => teams.input());
  const tickTimer = setInterval(() => teams.tick(), 15000);
  tickTimer.unref?.(); // (so em Node/testes; no navegador e um numero)

  // IdleDetector (Chromium): ausencia do computador inteiro, nao so desta aba
  if ('IdleDetector' in window) {
    const b = q('teams-idledetector');
    b.hidden = false;
    b.onclick = async () => {
      try {
        if ((await IdleDetector.requestPermission()) !== 'granted') return teams.log('Permissão de detecção de ausência negada.');
        const d = new IdleDetector();
        d.addEventListener('change', () => teams.setIdleState(d.userState));
        await d.start({ threshold: Math.max(60000, teams.prefs.awayMinutes * 60000) });
        teams.setIdleState(d.userState);
        teams.log('Detecção de ausência do computador ativada.');
      } catch (e) {
        teams.log('Não foi possível ativar a detecção de ausência: ' + e.message);
      }
    };
  }

  function askNotifyPermission() {
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {});
  }
  teams.notify = (title) => {
    if ('Notification' in window && Notification.permission === 'granted') {
      try { new Notification(title, { body: 'Clique para abrir o painel', requireInteraction: true }); } catch { /* ignora */ }
    }
  };
  render();
  return { render, card, modal };
}
