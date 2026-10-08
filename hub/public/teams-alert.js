// Alerta de atividade no Teams das maquinas remotas (regra migrada do Servidor original):
//  - o agente so avisa QUE houve atividade nova (nao le o conteudo); o painel toca uma sirene;
//  - com "verificar inatividade" (padrao): so toca se o operador estiver AUSENTE (sem mexer ha N min,
//    padrao 5); se esta presente, so registra no log;
//  - a sirene para quando: o operador pausa/fecha, volta a mexer, ou a atividade e lida na maquina;
//  - "silenciar por 30 min" suprime os proximos alertas;
//  - o alerta pode ser desligado e a sirene testada.

export const SNOOZE_MINUTES = 30;
export const DEFAULT_PREFS = { enabled: true, idleCheck: true, awayMinutes: 5 };

/** Decisao pura (testavel): tocar a sirene agora? */
export function decide({ enabled, idleCheck, away, snoozedUntil, now }) {
  if (!enabled) return { play: false, reason: 'Alerta do Teams desativado - sem sirene.' };
  if (now < snoozedUntil) return { play: false, reason: `Alerta do Teams silenciado por mais ${Math.ceil((snoozedUntil - now) / 60000)} min - sem sirene.` };
  if (!idleCheck) return { play: true, reason: 'Chegou atividade nova no Teams.' };
  if (!away) return { play: false, reason: 'Voce esta presente - sem sirene.' };
  return { play: true, reason: 'Chegou atividade nova no Teams enquanto voce estava ausente.' };
}

/** Sirene de duas notas (Web Audio), sempre no volume maximo do navegador. */
export class WebAudioSiren {
  constructor() {
    this.ctx = null;
    this.osc = null;
    this.timer = null;
  }

  /** Precisa ser chamado depois de um gesto do usuario (politica de autoplay do navegador). */
  async unlock() {
    const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AC) return false;
    this.ctx ??= new AC();
    try {
      if (this.ctx.state === 'suspended') await this.ctx.resume();
    } catch {
      return false;
    }
    return this.ctx.state === 'running';
  }

  get ready() { return !!this.ctx && this.ctx.state === 'running'; }
  get playing() { return !!this.osc; }

  start() {
    if (this.osc || !this.ctx || this.ctx.state !== 'running') return false;
    const gain = this.ctx.createGain();
    gain.gain.value = 1.0;
    gain.connect(this.ctx.destination);
    const osc = this.ctx.createOscillator();
    osc.type = 'square';
    osc.connect(gain);
    osc.start();
    this.osc = osc;
    let high = false;
    const tick = () => { osc.frequency.setValueAtTime(high ? 950 : 650, this.ctx.currentTime); high = !high; };
    tick();
    this.timer = setInterval(tick, 450);
    return true;
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
    try { this.osc?.stop(); this.osc?.disconnect(); } catch { /* ja parado */ }
    this.osc = null;
  }
}

/**
 * Controlador do alerta. Dependencias injetadas (para testar sem navegador): audio, clock, notify, storage, log.
 */
export class TeamsAlert {
  constructor({ audio, now = () => Date.now(), notify = () => {}, storage = null, log = () => {}, onChange = () => {} } = {}) {
    this.audio = audio;
    this.now = now;
    this.notify = notify;
    this.storage = storage;
    this.log = log;
    this.onChange = onChange;
    this.prefs = { ...DEFAULT_PREFS, ...this.load() };
    this.snoozedUntil = 0;
    this.lastInput = now();
    this.idleState = null; // 'active' | 'idle' quando a IdleDetector (sistema inteiro) esta em uso
    this.active = new Map(); // clientId -> nome (maquinas com atividade pendente)
    this.alertShown = false;
    this.wasAway = false;
  }

  load() {
    try { return JSON.parse(this.storage?.getItem('teamsAlertPrefs') || '{}'); } catch { return {}; }
  }

  setPrefs(patch) {
    this.prefs = { ...this.prefs, ...patch };
    try { this.storage?.setItem('teamsAlertPrefs', JSON.stringify(this.prefs)); } catch { /* sem storage */ }
    this.onChange();
  }

  /** Operador ausente = IdleDetector (sistema) diz idle, ou nenhum input na pagina ha N min. */
  get away() {
    if (this.idleState) return this.idleState === 'idle';
    return this.now() - this.lastInput >= this.prefs.awayMinutes * 60000;
  }

  /** Chamar a cada mouse/teclado/foco na pagina. */
  input() {
    const wasAway = this.away;
    this.lastInput = this.now();
    if (wasAway && !this.away) this.onBack();
  }

  setIdleState(state) {
    const wasAway = this.away;
    this.idleState = state;
    if (wasAway && !this.away) this.onBack();
    this.onChange();
  }

  /** Verificacao periodica (a ausencia por tempo nao gera evento sozinha). */
  tick() {
    const away = this.away;
    if (away !== this.wasAway) {
      this.wasAway = away;
      this.log(away ? 'Operador ausente (sem mouse/teclado).' : 'Operador voltou.');
      this.onChange();
    }
  }

  onBack() {
    this.wasAway = false;
    this.log('Operador voltou.');
    if (this.audio.playing) {
      this.audio.stop();
      this.log('Sirene parada: voce voltou.');
      this.alertShown = true; // o modal continua para o operador ver o que aconteceu
    }
    this.onChange();
  }

  /** Evento do agente: atividade nova (active=true) ou lida/limpa (active=false). */
  teamsEvent(clientId, name, active) {
    if (!active) {
      this.active.delete(clientId);
      if (this.active.size === 0) {
        if (this.audio.playing) {
          this.audio.stop();
          this.log('Sirene parada: a atividade do Teams foi lida na maquina.');
        }
        this.alertShown = false;
      }
      this.onChange();
      return;
    }
    this.active.set(clientId, name);
    const d = decide({ enabled: this.prefs.enabled, idleCheck: this.prefs.idleCheck, away: this.away, snoozedUntil: this.snoozedUntil, now: this.now() });
    this.log(`${name}: ${d.reason}`);
    if (d.play) this.sound(`Nova atividade no Teams: ${name}`);
    this.onChange();
  }

  sound(title) {
    this.alertShown = true;
    if (!this.audio.ready) this.log('Som bloqueado pelo navegador: clique uma vez na pagina para liberar a sirene.');
    if (!this.audio.start()) this.log('Sirene nao iniciou: clique em "Testar sirene" para liberar o audio desta pagina.');
    this.notify(title);
    this.onChange();
  }

  /** "Pausar sirene e alerta". */
  pause() {
    if (this.audio.playing) {
      this.audio.stop();
      this.log('Sirene pausada pelo usuario.');
    }
    this.alertShown = false;
    this.onChange();
  }

  /** "Silenciar alertas por 30 min". */
  snooze() {
    this.snoozedUntil = this.now() + SNOOZE_MINUTES * 60000;
    this.log(`Alertas do Teams silenciados por ${SNOOZE_MINUTES} min.`);
    this.pause();
  }

  test() {
    this.sound('Teste da sirene');
    this.log('Teste da sirene.');
  }

  get playing() { return this.audio.playing; }
}
