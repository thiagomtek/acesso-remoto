import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import archiver from 'archiver';
import { config, validateConfig } from './config.js';
import { Store } from './store.js';
import { authenticateAgentEdge, iceServers } from './auth.js';
import { Accounts } from './accounts.js';
import { Updates, isHex64 } from './updates.js';
import { createReadStream, createWriteStream, existsSync, statSync, unlinkSync, mkdirSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { basename, dirname, relative } from 'node:path';

const PUBLIC_DIR = resolve(fileURLToPath(new URL('../public', import.meta.url)));
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
};

export function createHub(opts = {}) {
  const store = opts.store || new Store(config.dataDir);
  const accounts = opts.accounts || new Accounts(config.dataDir);
  // Migração idempotente dos agentes que já existiam antes das contas. Fazer isso dentro do
  // processo evita que uma instância antiga sobrescreva uma edição externa ao encerrar.
  const soleOwnerId = accounts.onlyUserId();
  if (soleOwnerId) store.assignUnowned(soleOwnerId);
  const updates = opts.updates || new Updates(config.updatesDir || join(config.dataDir, 'updates'));
  const transferDir = join(config.dataDir, 'transfers');
  const sharedDir = join(config.dataDir, 'shared');
  const installerBaseDir = join(config.dataDir, 'installer-base');
  mkdirSync(transferDir, { recursive: true });
  mkdirSync(sharedDir, { recursive: true });
  /** id -> { path, sessionId, clientId, operatorEmail, name, size, fromAgent, createdAt } */
  const transfers = new Map();
  /** clientId -> { ws, alive } */
  const agents = new Map();
  /** sessionId -> { clientId, operatorWs } */
  const sessions = new Map();
  /** operadores conectados (para broadcast da lista) */
  const operators = new Set();
  /** Protege o hub contra agentes antigos que repetem a mesma falha em loop. */
  const agentLogThrottle = new Map();
  /** tickets de curta duracao: permitem ao navegador autenticado trocar o WebSocket para a LAN. */
  const lanTickets = new Map();
  const LAN_TICKET_TTL_MS = 30_000;
  const account = (headers) => accounts.session(headers?.cookie);

  const localIngress = (headers) => headers['x-transacao-lan'] === '1'
    && String(headers.host || '').toLowerCase() === config.lanHost;
  const issueLanTicket = (operator) => {
    if (!config.lanOperatorUrl) return null;
    const ticket = randomUUID();
    lanTickets.set(ticket, { ...operator, expiresAt: Date.now() + LAN_TICKET_TTL_MS });
    return ticket;
  };
  const consumeLanTicket = (ticket) => {
    const entry = lanTickets.get(ticket);
    lanTickets.delete(ticket);
    return entry && entry.expiresAt >= Date.now() ? entry : null;
  };

  const send = (ws, msg) => {
    if (ws?.readyState === 1) {
      ws.send(JSON.stringify(msg));
      return true;
    }
    return false;
  };

  const safeFileName = (value) => {
    const name = String(value || 'arquivo').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^\.+$/, 'arquivo').trim();
    return (name || 'arquivo').slice(0, 180);
  };
  const headerName = (value) => {
    try { return decodeURIComponent(String(value || 'arquivo')); } catch { return 'arquivo'; }
  };
  const readUpload = async (req, path, maxBytes = 512 * 1024 * 1024) => {
    const out = createWriteStream(path, { flags: 'wx' });
    let size = 0;
    try {
      for await (const part of req) {
        size += part.length;
        if (size > maxBytes) throw new Error('arquivo grande demais');
        if (!out.write(part)) await new Promise((resolve, reject) => { out.once('drain', resolve); out.once('error', reject); });
      }
      await new Promise((resolve, reject) => out.end((e) => e ? reject(e) : resolve()));
      return size;
    } catch (e) {
      out.destroy();
      try { unlinkSync(path); } catch { /* parcial */ }
      throw e;
    }
  };

  // A pasta compartilhada nunca aceita um caminho arbitrario do HTTP. O identificador da
  // maquina ja e validado pelo Store e o caminho final precisa continuar abaixo dela.
  const sharedRoot = (clientId) => join(sharedDir, clientId);
  const sharedPath = (clientId, raw) => {
    if (!store.has(clientId) || typeof raw !== 'string' || raw.length < 1 || raw.length > 500) return null;
    const root = resolve(sharedRoot(clientId));
    const normalized = raw.replaceAll('\\', '/');
    const parts = normalized.split('/');
    // Alem de traversal, rejeita nomes que o Windows nao conseguiria materializar em recebimentos.
    if (parts.some((part) => !part || part === '.' || part === '..' || /[<>:"|?*\x00-\x1f]/.test(part))) return null;
    const target = resolve(root, normalized);
    return target.startsWith(root + sep) ? target : null;
  };
  const sharedManifest = (clientId) => {
    const root = sharedRoot(clientId);
    if (!existsSync(root)) return [];
    const out = [];
    const visit = (dir) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.name.startsWith('.transacao-')) continue;
        const file = join(dir, entry.name);
        if (entry.isDirectory()) visit(file);
        else if (entry.isFile()) {
          const st = statSync(file);
          out.push({ path: relative(root, file).replaceAll('\\', '/'), size: st.size, mtime: st.mtimeMs });
        }
      }
    };
    visit(root);
    return out.sort((a, b) => a.path.localeCompare(b.path));
  };
  const sharedAccess = async (req, clientId) => {
    const user = account(req.headers);
    if (user && accounts.owns(user.id, store.get(clientId))) return { operator: user };
    const agentId = String(req.headers['x-client-id'] || '');
    const agentOk = (await authenticateAgentEdge(req.headers))
      && agentId === clientId
      && !!store.authenticate(agentId, String(req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
    return agentOk ? { agent: true } : null;
  };

  const publicClient = (c) => ({
    id: c.id,
    name: c.name,
    online: agents.has(c.id),
    approved: !!c.approved,
    lastSeen: c.lastSeen,
    info: c.info,
    stats: agents.get(c.id)?.stats || null,
    settings: c.settings,
    history: (c.history || []).slice(-30),
  });

  const latestAgent = () => updates.meta()?.jarSha256?.slice(0, 8) || '';
  const broadcastClients = () => {
    const latest = latestAgent();
    for (const op of operators) {
      const user = op.role?.user;
      send(op, { type: 'clients', clients: store.list().filter((c) => accounts.owns(user?.id, c)).map(publicClient), latestAgent: latest });
    }
  };

  /** Oferece ao agente o pacote publicado se o jar dele for diferente (so maquinas aprovadas). */
  function offerUpdate(id, entry, manual = false) {
    const meta = updates.meta();
    const approved = store.get(id)?.approved;
    if (!meta || !approved || !entry.jarSha256) {
      if (manual) send(entry.ws, { type: 'update-status', status: 'unavailable' });
      return;
    }
    if (entry.jarSha256 === meta.jarSha256) {
      if (manual) send(entry.ws, { type: 'update-status', status: 'up-to-date' });
      return;
    }
    if (!manual && Date.now() - (entry.lastOffer || 0) < 5 * 60_000) return; // nao insiste a cada instante
    entry.lastOffer = Date.now();
    if (store.markOffered(id, meta.jarSha256)) {
      store.addHistory(id, { type: 'offered', from: entry.jarSha256.slice(0, 8), to: meta.jarSha256.slice(0, 8) });
      broadcastClients();
    }
    send(entry.ws, { type: 'update', version: meta.version, jarSha256: meta.jarSha256, zipSha256: meta.zipSha256, size: meta.size });
  }

  function closeSessionsOf(pred) {
    for (const [sid, s] of sessions) {
      if (!pred(s)) continue;
      sessions.delete(sid);
      if (s.paused) agents.get(s.clientId)?.ws._socket?.resume();
      const agent = agents.get(s.clientId);
      if (agent) send(agent.ws, { type: 'session-close', sessionId: sid });
      send(s.operatorWs, { type: 'session-closed', sessionId: sid });
    }
  }

  // ---------- HTTP (painel estatico + health) ----------
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/healthz') {
      res.writeHead(200).end('ok');
      return;
    }
    if (url.pathname.startsWith('/api/auth/')) {
      if (url.pathname === '/api/auth/me' && req.method === 'GET') {
        const user = account(req.headers); res.writeHead(user ? 200 : 401, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify({ user })); return;
      }
      if (url.pathname === '/api/auth/logout' && req.method === 'POST') {
        accounts.logout(req.headers.cookie); res.writeHead(204, { 'set-cookie': 'hub_session=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0' }).end(); return;
      }
      if (url.pathname === '/api/auth/password' && req.method === 'POST') {
        const signedIn = account(req.headers);
        if (!signedIn) { res.writeHead(401).end(JSON.stringify({ error: 'nao-autenticado' })); return; }
        let raw = ''; for await (const part of req) { raw += part; if (raw.length > 16_384) break; }
        const body = (() => { try { return JSON.parse(raw); } catch { return {}; } })();
        try {
          accounts.changePassword(signedIn.id, body.currentPassword, body.newPassword);
          accounts.revokeOtherSessions(signedIn.id, req.headers.cookie);
          res.writeHead(204, { 'cache-control': 'no-store' }).end();
        } catch (e) {
          res.writeHead(400, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify({ error: e.message }));
        }
        return;
      }
      let raw = ''; for await (const part of req) { raw += part; if (raw.length > 16_384) break; }
      const body = (() => { try { return JSON.parse(raw); } catch { return {}; } })();
      try {
        const registered = url.pathname === '/api/auth/register';
        const user = registered ? accounts.register(body.email, body.password)
          : url.pathname === '/api/auth/login' ? accounts.login(body.email, body.password) : null;
        if (!user) { res.writeHead(401).end(JSON.stringify({ error: 'credenciais-invalidas' })); return; }
        // Migra apenas a primeira conta: evita que um cadastro posterior capture máquinas existentes.
        if (registered && Object.keys(accounts.data.users).length === 1) store.assignUnowned(user.id);
        const token = accounts.createSession(user.id);
        res.writeHead(url.pathname === '/api/auth/register' ? 201 : 200, { 'content-type': 'application/json', 'cache-control': 'no-store', 'set-cookie': `hub_session=${token}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=604800` }).end(JSON.stringify({ user }));
      } catch (e) { res.writeHead(400, { 'content-type': 'application/json' }).end(JSON.stringify({ error: e.message })); }
      return;
    }
    // Instalador personalizado da conta. O ZIP base fica no volume persistente, fora da imagem;
    // somente agent.properties e refeito em memoria com uma matricula aleatoria de uso unico.
    if (url.pathname === '/api/agent-installer' && req.method === 'GET') {
      const user = account(req.headers);
      const propertiesPath = join(installerBaseDir, 'agent.properties');
      const jarPath = join(installerBaseDir, 'transacao-agent.jar');
      if (!user) { res.writeHead(401).end('unauthorized'); return; }
      if (!existsSync(propertiesPath) || !existsSync(jarPath)) {
        res.writeHead(503, { 'content-type': 'application/json', 'cache-control': 'no-store' })
          .end(JSON.stringify({ error: 'instalador-indisponivel' }));
        return;
      }
      const token = accounts.createEnrollment(user.id);
      const baseProperties = await readFile(propertiesPath, 'utf8');
      const properties = baseProperties.split(/\r?\n/)
        .filter((line) => !line.startsWith('account.enrollmentToken='))
        .join('\r\n').replace(/\r?\n?$/, '\r\n') + `account.enrollmentToken=${token}\r\n`;
      res.writeHead(200, {
        'content-type': 'application/zip',
        'content-disposition': "attachment; filename*=UTF-8''transacao-agent-instalador.zip",
        'cache-control': 'no-store, private',
        'x-content-type-options': 'nosniff',
      });
      const zip = archiver('zip', { zlib: { level: 6 } });
      zip.on('error', () => { if (!res.destroyed) res.destroy(); });
      zip.pipe(res);
      zip.glob('**/*', { cwd: installerBaseDir, dot: false, ignore: ['agent.properties'] });
      zip.append(properties, { name: 'agent.properties' });
      void zip.finalize();
      return;
    }
    // Pasta compartilhada persistente por maquina. O navegador a administra, e o agente a
    // sincroniza com <usuario>/recebimentos quando a opcao da maquina estiver ligada.
    if (url.pathname === '/shared/manifest' && req.method === 'GET') {
      const clientId = String(url.searchParams.get('clientId') || '');
      if (!await sharedAccess(req, clientId)) { res.writeHead(403).end('forbidden'); return; }
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
        .end(JSON.stringify({ files: sharedManifest(clientId) }));
      return;
    }
    if (url.pathname === '/shared/file') {
      const clientId = String(url.searchParams.get('clientId') || '');
      const path = sharedPath(clientId, String(url.searchParams.get('path') || ''));
      if (!path || !await sharedAccess(req, clientId)) { res.writeHead(403).end('forbidden'); return; }
      if (req.method === 'GET') {
        if (!existsSync(path) || !statSync(path).isFile()) { res.writeHead(404).end('not found'); return; }
        const st = statSync(path);
        res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': st.size,
          'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(basename(path))}`, 'cache-control': 'no-store' });
        createReadStream(path).pipe(res);
        return;
      }
      if (req.method === 'DELETE') {
        if (existsSync(path)) rmSync(path, { recursive: statSync(path).isDirectory(), force: true });
        res.writeHead(204).end();
        return;
      }
      if (req.method === 'PUT') {
        mkdirSync(dirname(path), { recursive: true });
        const temp = join(dirname(path), `.transacao-${randomUUID()}.upload`);
        try {
          const size = await readUpload(req, temp);
          renameSync(temp, path); // publicacao atomica: o agente nunca baixa arquivo parcial
          res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ size }));
        } catch { res.writeHead(413).end('upload failed'); }
        return;
      }
      res.writeHead(405).end('method not allowed');
      return;
    }
    if (url.pathname === '/shared/archive' && req.method === 'GET') {
      const clientId = String(url.searchParams.get('clientId') || '');
      const path = sharedPath(clientId, String(url.searchParams.get('path') || ''));
      if (!path || !await sharedAccess(req, clientId)) { res.writeHead(403).end('forbidden'); return; }
      if (!existsSync(path) || !statSync(path).isDirectory()) { res.writeHead(404).end('not found'); return; }
      const name = safeFileName(basename(path)) + '.zip';
      res.writeHead(200, {
        'content-type': 'application/zip',
        'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
        'cache-control': 'no-store',
      });
      const zip = archiver('zip', { zlib: { level: 9 } });
      zip.on('warning', () => { /* entrada que desapareceu durante a compactacao: segue com as demais */ });
      zip.on('error', () => { if (!res.destroyed) res.destroy(); });
      zip.pipe(res);
      zip.directory(path, basename(path));
      void zip.finalize();
      return;
    }
    // Transferencia de arquivos: o hub so guarda o arquivo pelo tempo da sessao e entrega ao outro lado.
    // O browser nunca ganha acesso ao disco do Windows/macOS; o agente escolhe a Area de Trabalho local.
    if (url.pathname === '/transfers/upload' && req.method === 'POST') {
      const sessionId = String(req.headers['x-transfer-session'] || '');
      const session = sessions.get(sessionId);
      const operator = account(req.headers);
      const agentId = String(req.headers['x-client-id'] || '');
      const agentOk = (await authenticateAgentEdge(req.headers))
        && !!store.authenticate(agentId, String(req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
      const fromAgent = agentOk && session?.clientId === agentId;
      const fromOperator = !!operator && session?.operatorEmail === operator.email;
      if (!session || (!fromAgent && !fromOperator)) {
        res.writeHead(403).end('forbidden');
        return;
      }
      // Nao aceite um upload do operador que nao tenha um agente conectado para recebe-lo. Antes,
      // o HTTP 201 fazia a interface prometer entrega mesmo que o comando nao tivesse destino.
      if (fromOperator && !agents.get(session.clientId)?.ws) {
        res.writeHead(409).end('agent offline');
        return;
      }
      const id = randomUUID();
      const path = join(transferDir, id + '.bin');
      const name = safeFileName(headerName(req.headers['x-transfer-name']));
      const kind = req.headers['x-transfer-kind'] === 'clipboard-image' ? 'clipboard-image' : 'file';
      try {
        const size = await readUpload(req, path, kind === 'clipboard-image' ? 25 * 1024 * 1024 : undefined);
        if (kind === 'clipboard-image' && (!fromOperator || size < 1 || size > 25 * 1024 * 1024)) throw new Error('invalid clipboard image');
        const item = { path, sessionId, clientId: session.clientId, operatorEmail: session.operatorEmail, name, size, fromAgent, kind, createdAt: Date.now() };
        transfers.set(id, item);
        const deliveredToPeer = kind === 'clipboard-image' ? true : fromAgent
          ? send(session.operatorWs, { type: 'file-ready', sessionId, transferId: id, name, size })
          : send(agents.get(session.clientId)?.ws, { type: 'file-download', sessionId, transferId: id, name, size });
        if (!deliveredToPeer) {
          transfers.delete(id);
          try { unlinkSync(path); } catch { /* arquivo temporario */ }
          res.writeHead(409).end('peer offline');
          return;
        }
        res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id, size }));
      } catch (e) {
        try { unlinkSync(path); } catch { /* upload recusado/parcial */ }
        res.writeHead(413).end('upload failed');
      }
      return;
    }
    if (url.pathname.startsWith('/transfers/') && req.method === 'GET') {
      const id = basename(url.pathname);
      const item = transfers.get(id);
      const operator = account(req.headers);
      const agentId = String(req.headers['x-client-id'] || '');
      const agentOk = (await authenticateAgentEdge(req.headers))
        && !!store.authenticate(agentId, String(req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
      const ok = item && ((operator && operator.email === item.operatorEmail) || (agentOk && agentId === item.clientId));
      if (!ok || !existsSync(item.path)) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': item.size,
        'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(item.name)}`,
        'cache-control': 'no-store',
      });
      const stream = createReadStream(item.path);
      if (item.kind === 'clipboard-image') stream.once('close', () => {
        transfers.delete(id);
        try { unlinkSync(item.path); } catch { /* ja removido */ }
      });
      stream.pipe(res);
      return;
    }
    // Pacote de atualizacao: so para agentes autenticados (Service Token + credencial propria) e aprovados.
    if (url.pathname.startsWith('/updates/')) {
      const bearer = (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
      const id = req.headers['x-client-id'];
      const ok = (await authenticateAgentEdge(req.headers)) && store.authenticate(id, bearer) && store.get(id)?.approved;
      if (!ok) {
        res.writeHead(401).end('unauthorized');
        return;
      }
      const file = updates.meta() && updates.filePath(basename(url.pathname));
      if (!file || !existsSync(file)) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': statSync(file).size, 'cache-control': 'no-store' });
      createReadStream(file).pipe(res);
      return;
    }
    if (url.pathname === '/login.html' || url.pathname === '/login.js' || url.pathname === '/login.css') {
      try { const body = await readFile(normalize(join(PUBLIC_DIR, url.pathname))); res.writeHead(200, { 'content-type': MIME[extname(url.pathname)] || 'application/octet-stream', 'cache-control': 'no-store' }).end(body); } catch { res.writeHead(404).end(); }
      return;
    }
    const op = account(req.headers);
    if (!op) {
      res.writeHead(302, { location: '/login.html' }).end();
      return;
    }
    const rel = url.pathname === '/' ? '/index.html' : url.pathname;
    const file = normalize(join(PUBLIC_DIR, rel));
    if (file !== PUBLIC_DIR && !file.startsWith(PUBLIC_DIR + sep)) {
      res.writeHead(403).end();
      return;
    }
    try {
      const body = await readFile(file);
      res.writeHead(200, {
        'content-type': MIME[extname(file)] || 'application/octet-stream',
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
        'content-security-policy':
          "default-src 'self'; connect-src 'self' wss: ws:; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'",
        'permissions-policy': 'microphone=(self), camera=(self), display-capture=(self), fullscreen=(self), clipboard-read=(self), clipboard-write=(self)',
      });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: 8 << 20 });

  // Modo compativel: frames binarios do agente -> operador. Cabecalho: 1 byte (0x01) + 16 bytes do sessionId.
  // Sem descartar nada (os blocos sao incrementais): se o operador estiver lento, pausa a leitura do agente.
  const HIGH_WATER = 8 << 20;
  const LOW_WATER = 2 << 20;
  const uuidFromBytes = (buf, off) => {
    const h = buf.subarray(off, off + 16).toString('hex');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  };
  function relayFrame(agentId, agentWs, raw) {
    if (raw.length < 18 || raw[0] !== 0x01) return;
    const s = sessions.get(uuidFromBytes(raw, 1));
    if (!s || s.clientId !== agentId || s.operatorWs.readyState !== 1) return;
    s.inflight = (s.inflight || 0) + raw.length;
    if (s.inflight > HIGH_WATER && !s.paused) {
      s.paused = true;
      agentWs._socket?.pause();
    }
    s.operatorWs.send(raw, { binary: true }, () => {
      s.inflight -= raw.length;
      if (s.paused && s.inflight < LOW_WATER) {
        s.paused = false;
        agentWs._socket?.resume();
      }
    });
  }

  server.on('upgrade', async (req, socket, head) => {
    const url = new URL(req.url, 'http://x');
    const reject = () => {
      // Resposta HTTP completa (com Content-Length) para o proxy repassar o 401 em vez de um 502.
      socket.end('HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    };
    try {
      if (url.pathname === '/agent') {
        if (!(await authenticateAgentEdge(req.headers))) return reject();
        const id = req.headers['x-client-id'];
        const token = (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
        let client = store.authenticate(id, token);
        if (!client) {
          // ID conhecido com segredo errado = impostor; ID novo = registro automatico.
          if (store.has(id) || store.pendingCount() >= config.maxPending) return reject();
          let name = '';
          try {
            name = decodeURIComponent(req.headers['x-client-name'] || '');
          } catch {
            /* nome invalido: usa o padrao */
          }
          const enrollment = String(req.headers['x-agent-enrollment'] || '');
          const enrolledOwner = accounts.resolveEnrollment(enrollment);
          // Agentes antigos nao conhecem matricula. Enquanto houver uma unica conta, a associacao
          // e inequivoca e preserva a compatibilidade com instalacoes e atualizacoes anteriores.
          const ownerId = enrolledOwner || accounts.onlyUserId();
          client = store.enroll(id, token, name, config.autoApprove, ownerId);
          if (!client) return reject();
          if (enrolledOwner) accounts.consumeEnrollment(enrollment);
        }
        wss.handleUpgrade(req, socket, head, (ws) => onAgent(ws, client));
      } else if (url.pathname === '/operator') {
        // A entrada LAN nao aceita cookies nem credenciais locais: exige ticket de uso unico criado
        // por uma conexao Cloudflare Access ja validada. Isso evita transformar a LAN em um login
        // paralelo ou abrir o painel a qualquer dispositivo da rede.
        const ticket = url.searchParams.get('lanTicket') || '';
        const op = localIngress(req.headers) && ticket ? consumeLanTicket(ticket) : account(req.headers);
        if (!op) return reject();
        wss.handleUpgrade(req, socket, head, (ws) => onOperator(ws, op));
      } else {
        socket.destroy();
      }
    } catch {
      socket.destroy();
    }
  });

  // ---------- Agent (client) ----------
  function onAgent(ws, client) {
    const id = client.id;
    // Uma conexao por client: a mais nova vence (ex: reconexao apos queda de rede).
    agents.get(id)?.ws.terminate();
    const entry = { ws, alive: true };
    agents.set(id, entry);
    ws.role = entry;
    store.update(id, { lastSeen: Date.now() });
    if (client.approved) send(ws, { type: 'settings', settings: client.settings });
    else send(ws, { type: 'pending' });
    broadcastClients();

    ws.on('pong', () => {
      entry.alive = true;
    });
    ws.on('message', (raw, isBinary) => {
      if (isBinary) {
        if (store.get(id)?.approved) relayFrame(id, ws, raw);
        return;
      }
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }
      // Maquina nao aprovada so se apresenta; nada dela e repassado aos operadores.
      if (!store.get(id)?.approved && msg.type !== 'hello') return;
      switch (msg.type) {
        case 'hello': {
          const prevJar = store.get(id)?.info?.jarSha256 || '';
          const curJar = isHex64(msg.jarSha256) ? msg.jarSha256 : '';
          store.update(id, {
            info: {
              jarSha256: curJar,
              hostname: String(msg.hostname || '').slice(0, 120),
              os: String(msg.os || '').slice(0, 60),
              version: String(msg.version || '').slice(0, 60),
              hubRoute: msg.hubRoute === 'lan' || msg.hubRoute === 'cloud' ? msg.hubRoute : undefined,
              screen: msg.screen && { w: msg.screen.w | 0, h: msg.screen.h | 0 },
            },
          });
          entry.jarSha256 = curJar;
          if (curJar && prevJar !== curJar) {
            // versao do agente mudou (atualizacao automatica ou reinstalacao manual)
            store.addHistory(id, { type: 'version', from: prevJar.slice(0, 8), to: curJar.slice(0, 8) });
          }
          broadcastClients();
          offerUpdate(id, entry);
          break;
        }
        case 'check-update':
          // Pedido explícito da bandeja: ignora apenas o intervalo de reoferta; a assinatura,
          // a aprovação da máquina e todas as proteções do atualizador continuam obrigatórias.
          offerUpdate(id, entry, true);
          break;
        case 'stats': {
          // Uso de CPU/memoria da maquina, so em memoria (nao vai pro store/disco - chega a cada
          // poucos segundos, nao tem por que persistir o historico, so o valor mais recente importa).
          const d = msg.data || {};
          const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
          entry.stats = {
            cpuPct: num(d.cpuPct),
            memUsedMb: num(d.memUsedMb),
            memTotalMb: num(d.memTotalMb),
            uptimeSec: num(d.uptimeSec),
            hubRoute: d.hubRoute === 'lan' || d.hubRoute === 'cloud' ? d.hubRoute : undefined,
          };
          broadcastClients();
          break;
        }
        case 'rtc': {
          const s = sessions.get(msg.sessionId);
          if (s && s.clientId === id) send(s.operatorWs, { type: 'rtc', sessionId: msg.sessionId, data: msg.data });
          break;
        }
        case 'compat-pong': {
          // Eco da sonda de latencia: so volta ao operador dono da sessao correspondente.
          const s = sessions.get(msg.sessionId);
          const n = msg.data?.n;
          if (s && s.clientId === id && Number.isInteger(n) && n >= 0 && n <= 0x7fffffff) {
            send(s.operatorWs, { type: 'compat-pong', sessionId: msg.sessionId, data: { n } });
          }
          break;
        }
        case 'status': // eventos do client (ex: atividade no Teams) repassados aos operadores
          if (typeof msg.event === 'string' && msg.event.startsWith('update-')) {
            const d = msg.data || {};
            const map = {
              'update-applying': { type: 'applying', from: d.from, to: d.to },
              'update-failed': { type: 'failed', to: d.to, text: d.error },
              'update-rollback': { type: 'rollback', from: d.badVersion, to: d.currentVersion },
            };
            if (map[msg.event]) {
              store.addHistory(id, map[msg.event]);
              broadcastClients();
            }
          } else if (msg.event === 'agent-diagnostic') {
            const d = msg.data || {};
            const code = typeof d.code === 'string' && /^[a-z0-9-]{1,32}$/.test(d.code) ? d.code : 'agent';
            const detail = typeof d.detail === 'string' ? d.detail.slice(0, 240) : '';
            store.addHistory(id, { type: 'diagnostic', text: `[${code}] ${detail}`.trim() });
            broadcastClients();
          } else if (msg.event === 'agent-log') {
            const d = msg.data || {};
            const logId = typeof d.id === 'string' && /^[a-f0-9-]{36}$/i.test(d.id) ? d.id : '';
            const code = typeof d.code === 'string' && /^[a-z0-9-]{1,32}$/.test(d.code) ? d.code : 'agent';
            const level = ['info', 'warn', 'error'].includes(d.level) ? d.level : 'info';
            const detail = typeof d.detail === 'string' ? d.detail.slice(0, 240) : '';
            const throttleKey = `${id}:${code}:${level}:${detail}`;
            const now = Date.now();
            const duplicate = now - (agentLogThrottle.get(throttleKey) || 0) < 5 * 60_000;
            // Informativos e repeticoes sao confirmados para limpar a fila do agente, mas descartados.
            if (level === 'info' || duplicate) {
              if (logId) send(ws, { type: 'agent-log-ack', id: logId });
            } else {
              agentLogThrottle.set(throttleKey, now);
              const persisted = store.appendAgentLog(id, { id: logId, code, level, detail });
              if (persisted && logId) send(ws, { type: 'agent-log-ack', id: logId });
              store.addHistory(id, { type: 'diagnostic', text: `[${code}] ${detail}`.trim() });
              broadcastClients();
            }
          } else if (msg.event === 'file-delivery') {
            const d = msg.data || {};
            const sessionId = typeof d.sessionId === 'string' ? d.sessionId : '';
            const transferId = typeof d.transferId === 'string' ? d.transferId : '';
            const s = sessions.get(sessionId);
            const item = transfers.get(transferId);
            if (s && s.clientId === id && item?.sessionId === sessionId && !item.fromAgent) {
              const delivered = d.delivered === true;
              store.addHistory(id, { type: 'transfer', text: delivered ? 'Transferência para a máquina concluída.' : 'Transferência para a máquina falhou.' });
              send(s.operatorWs, { type: 'file-status', sessionId, transferId, delivered });
              broadcastClients();
            }
          } else if (msg.event === 'input-result') {
            const d = msg.data || {};
            const s = sessions.get(d.sessionId);
            const trace = typeof d.trace === 'string' ? d.trace.slice(0, 100) : '';
            const result = d.result === 'applied' ? 'applied' : 'rejected';
            // Confirma somente o fluxo funcional que espera resposta (ex.: imagem de clipboard),
            // sem persistir auditoria por comando.
            if (trace) {
              send(ws, { type: 'input-result-ack', trace });
              if (s?.clientId === id) send(s.operatorWs, { type: 'input-result', sessionId: d.sessionId, data: { trace, result } });
            }
          } else if (msg.event === 'input-activity') {
            // Compatibilidade com agentes antigos: drena a fila sem persistir atividade de entrada.
            const d = msg.data || {};
            const activityId = typeof d.id === 'string' && /^[a-f0-9-]{36}$/i.test(d.id) ? d.id : '';
            if (activityId) send(ws, { type: 'input-activity-ack', id: activityId });
          }
          for (const op of operators) send(op, { type: 'client-event', clientId: id, event: msg.event, data: msg.data });
          break;
      }
    });
    ws.on('close', () => {
      if (agents.get(id) === entry) {
        agents.delete(id);
        store.update(id, { lastSeen: Date.now() });
        closeSessionsOf((s) => s.clientId === id);
        broadcastClients();
      }
    });
    ws.on('error', () => {});
  }

  // ---------- Operador (navegador) ----------
  function onOperator(ws, op) {
    const entry = { ws, alive: true, user: op };
    ws.role = entry;
    operators.add(ws);
    send(ws, { type: 'hello', email: op.email, admin: true });
    send(ws, { type: 'clients', clients: store.list().filter((c) => accounts.owns(op.id, c)).map(publicClient), latestAgent: latestAgent() });
    ws.on('pong', () => {
      entry.alive = true;
    });

    ws.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }
      const needAdmin = () => true;
      switch (msg.type) {
        case 'approve-client': {
          if (!needAdmin()) return;
          if (!accounts.owns(op.id, store.get(msg.clientId))) { send(ws, { type: 'error', error: 'forbidden' }); return; }
          const c = store.approve(msg.clientId);
          if (c) {
            const agent = agents.get(c.id);
            if (agent) {
              send(agent.ws, { type: 'settings', settings: c.settings });
              offerUpdate(c.id, agent);
            }
            broadcastClients();
          }
          break;
        }
        case 'revoke-client': {
          if (!needAdmin()) return;
          if (!accounts.owns(op.id, store.get(msg.clientId))) { send(ws, { type: 'error', error: 'forbidden' }); return; }
          agents.get(msg.clientId)?.ws.close(4001, 'revoked');
          closeSessionsOf((s) => s.clientId === msg.clientId);
          store.revoke(msg.clientId);
          broadcastClients();
          break;
        }
        case 'update-client': {
          if (!needAdmin()) return;
          if (!accounts.owns(op.id, store.get(msg.clientId))) { send(ws, { type: 'error', error: 'forbidden' }); return; }
          const c = store.update(msg.clientId, { name: msg.name, settings: msg.settings });
          if (c) {
            const agent = agents.get(c.id);
            if (agent) send(agent.ws, { type: 'settings', settings: c.settings });
            broadcastClients();
          }
          break;
        }
        case 'session-open': {
          if (!accounts.owns(op.id, store.get(msg.clientId))) {
            send(ws, { type: 'error', error: 'forbidden' });
            return;
          }
          const agent = agents.get(msg.clientId);
          if (!agent) {
            send(ws, { type: 'error', error: 'client-offline' });
            return;
          }
          if (!store.get(msg.clientId)?.approved) {
            send(ws, { type: 'error', error: 'not-approved' });
            return;
          }
          const sessionId = randomUUID();
          sessions.set(sessionId, { clientId: msg.clientId, operatorWs: ws, operatorEmail: op.email });
          const ice = iceServers(`${op.email}:${sessionId}`);
          send(agent.ws, { type: 'session-open', sessionId, iceServers: ice });
          send(ws, { type: 'session-ready', sessionId, clientId: msg.clientId, iceServers: ice });
          break;
        }
        case 'rtc': {
          const s = sessions.get(msg.sessionId);
          if (s && s.operatorWs === ws) {
            const agent = agents.get(s.clientId);
            if (agent) send(agent.ws, { type: 'rtc', sessionId: msg.sessionId, data: msg.data });
          }
          break;
        }
        case 'cmd': { // controle no modo compativel (no modo WebRTC isso vai pelo canal de dados)
          const s = sessions.get(msg.sessionId);
          if (s && s.operatorWs === ws) {
            const agent = agents.get(s.clientId);
            if (agent) {
              send(agent.ws, { type: 'cmd', sessionId: msg.sessionId, data: msg.data });
            }
          }
          break;
        }
        case 'telemetry': {
          const s = sessions.get(msg.sessionId);
          const event = String(msg.data?.event || '');
          if (s && s.operatorWs === ws && event === 'input-command') {
            // Visualizadores antigos podem continuar enviando telemetria ate atualizarem o cache.
            // Confirma para que eles limpem a fila, mas nao grava nem processa o evento.
            if (typeof msg.data?.trace === 'string') send(ws, { type: 'telemetry-ack', ids: [msg.data.trace] });
            break;
          }
          break;
        }
        case 'telemetry-recovery': {
          const ids = Array.isArray(msg.events) ? msg.events.slice(0, 500)
            .map((entry) => typeof entry?.trace === 'string' ? entry.trace : '').filter(Boolean) : [];
          if (ids.length) send(ws, { type: 'telemetry-ack', ids });
          break;
        }
        case 'session-close': {
          const s = sessions.get(msg.sessionId);
          if (s && s.operatorWs === ws) closeSessionsOf((x) => x === s);
          break;
        }
        case 'lan-ticket': {
          const ticket = issueLanTicket(op);
          if (ticket) send(ws, { type: 'lan-ticket', url: config.lanOperatorUrl, ticket, expiresInMs: LAN_TICKET_TTL_MS });
          break;
        }
      }
    });
    ws.on('close', () => {
      operators.delete(ws);
      closeSessionsOf((s) => s.operatorWs === ws);
    });
    ws.on('error', () => {});
  }

  // Keep-alive: mantem o WebSocket vivo atras do Cloudflare e detecta pares mortos.
  const pinger = setInterval(() => {
    for (const ws of wss.clients) {
      const r = ws.role;
      if (!r) continue;
      if (!r.alive) {
        ws.terminate();
        continue;
      }
      r.alive = false;
      ws.ping();
    }
  }, config.pingIntervalMs);
  pinger.unref();
  const updateTimer = setInterval(() => {
    for (const [id, entry] of agents) offerUpdate(id, entry);
    // Imagens de clipboard do modo compatibilidade sao descartaveis. Se a sessao cair antes de o
    // agente baixa-las, nao deixe o blob temporario acumular no hub.
    const clipboardExpiry = Date.now() - 5 * 60_000;
    for (const [id, item] of transfers) {
      if (item.kind === 'clipboard-image' && item.createdAt < clipboardExpiry) {
        transfers.delete(id);
        try { unlinkSync(item.path); } catch { /* ja removido */ }
      }
    }
    for (const [ticket, entry] of lanTickets) if (entry.expiresAt < Date.now()) lanTickets.delete(ticket);
    for (const [key, at] of agentLogThrottle) if (Date.now() - at > 30 * 60_000) agentLogThrottle.delete(key);
  }, 60_000);
  updateTimer.unref();

  return {
    server,
    store,
    close() {
      clearInterval(pinger);
      clearInterval(updateTimer);
      for (const ws of wss.clients) ws.terminate();
      server.close();
      server.closeAllConnections?.(); // conexoes HTTP persistentes (keep-alive) nao seguram o encerramento
    },
  };
}

// Execucao direta (node src/server.js)
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = validateConfig();
  if (problems.length) {
    console.error('Configuracao invalida:', problems.join('; '));
    process.exit(1);
  }
  if (config.insecureDev) console.warn('ATENCAO: INSECURE_DEV=1 - autenticacao de operadores desligada.');
  const hub = createHub();
  hub.server.listen(config.port, () => console.log(`remote-hub ouvindo na porta ${config.port}`));
}
