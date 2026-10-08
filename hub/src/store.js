import { createHash, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';

// Configuracoes por client, editadas no painel web e empurradas ao client.
// Substituem as opcoes que antes ficavam na janela do client.
export const DEFAULT_SETTINGS = {
  allowRemoteControl: true,
  clipboardSync: true,
  keepAwake: false,
  teamsWatcher: false,
  startWithSystem: true,
  sharedFolderSync: true,
  quality: 'auto', // auto | max | balanced | economy
  localOnly: false,
  webrtcEnabled: true,
  nativeKeyboardHelper: true,
  autoUpdate: true,
};

const VALIDATORS = {
  // Controle remoto e a finalidade do produto; abas antigas nao podem desativa-lo.
  allowRemoteControl: (v) => v === true,
  clipboardSync: (v) => typeof v === 'boolean',
  keepAwake: (v) => typeof v === 'boolean',
  teamsWatcher: (v) => typeof v === 'boolean',
  startWithSystem: (v) => typeof v === 'boolean',
  // A pasta compartilhada substitui a transferencia avulsa e faz parte do produto.
  // Rejeita `false` inclusive de uma aba antiga que ainda esteja em cache.
  sharedFolderSync: (v) => v === true,
  quality: (v) => ['auto', 'max', 'balanced', 'economy'].includes(v),
  localOnly: (v) => typeof v === 'boolean',
  webrtcEnabled: (v) => typeof v === 'boolean',
  nativeKeyboardHelper: (v) => typeof v === 'boolean',
  autoUpdate: (v) => typeof v === 'boolean',
};

/** Mantem so chaves conhecidas com valores validos. */
export function sanitizeSettings(input) {
  const out = {};
  for (const [k, v] of Object.entries(input || {})) {
    if (Object.hasOwn(VALIDATORS, k) && VALIDATORS[k](v)) out[k] = v;
  }
  return out;
}

const ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const sha256 = (s) => createHash('sha256').update(s).digest();

export class Store {
  constructor(dir) {
    this.file = join(dir, 'clients.json');
    this.logsDir = join(dir, 'agent-logs');
    this.agentLogSeen = new Map();
    mkdirSync(dir, { recursive: true });
    mkdirSync(this.logsDir, { recursive: true });
    this.clients = existsSync(this.file) ? JSON.parse(readFileSync(this.file, 'utf8')) : {};
    // Preferencias removidas nao podem sobreviver em maquinas antigas: arquivos vao para a pasta ativa,
    // e o experimento de modo servico/driver virtual foi descontinuado.
    let migrated = false;
    for (const c of Object.values(this.clients)) {
      if (!c.settings) { c.settings = { ...DEFAULT_SETTINGS }; migrated = true; }
      if (c.settings.allowRemoteControl !== true) {
        c.settings.allowRemoteControl = true;
        migrated = true;
      }
      delete c.settings?.receivedFilesDir;
      // A pasta compartilhada substituiu o fluxo antigo de envio avulso. Maquinas ja
      // cadastradas precisam recebe-la tambem; antes a chave ausente era interpretada
      // pelo agente como "desligada" e a sincronizacao jamais iniciava.
      if (c.settings && c.settings.sharedFolderSync !== true) {
        c.settings.sharedFolderSync = true;
        migrated = true;
      }
      if (Object.hasOwn(c.settings || {}, 'serviceMode')) {
        delete c.settings.serviceMode;
        migrated = true;
      }
      for (const [k, v] of Object.entries(DEFAULT_SETTINGS)) {
        if (!Object.hasOwn(c.settings, k)) {
          c.settings[k] = v;
          migrated = true;
        }
      }
    }
    if (migrated) this.#save();
  }

  #save() {
    const tmp = this.file + '.tmp';
    writeFileSync(tmp, JSON.stringify(this.clients, null, 2));
    renameSync(tmp, this.file);
  }

  has(id) {
    return typeof id === 'string' && Object.hasOwn(this.clients, id);
  }

  get(id) {
    return this.has(id) ? this.clients[id] : null;
  }

  pendingCount() {
    return this.list().filter((c) => !c.approved).length;
  }

  /**
   * Registro automatico (trust on first use): o client gera o proprio ID e segredo
   * e se apresenta na primeira conexao. So o hash do segredo e guardado.
   * Devolve null se o ID ja existe ou os dados sao invalidos.
   */
  enroll(id, token, name, approved, ownerId = '') {
    if (!ID_RE.test(id || '') || typeof token !== 'string' || token.length < 32 || token.length > 128) return null;
    if (this.has(id)) return null;
    this.clients[id] = {
      id,
      name: String(name || 'client').slice(0, 80),
      tokenHash: sha256(token).toString('hex'),
      approved: !!approved,
      settings: { ...DEFAULT_SETTINGS },
      createdAt: Date.now(),
      lastSeen: null,
      info: {},
      ...(typeof ownerId === 'string' && ownerId ? { ownerId } : {}),
    };
    this.#save();
    return this.clients[id];
  }

  approve(id) {
    const c = this.clients[id];
    if (!c) return null;
    c.approved = true;
    this.#save();
    return c;
  }

  authenticate(id, token) {
    const c = typeof id === 'string' ? this.clients[id] : null;
    if (!c || typeof token !== 'string') return null;
    const a = Buffer.from(c.tokenHash, 'hex');
    const b = sha256(token);
    return a.length === b.length && timingSafeEqual(a, b) ? c : null;
  }

  update(id, patch) {
    const c = this.clients[id];
    if (!c) return null;
    if (patch.name) c.name = String(patch.name).slice(0, 80);
    if (patch.settings) c.settings = { ...c.settings, ...sanitizeSettings(patch.settings) };
    if (patch.info) c.info = patch.info;
    if (patch.lastSeen) c.lastSeen = patch.lastSeen;
    this.#save();
    return c;
  }

  /** Associação administrativa persistente. Não altera identidade, segredo ou rota do agente. */
  assignOwner(id, ownerId) {
    const c = this.clients[id];
    if (!c || typeof ownerId !== 'string' || !ownerId) return null;
    c.ownerId = ownerId;
    this.#save();
    return c;
  }

  /** Migração inicial: máquinas legadas sem conta passam à primeira conta administrativa definida. */
  assignUnowned(ownerId) {
    let changed = false;
    for (const c of Object.values(this.clients)) {
      if (!c.ownerId) { c.ownerId = ownerId; changed = true; }
    }
    if (changed) this.#save();
    return changed;
  }

  /** Historico de atualizacao da maquina (ultimos 100 eventos). */
  addHistory(id, entry) {
    const c = this.clients[id];
    if (!c) return null;
    const clean = { t: Date.now(), type: String(entry.type || '').slice(0, 20) };
    for (const k of ['from', 'to']) if (entry[k]) clean[k] = String(entry[k]).slice(0, 16);
    if (entry.text) clean.text = String(entry.text).slice(0, 200);
    c.history = [...(c.history || []), clean].slice(-100);
    this.#save();
    return clean;
  }

  /** Log técnico centralizado append-only, sem conteúdo de teclado/clipboard/arquivos. */
  appendAgentLog(id, entry) {
    if (!this.clients[id]) return false;
    const file = join(this.logsDir, `${id}.jsonl`);
    try {
      if (entry.id) {
        if (!this.agentLogSeen.has(id)) {
          const loaded = new Set();
          for (const source of [file, file + '.1']) if (existsSync(source)) {
            for (const line of readFileSync(source, 'utf8').split('\n')) {
              try { const old = JSON.parse(line); if (old.id) loaded.add(old.id); } catch { /* linha parcial antiga */ }
            }
          }
          this.agentLogSeen.set(id, loaded);
        }
        const seen = this.agentLogSeen.get(id);
        if (seen.has(entry.id)) return true;
      }
      const clean = { t: Date.now(), level: ['info', 'warn', 'error'].includes(entry.level) ? entry.level : 'info', code: String(entry.code || 'agent').slice(0, 32), detail: String(entry.detail || '').slice(0, 240) };
      if (entry.id) clean.id = String(entry.id).slice(0, 36);
      appendFileSync(file, JSON.stringify(clean) + '\n', 'utf8');
      if (entry.id) this.agentLogSeen.get(id).add(entry.id);
      return true;
    } catch {
      return false;
    }
  }

  /** true se esta e a primeira vez que oferecemos este alvo a esta maquina (evita repetir no historico). */
  markOffered(id, hash) {
    const c = this.clients[id];
    if (!c || c.lastOfferedHash === hash) return false;
    c.lastOfferedHash = hash;
    this.#save();
    return true;
  }

  revoke(id) {
    if (!this.clients[id]) return false;
    delete this.clients[id];
    this.#save();
    return true;
  }

  list() {
    return Object.values(this.clients);
  }
}
