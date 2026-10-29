import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import webpush from 'web-push';

// Web Push (PWA): guarda as inscricoes dos dispositivos de cada conta e avisa quando chega
// atividade no Teams de uma maquina. As chaves VAPID sao geradas uma vez e ficam em data/.

const MAX_PER_USER = 20;
const MIN_INTERVAL_MS = 30_000; // por maquina: evita rajada de notificacoes

const isEndpoint = (v) => typeof v === 'string' && v.length < 2048 && /^https:\/\//.test(v);
const isB64 = (v) => typeof v === 'string' && v.length > 0 && v.length < 512 && /^[A-Za-z0-9_-]+$/.test(v);

export function validSubscription(s) {
  return !!s && isEndpoint(s.endpoint) && isB64(s.keys?.p256dh) && isB64(s.keys?.auth);
}

export class PushService {
  /** @param {string} dir  @param {{send?: Function}} [opts] send injetavel para testes */
  constructor(dir, { send, subject = 'mailto:admin@localhost', now = () => Date.now() } = {}) {
    mkdirSync(dir, { recursive: true });
    this.file = join(dir, 'push.json');
    this.now = now;
    this.sendFn = send || ((sub, payload, options) => webpush.sendNotification(sub, payload, options));
    this.subject = subject;
    this.lastSent = new Map();
    this.data = this.#load();
    if (!this.data.vapid) {
      this.data.vapid = webpush.generateVAPIDKeys();
      this.#save();
    }
  }

  #load() {
    try {
      if (existsSync(this.file)) return JSON.parse(readFileSync(this.file, 'utf8'));
    } catch { /* arquivo ilegivel: recomeca */ }
    return { vapid: null, subs: {} };
  }

  #save() {
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  get publicKey() { return this.data.vapid.publicKey; }

  subscribe(userId, subscription, label = '') {
    if (!userId || !validSubscription(subscription)) return false;
    const list = (this.data.subs[userId] ||= []).filter((s) => s.endpoint !== subscription.endpoint);
    list.push({
      endpoint: subscription.endpoint,
      keys: { p256dh: subscription.keys.p256dh, auth: subscription.keys.auth },
      label: String(label || '').slice(0, 80),
      createdAt: this.now(),
    });
    this.data.subs[userId] = list.slice(-MAX_PER_USER);
    this.#save();
    return true;
  }

  unsubscribe(userId, endpoint) {
    const list = this.data.subs[userId] || [];
    const next = list.filter((s) => s.endpoint !== endpoint);
    if (next.length === list.length) return false;
    this.data.subs[userId] = next;
    this.#save();
    return true;
  }

  has(userId, endpoint) {
    return (this.data.subs[userId] || []).some((s) => s.endpoint === endpoint);
  }

  count(userId) { return (this.data.subs[userId] || []).length; }

  /** Avisa todos os dispositivos da conta. `key` limita a frequencia (ex.: id da maquina). Retorna quantos enviou. */
  async notify(userId, payload, { key = '', force = false } = {}) {
    const subs = this.data.subs[userId] || [];
    if (subs.length === 0) return 0;
    const throttleKey = `${userId}:${key}`;
    if (!force && key && this.now() - (this.lastSent.get(throttleKey) || 0) < MIN_INTERVAL_MS) return 0;
    if (key) this.lastSent.set(throttleKey, this.now());
    const body = JSON.stringify(payload);
    const options = {
      TTL: 120, urgency: 'high',
      vapidDetails: { subject: this.subject, publicKey: this.data.vapid.publicKey, privateKey: this.data.vapid.privateKey },
    };
    let sent = 0;
    const dead = [];
    await Promise.all(subs.map(async (s) => {
      try {
        await this.sendFn({ endpoint: s.endpoint, keys: s.keys }, body, options);
        sent++;
      } catch (e) {
        if (e?.statusCode === 404 || e?.statusCode === 410) dead.push(s.endpoint);
      }
    }));
    if (dead.length) {
      this.data.subs[userId] = subs.filter((s) => !dead.includes(s.endpoint));
      this.#save();
    }
    return sent;
  }
}
