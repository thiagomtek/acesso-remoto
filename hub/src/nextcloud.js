import http from 'node:http';
import https from 'node:https';

export const DEFAULT_NEXTCLOUD_URL = 'https://servidor.tail074692.ts.net:8445/';

const FALLBACK_LOCAL_HOSTS = ['172.17.0.1', '192.168.16.253', '127.0.0.1'];
const RETRIABLE_NET_CODES = new Set([
  'ENOTFOUND',
  'EAI_AGAIN',
  'ECONNREFUSED',
  'ETIMEDOUT',
  'EHOSTUNREACH',
  'ENETUNREACH',
  'ECONNRESET',
]);

/** Valida se um caminho relativo e seguro para materializar em recebimentos (Windows/macOS/Linux). */
export function isSafeRelativePath(raw) {
  if (typeof raw !== 'string' || raw.length < 1 || raw.length > 500 || raw.startsWith('/')) return false;
  const normalized = raw.replaceAll('\\', '/');
  const parts = normalized.split('/');
  return !parts.some(
    (part) =>
      !part ||
      part === '.' ||
      part === '..' ||
      part.startsWith('.transacao-') ||
      /[<>:"|?*\x00-\x1f]/.test(part),
  );
}

/** Normaliza a pasta remota configurada no Nextcloud (ex.: "/" ou "/Recebimentos" -> ""). */
export function normalizeRemoteFolder(raw) {
  if (typeof raw !== 'string') return '/';
  const cleaned = raw
    .replaceAll('\\', '/')
    .split('/')
    .map((s) => s.trim())
    .filter((s) => s && s !== '.' && s !== '..' && !/[<>:"|?*\x00-\x1f]/.test(s));
  return cleaned.length ? '/' + cleaned.join('/') : '/';
}

/** Sanitiza a configuracao do Nextcloud mantendo a senha existente quando nao informada novamente. */
export function sanitizeNextcloudConfig(input = {}, existing = {}) {
  let url = typeof input.url === 'string' && input.url.trim() ? input.url.trim() : existing.url || DEFAULT_NEXTCLOUD_URL;
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') url = DEFAULT_NEXTCLOUD_URL;
  } catch {
    url = DEFAULT_NEXTCLOUD_URL;
  }
  const username =
    typeof input.username === 'string'
      ? input.username.trim().slice(0, 120)
      : String(existing.username || '').trim().slice(0, 120);
  let appPassword = existing.appPassword || '';
  if (input.clearPassword === true) {
    appPassword = '';
  } else if (typeof input.appPassword === 'string' && input.appPassword.trim().length > 0) {
    appPassword = input.appPassword.trim().slice(0, 256);
  }
  const remoteFolder =
    input.remoteFolder !== undefined
      ? normalizeRemoteFolder(input.remoteFolder)
      : normalizeRemoteFolder(existing.remoteFolder || '/');
  const enabled = typeof input.enabled === 'boolean' ? input.enabled : Boolean(existing.enabled);
  return { enabled, url, username, appPassword, remoteFolder };
}

/** Visao segura para a interface web (nunca inclui a senha/chave de aplicativo). */
export function publicNextcloudConfig(cfg, status = null) {
  const clean = cfg || {};
  return {
    enabled: Boolean(clean.enabled && clean.username && clean.appPassword),
    url: clean.url || DEFAULT_NEXTCLOUD_URL,
    username: clean.username || '',
    remoteFolder: normalizeRemoteFolder(clean.remoteFolder || '/'),
    hasPassword: Boolean(clean.appPassword && clean.appPassword.length > 0),
    lastSyncAt: status?.lastSyncAt || null,
    lastError: status?.lastError || null,
    syncing: Boolean(status?.syncing),
    processedFiles: status?.processedFiles ?? 0,
    totalFiles: status?.totalFiles ?? 0,
    lastChanges: status?.lastChanges ?? 0,
    completedRequestId: status?.completedRequestId || null,
    completedError: status?.completedError || null,
    requestId: status?.requestId || null,
  };
}

function decodeXmlEntities(str) {
  return String(str || '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

/** Extrai as entradas de um XML 207 Multi-Status retornado pelo PROPFIND do Nextcloud. */
export function parsePropfindXml(xmlText, baseHrefPath) {
  const responses = [];
  const respRegex = /<(?:[a-zA-Z0-9_-]+:)?response\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?response>/gi;
  const normBase = ('/' + String(baseHrefPath || '').replace(/^\/+|\/+$/g, '')).replace(/\/+$/, '');
  let match;
  while ((match = respRegex.exec(xmlText)) !== null) {
    const block = match[1];
    const hrefMatch = /<(?:[a-zA-Z0-9_-]+:)?href\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?href>/i.exec(block);
    if (!hrefMatch) continue;
    let rawHref = decodeXmlEntities(hrefMatch[1].trim());
    try {
      if (/^https?:\/\//i.test(rawHref)) rawHref = new URL(rawHref).pathname;
    } catch {
      /* mantem pathname */
    }
    const decodedSegments = rawHref
      .split('/')
      .filter(Boolean)
      .map((s) => {
        try {
          return decodeURIComponent(s);
        } catch {
          return s;
        }
      });
    const decodedFull = '/' + decodedSegments.join('/');
    const baseDecodedSegments = normBase
      .split('/')
      .filter(Boolean)
      .map((s) => {
        try {
          return decodeURIComponent(s);
        } catch {
          return s;
        }
      });
    const baseDecoded = '/' + baseDecodedSegments.join('/');
    if (decodedFull === baseDecoded) continue;
    if (baseDecoded !== '/' && !decodedFull.startsWith(baseDecoded + '/')) continue;
    const relPath = baseDecoded === '/' ? decodedFull.slice(1) : decodedFull.slice(baseDecoded.length + 1);
    if (!relPath) continue;

    let propBlock = block;
    const propstatRegex = /<(?:[a-zA-Z0-9_-]+:)?propstat\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?propstat>/gi;
    let psMatch;
    let found200 = false;
    while ((psMatch = propstatRegex.exec(block)) !== null) {
      const ps = psMatch[1];
      const st = /<(?:[a-zA-Z0-9_-]+:)?status\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?status>/i.exec(ps);
      if (!st || /\b200\b/.test(st[1])) {
        propBlock = ps;
        found200 = true;
        break;
      }
    }
    if (!found200 && /<(?:[a-zA-Z0-9_-]+:)?propstat\b/i.test(block)) continue;

    const rtMatch = /<(?:[a-zA-Z0-9_-]+:)?resourcetype\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?resourcetype>/i.exec(propBlock);
    const isDirectory = Boolean(rtMatch && /<(?:[a-zA-Z0-9_-]+:)?collection\b/i.test(rtMatch[1]));

    const lenMatch = /<(?:[a-zA-Z0-9_-]+:)?getcontentlength\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?getcontentlength>/i.exec(propBlock);
    const size = lenMatch ? Math.max(0, Number(lenMatch[1].trim()) || 0) : 0;

    const modMatch = /<(?:[a-zA-Z0-9_-]+:)?getlastmodified\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?getlastmodified>/i.exec(propBlock);
    const parsedTime = modMatch ? Date.parse(decodeXmlEntities(modMatch[1].trim())) : NaN;
    const mtime = Number.isFinite(parsedTime) ? parsedTime : 0;

    const etagMatch = /<(?:[a-zA-Z0-9_-]+:)?getetag\b[^>]*>([\s\S]*?)<\/(?:[a-zA-Z0-9_-]+:)?getetag>/i.exec(propBlock);
    const etag = etagMatch ? decodeXmlEntities(etagMatch[1].trim()).replace(/^W\/|["']/g, '') : '';

    responses.push({ path: relPath, isDirectory, size, mtime, etag });
  }
  return responses;
}

function encodeDavPath(relPath) {
  return String(relPath || '')
    .split('/')
    .filter(Boolean)
    .map((segment) => encodeURIComponent(segment))
    .join('/');
}

const PROPFIND_BODY = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getetag/>
  </d:prop>
</d:propfind>`;

/** Executa uma requisicao HTTP/WebDAV com fallback local quando o Nextcloud esta na mesma maquina. */
async function davRequest(targetUrl, opts = {}) {
  const parsed = new URL(targetUrl);
  const isHttps = parsed.protocol === 'https:';
  const isTailscaleHost = parsed.hostname.toLowerCase() === 'servidor.tail074692.ts.net';
  const candidates = [null, ...(isTailscaleHost ? FALLBACK_LOCAL_HOSTS : [])];

  let lastErr = null;
  for (const overrideHost of candidates) {
    try {
      return await singleDavRequest(parsed, isHttps, overrideHost, opts);
    } catch (err) {
      lastErr = err;
      if (!err || !RETRIABLE_NET_CODES.has(err.code)) throw err;
    }
  }
  throw lastErr || new Error('Falha de conexão com o Nextcloud');
}

function singleDavRequest(parsed, isHttps, overrideHost, opts) {
  const mod = isHttps ? https : http;
  const timeoutMs = opts.timeoutMs || 30_000;
  const headers = {
    Host: parsed.host,
    ...(opts.headers || {}),
  };
  if (opts.body && typeof opts.body === 'string' && !headers['Content-Length'] && !headers['content-length']) {
    headers['Content-Length'] = Buffer.byteLength(opts.body);
  }

  return new Promise((resolvePromise, rejectPromise) => {
    const reqOpts = {
      protocol: parsed.protocol,
      hostname: overrideHost || parsed.hostname,
      port: parsed.port || (isHttps ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method: opts.method || 'GET',
      headers,
      timeout: timeoutMs,
      ...(isHttps ? { servername: parsed.hostname, rejectUnauthorized: true } : {}),
    };

    const req = mod.request(reqOpts, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        resolvePromise({
          statusCode: res.statusCode || 0,
          headers: res.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
      res.on('error', rejectPromise);
    });

    req.on('timeout', () => {
      const err = new Error('Tempo limite excedido ao comunicar com o Nextcloud');
      err.code = 'ETIMEDOUT';
      req.destroy(err);
    });
    req.on('error', rejectPromise);

    if (opts.body) req.write(opts.body);
    req.end();
  });
}

/** Cliente WebDAV do Hub (usado apenas para validar credenciais na interface web). */
export class NextcloudClient {
  constructor(cfg) {
    this.cfg = sanitizeNextcloudConfig(cfg);
    this.authHeader =
      'Basic ' + Buffer.from(`${this.cfg.username}:${this.cfg.appPassword}`, 'utf8').toString('base64');
    this.resolvedBase = null;
  }

  #originAndPrefix() {
    const u = new URL(this.cfg.url);
    const cleanPath = u.pathname.replace(/\/+$/, '');
    const davIdx = cleanPath.indexOf('/remote.php/');
    const prefix = davIdx >= 0 ? cleanPath.slice(0, davIdx) : cleanPath;
    return { origin: u.origin, prefix };
  }

  /** Resolve a raiz WebDAV do usuario (/remote.php/dav/files/<user> com fallback para /remote.php/webdav). */
  async resolveDavRoot() {
    if (this.resolvedBase) return this.resolvedBase;
    const { origin, prefix } = this.#originAndPrefix();
    const candidates = [
      `${origin}${prefix}/remote.php/dav/files/${encodeURIComponent(this.cfg.username)}`,
      `${origin}${prefix}/remote.php/webdav`,
    ];

    let lastStatus = 0;
    for (const candidate of candidates) {
      const res = await davRequest(candidate + '/', {
        method: 'PROPFIND',
        headers: {
          Authorization: this.authHeader,
          Depth: '0',
          'Content-Type': 'application/xml; charset=utf-8',
        },
        body: PROPFIND_BODY,
      });
      lastStatus = res.statusCode;
      if (res.statusCode === 207 || res.statusCode === 200) {
        const folder = normalizeRemoteFolder(this.cfg.remoteFolder);
        if (folder !== '/') {
          await this.#ensureFolderSegments(candidate, folder);
          this.resolvedBase = `${candidate}/${encodeDavPath(folder)}`;
        } else {
          this.resolvedBase = candidate;
        }
        return this.resolvedBase;
      }
      if (res.statusCode === 401 || res.statusCode === 403) {
        throw new Error('Credenciais do Nextcloud recusadas (HTTP ' + res.statusCode + '). Verifique o usuário e a senha de aplicativo.');
      }
    }
    throw new Error(`Não foi possível acessar o WebDAV do Nextcloud (HTTP ${lastStatus}).`);
  }

  async #ensureFolderSegments(baseRoot, folderPath) {
    const segments = folderPath.split('/').filter(Boolean);
    let current = baseRoot;
    for (const seg of segments) {
      current += '/' + encodeURIComponent(seg);
      const check = await davRequest(current + '/', {
        method: 'PROPFIND',
        headers: { Authorization: this.authHeader, Depth: '0' },
      });
      if (check.statusCode === 404) {
        const mk = await davRequest(current + '/', {
          method: 'MKCOL',
          headers: { Authorization: this.authHeader },
        });
        if (mk.statusCode !== 201 && mk.statusCode !== 405) {
          throw new Error(`Não foi possível criar a pasta ${folderPath} no Nextcloud (HTTP ${mk.statusCode}).`);
        }
      }
    }
  }
}

/**
 * Gerenciador de estado e validacao do Nextcloud no Hub.
 *
 * A sincronizacao real de arquivos agora e executada DIRETAMENTE pelo Agente
 * via WebDAV, sem trafegar arquivos pelo Hub e sem concorrer com video/comandos.
 */
export class NextcloudSync {
  constructor({ store } = {}) {
    this.store = store;
    this.status = new Map(); // clientId -> { lastSyncAt, lastError, syncing, lastChanges }
  }

  setStatus(clientId, data = {}) {
    const prev = this.status.get(clientId) || {};
    const syncing = Boolean(data?.syncing);
    const completed = !syncing && data?.requestId && data.requestId === prev.requestId;
    this.status.set(clientId, {
      ...prev,
      lastSyncAt: !syncing && !data?.error && Number.isFinite(Number(data?.lastSyncAt)) && Number(data.lastSyncAt) > 0
        ? Number(data.lastSyncAt) : (prev.lastSyncAt || null),
      lastError: syncing ? null : (data?.error || null),
      syncing,
      processedFiles: syncing && Number.isInteger(data?.processedFiles) ? data.processedFiles : (syncing ? (prev.processedFiles ?? 0) : 0),
      totalFiles: syncing && Number.isInteger(data?.totalFiles) ? data.totalFiles : (syncing ? (prev.totalFiles ?? 0) : 0),
      lastChanges: !syncing && typeof (data?.lastChanges ?? data?.changes) === 'number'
        ? (data.lastChanges ?? data.changes) : (prev.lastChanges ?? 0),
      completedRequestId: completed ? data.requestId : (prev.completedRequestId || null),
      completedError: completed ? (data?.error || null) : (prev.completedError || null),
    });
  }

  markRequested(clientId, requestId) {
    const prev = this.status.get(clientId) || {};
    this.status.set(clientId, { ...prev, requestId, requestedAt: Date.now(), completedRequestId: null,
      completedError: null, lastError: null, processedFiles: 0, totalFiles: 0 });
  }

  hasPendingRequest(clientId) {
    const state = this.status.get(clientId);
    return Boolean(state?.requestId && state.requestId !== state.completedRequestId
      && Date.now() - state.requestedAt < 120_000);
  }

  getStatus(clientId) {
    return this.status.get(clientId) || null;
  }

  getPublicConfig(clientId) {
    const client = this.store?.get(clientId);
    return publicNextcloudConfig(client?.nextcloud, this.getStatus(clientId));
  }

  /** Valida as credenciais no Nextcloud antes de ativar/salvar. */
  async testConfig(cfg) {
    const clean = sanitizeNextcloudConfig(cfg);
    if (!clean.username || !clean.appPassword) {
      throw new Error('Informe o usuário e a chave/senha de aplicativo do Nextcloud.');
    }
    const endpoint = new URL(clean.url);
    const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(endpoint.hostname);
    if (endpoint.protocol !== 'https:' && !(endpoint.protocol === 'http:' && loopback)) {
      throw new Error('O Nextcloud exige HTTPS com certificado válido.');
    }
    const client = new NextcloudClient(clean);
    await client.resolveDavRoot();
    return true;
  }

  close() {}
}
