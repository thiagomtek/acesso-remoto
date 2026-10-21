import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import WebSocket from 'ws';

process.env.INSECURE_DEV = '1';
const { createHub } = await import('../src/server.js');
const { Store } = await import('../src/store.js');
const { Accounts } = await import('../src/accounts.js');
const { DEFAULT_NEXTCLOUD_URL, NextcloudSync, parsePropfindXml } = await import('../src/nextcloud.js');

test('nextcloud: credenciais nao sao enviadas por HTTP fora do loopback', async () => {
  const sync = new NextcloudSync();
  await assert.rejects(sync.testConfig({ url: 'http://192.168.1.10/', username: 'u', appPassword: 'p' }), /HTTPS/);
});

/**
 * Servidor WebDAV em memoria que simula o Nextcloud (/remote.php/dav/files/<user>/...).
 * Suporta Basic Auth, PROPFIND (Depth 0 e 1), MKCOL, PUT, GET e DELETE.
 */
function createMockNextcloud({ expectedUser = 'thiago', expectedPass = 'app-token-1234' } = {}) {
  const files = new Map(); // relPath -> { content: Buffer, mtime: number, etag: string }
  const dirs = new Set(['']); // '' = raiz do usuario
  let etagCounter = 1;

  const ensureParentDirs = (relPath) => {
    const parts = relPath.split('/').filter(Boolean);
    parts.pop();
    let cur = '';
    for (const p of parts) {
      cur = cur ? `${cur}/${p}` : p;
      dirs.add(cur);
    }
  };

  const putVirtualFile = (relPath, content, mtime = Date.now()) => {
    ensureParentDirs(relPath);
    const buf = Buffer.isBuffer(content) ? content : Buffer.from(String(content), 'utf8');
    files.set(relPath, {
      content: buf,
      mtime: Math.floor(mtime / 1000) * 1000,
      etag: `etag-${etagCounter++}`,
    });
  };

  const server = http.createServer(async (req, res) => {
    const auth = req.headers.authorization || '';
    const expectedAuth = 'Basic ' + Buffer.from(`${expectedUser}:${expectedPass}`).toString('base64');
    if (auth !== expectedAuth) {
      res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="Nextcloud"' }).end('Unauthorized');
      return;
    }

    const url = new URL(req.url, 'http://127.0.0.1');
    const prefix = `/remote.php/dav/files/${encodeURIComponent(expectedUser)}`;
    if (!url.pathname.startsWith(prefix)) {
      res.writeHead(404).end('Not Found');
      return;
    }

    const rel = url.pathname
      .slice(prefix.length)
      .replace(/^\/+|\/+$/g, '')
      .split('/')
      .filter(Boolean)
      .map(decodeURIComponent)
      .join('/');

    if (req.method === 'PROPFIND') {
      const depth = String(req.headers.depth ?? '1');
      if (!dirs.has(rel) && !files.has(rel)) {
        res.writeHead(404).end('Not Found');
        return;
      }
      const items = [];
      const encodeHref = (p) =>
        p
          ? `${prefix}/${p.split('/').map(encodeURIComponent).join('/')}`
          : `${prefix}/`;

      if (dirs.has(rel)) {
        items.push({ href: encodeHref(rel) + (rel ? '/' : ''), isDir: true, size: 0, mtime: Date.now(), etag: 'dir' });
        if (depth !== '0') {
          const childPrefix = rel ? `${rel}/` : '';
          for (const d of dirs) {
            if (!d || !d.startsWith(childPrefix)) continue;
            const rest = d.slice(childPrefix.length);
            if (rest && !rest.includes('/')) {
              items.push({ href: `${encodeHref(d)}/`, isDir: true, size: 0, mtime: Date.now(), etag: 'dir' });
            }
          }
          for (const [fPath, meta] of files.entries()) {
            if (!fPath.startsWith(childPrefix)) continue;
            const rest = fPath.slice(childPrefix.length);
            if (rest && !rest.includes('/')) {
              items.push({
                href: encodeHref(fPath),
                isDir: false,
                size: meta.content.length,
                mtime: meta.mtime,
                etag: meta.etag,
              });
            }
          }
        }
      } else {
        const meta = files.get(rel);
        items.push({
          href: encodeHref(rel),
          isDir: false,
          size: meta.content.length,
          mtime: meta.mtime,
          etag: meta.etag,
        });
      }

      const xml = `<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
${items
  .map(
    (it) => `  <d:response>
    <d:href>${it.href}</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype>${it.isDir ? '<d:collection/>' : ''}</d:resourcetype>
        ${it.isDir ? '' : `<d:getcontentlength>${it.size}</d:getcontentlength>`}
        <d:getlastmodified>${new Date(it.mtime).toUTCString()}</d:getlastmodified>
        <d:getetag>"${it.etag}"</d:getetag>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>`,
  )
  .join('\n')}
</d:multistatus>`;
      res.writeHead(207, { 'Content-Type': 'application/xml; charset=utf-8' }).end(xml);
      return;
    }

    if (req.method === 'MKCOL') {
      if (dirs.has(rel)) {
        res.writeHead(405).end();
        return;
      }
      dirs.add(rel);
      res.writeHead(201).end();
      return;
    }

    if (req.method === 'GET') {
      const meta = files.get(rel);
      if (!meta) {
        res.writeHead(404).end();
        return;
      }
      res.writeHead(200, { 'Content-Length': meta.content.length }).end(meta.content);
      return;
    }

    if (req.method === 'PUT') {
      const parent = rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '';
      if (!dirs.has(parent)) {
        res.writeHead(409).end('Conflict: parent collection does not exist');
        return;
      }
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const buf = Buffer.concat(chunks);
      const ocMtime = Number(req.headers['x-oc-mtime']);
      const mtime = Number.isFinite(ocMtime) && ocMtime > 0 ? ocMtime * 1000 : Date.now();
      files.set(rel, { content: buf, mtime, etag: `etag-${etagCounter++}` });
      res.writeHead(201).end();
      return;
    }

    if (req.method === 'DELETE') {
      if (files.has(rel)) {
        files.delete(rel);
        res.writeHead(204).end();
        return;
      }
      if (dirs.has(rel)) {
        dirs.delete(rel);
        for (const k of [...files.keys()]) {
          if (k.startsWith(rel + '/')) files.delete(k);
        }
        res.writeHead(204).end();
        return;
      }
      res.writeHead(404).end();
      return;
    }

    res.writeHead(405).end();
  });

  return {
    server,
    files,
    dirs,
    putVirtualFile,
    async listen() {
      await new Promise((r) => server.listen(0, '127.0.0.1', r));
      return `http://127.0.0.1:${server.address().port}/`;
    },
    close() {
      server.close();
      server.closeAllConnections?.();
    },
  };
}

test('nextcloud: comunicacao direta do agente com o Nextcloud, validacao no Hub e sinalizacao via WebSocket', async () => {
  const nc = createMockNextcloud({ expectedUser: 'thiago', expectedPass: 'chave-api-secreta' });
  const ncUrl = await nc.listen();

  const dir = mkdtempSync(join(tmpdir(), 'hub-nc-'));
  const accounts = new Accounts(dir);
  const user = accounts.register('thiago@teste.local', 'senha-forte-123');
  const cookie = `hub_session=${accounts.createSession(user.id)}`;
  const hub = createHub({ store: new Store(dir), accounts });
  await new Promise((r) => hub.server.listen(0, '127.0.0.1', r));
  const baseHttp = `http://127.0.0.1:${hub.server.address().port}`;
  const baseWs = baseHttp.replace(/^http/, 'ws');

  // Registra o agente da maquina
  const clientId = randomUUID();
  const agentToken = randomBytes(32).toString('base64url');
  const agentHeaders = {
    'x-client-id': clientId,
    authorization: `Bearer ${agentToken}`,
    'x-client-name': encodeURIComponent('Notebook-Casa'),
  };
  const ws = new WebSocket(`${baseWs}/agent`, { headers: agentHeaders });
  const agentMessages = [];
  ws.on('message', (raw) => {
    try { agentMessages.push(JSON.parse(raw)); } catch {}
  });
  await new Promise((r) => ws.once('open', r));
  hub.store.approve(clientId);

  // 1. Consulta estado inicial do Nextcloud pela API web (deve trazer URL padrao do homelab)
  const initialRes = await fetch(`${baseHttp}/api/nextcloud?clientId=${clientId}`, { headers: { cookie } });
  assert.equal(initialRes.status, 200);
  const initialData = await initialRes.json();
  assert.equal(initialData.nextcloud.url, DEFAULT_NEXTCLOUD_URL);
  assert.equal(initialData.nextcloud.enabled, false);

  // 2. Credencial errada e rejeitada na hora sem ativar
  const badAuth = await fetch(`${baseHttp}/api/nextcloud?clientId=${clientId}`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({
      enabled: true,
      url: ncUrl,
      username: 'thiago',
      appPassword: 'senha-errada',
      remoteFolder: '/',
    }),
  });
  assert.equal(badAuth.status, 400);

  // 3. Configura credenciais corretas na interface web -> valida com WebDAV e empurra para o agente!
  const saveRes = await fetch(`${baseHttp}/api/nextcloud?clientId=${clientId}`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({
      enabled: true,
      url: ncUrl,
      username: 'thiago',
      appPassword: 'chave-api-secreta',
      remoteFolder: '/Recebimentos',
    }),
  });
  assert.equal(saveRes.status, 200);
  const saved = await saveRes.json();
  assert.equal(saved.ok, true);
  assert.equal(saved.nextcloud.enabled, true);
  assert.equal(saved.nextcloud.hasPassword, true);
  assert.equal('appPassword' in saved.nextcloud, false, 'a chave de API nunca volta na resposta HTTP publica');

  // Verifica que o agente conectado recebeu a mensagem `nextcloud-config` com a configuracao completa
  await new Promise((r) => setTimeout(r, 100));
  const configMsg = agentMessages.find((m) => m.type === 'nextcloud-config');
  assert.ok(configMsg, 'agente deve receber a configuracao do Nextcloud para sincronizar diretamente');
  assert.equal(configMsg.nextcloud.username, 'thiago');
  assert.equal(configMsg.nextcloud.appPassword, 'chave-api-secreta');

  // 4. Disparo de sincronizacao manual via API (ex: botao da bolinha) -> envia comando ao agente
  const syncRes = await fetch(`${baseHttp}/api/nextcloud/sync?clientId=${clientId}`, { method: 'POST', headers: { cookie } });
  assert.equal(syncRes.status, 202);
  const syncRequest = await syncRes.json();
  assert.ok(syncRequest.requestId);
  await new Promise((r) => setTimeout(r, 100));
  const syncMsg = agentMessages.find((m) => m.type === 'sync-nextcloud');
  assert.ok(syncMsg, 'agente recebe comando sync-nextcloud para executar reconciliacao imediata');
  assert.equal(syncMsg.requestId, syncRequest.requestId);

  ws.send(JSON.stringify({ type: 'status', event: 'nextcloud-status',
    data: { requestId: syncRequest.requestId, syncing: true } }));
  ws.send(JSON.stringify({ type: 'status', event: 'nextcloud-status',
    data: { requestId: syncRequest.requestId, syncing: true, processedFiles: 2, totalFiles: 5 } }));
  ws.send(JSON.stringify({ type: 'status', event: 'nextcloud-activity',
    data: { requestId: syncRequest.requestId, action: 'download', path: 'UNICRED/AGENTS.md' } }));
  await new Promise((r) => setTimeout(r, 30));
  const inProgress = await (await fetch(`${baseHttp}/api/nextcloud?clientId=${clientId}`, { headers: { cookie } })).json();
  assert.equal(inProgress.nextcloud.lastSyncAt, null, 'inicio da tarefa nao significa sincronizacao concluida');
  assert.equal(inProgress.nextcloud.completedRequestId, null);
  assert.equal(inProgress.nextcloud.processedFiles, 2);
  assert.equal(inProgress.nextcloud.totalFiles, 5);
  assert.ok(hub.store.get(clientId).history.some((entry) => entry.type === 'nextcloud'
    && entry.text.includes('iniciada')));
  assert.equal(hub.store.get(clientId).history.filter((entry) => entry.type === 'nextcloud'
    && entry.text.includes('iniciada')).length, 1, 'progresso nao duplica inicio no historico');
  const syncLog = await (await fetch(`${baseHttp}/api/nextcloud/log?clientId=${clientId}`, { headers: { cookie } })).json();
  assert.deepEqual(syncLog.events.map((entry) => entry.action), ['start', 'download']);
  assert.equal(syncLog.events[1].path, 'UNICRED/AGENTS.md');

  // 5. Agente reporta status de sincronizacao -> Hub atualiza e expoe para a UI
  ws.send(JSON.stringify({
    type: 'status',
    event: 'nextcloud-status',
    data: { requestId: syncRequest.requestId, syncing: false, lastSyncAt: 1700000050000, lastChanges: 3 },
  }));
  await new Promise((r) => setTimeout(r, 100));
  const statusAfterReport = await (await fetch(`${baseHttp}/api/nextcloud?clientId=${clientId}`, { headers: { cookie } })).json();
  assert.equal(statusAfterReport.nextcloud.lastSyncAt, 1700000050000);
  assert.equal(statusAfterReport.nextcloud.lastChanges, 3);
  assert.equal(statusAfterReport.nextcloud.completedRequestId, syncRequest.requestId);
  assert.ok(hub.store.get(clientId).history.some((entry) => entry.type === 'nextcloud'
    && entry.text.includes('Sincronização Nextcloud concluída')));

  const closed = new Promise((resolve) => ws.once('close', resolve));
  ws.close();
  await closed;
  const offlineSync = await fetch(`${baseHttp}/api/nextcloud/sync?clientId=${clientId}`, { method: 'POST', headers: { cookie } });
  assert.equal(offlineSync.status, 503, 'agente offline nao pode receber sucesso falso');
  hub.close();
  nc.close();
});

test('nextcloud: parsePropfindXml decodifica caminhos com espacos, acentos e ignora o proprio diretorio', () => {
  const xml = `<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/thiago/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/thiago/Relat%C3%B3rio%202026.pdf</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getcontentlength>4096</d:getcontentlength>
        <d:getlastmodified>Wed, 20 Oct 2026 20:00:00 GMT</d:getlastmodified>
        <d:getetag>"abc123"</d:getetag>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>`;
  const parsed = parsePropfindXml(xml, '/remote.php/dav/files/thiago');
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].path, 'Relatório 2026.pdf');
  assert.equal(parsed[0].size, 4096);
  assert.equal(parsed[0].etag, 'abc123');
});

test('nextcloud UI: modal de configuracao abre pelas configuracoes e pelo visualizador e salva credenciais', async () => {
  const { JSDOM } = await import('jsdom');
  const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true });
  const { window } = dom;
  globalThis.window = window;
  globalThis.document = window.document;
  globalThis.HTMLElement = window.HTMLElement;
  globalThis.MutationObserver = window.MutationObserver;
  globalThis.Node = window.Node;

  const calls = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url: String(url), opts });
    if (opts.method === 'POST' && String(url).startsWith('/api/nextcloud?')) {
      const body = JSON.parse(opts.body);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          changes: 3,
          nextcloud: {
            enabled: body.enabled,
            url: body.url,
            username: body.username,
            remoteFolder: body.remoteFolder,
            hasPassword: true,
            lastSyncAt: Date.now(),
          },
        }),
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({
        nextcloud: {
          enabled: false,
          url: DEFAULT_NEXTCLOUD_URL,
          username: 'thiago',
          remoteFolder: '/',
          hasPassword: false,
        },
      }),
    };
  };

  try {
    const { buildSettingsPanel } = await import('../public/settings.js');
    const panel = buildSettingsPanel(
      { id: 'c-nc', name: 'PC Homelab', settings: { allowRemoteControl: true } },
      { admin: true, send: () => {} },
    );
    window.document.body.append(panel);
    const openBtn = panel.querySelector('[data-nextcloud-open]');
    assert.ok(openBtn, 'botao Configurar Nextcloud presente no painel de configuracoes');
    openBtn.click();
    await new Promise((r) => setTimeout(r, 10));

    const modal = window.document.getElementById('nextcloud-modal');
    assert.ok(modal && !modal.hidden, 'modal do Nextcloud abriu');
    assert.equal(modal.querySelector('[data-nc-url]').value, DEFAULT_NEXTCLOUD_URL);
    assert.equal(modal.querySelector('[data-nc-user]').value, 'thiago');

    modal.querySelector('[data-nc-enabled]').checked = true;
    modal.querySelector('[data-nc-pass]').value = 'token-gerado-no-nextcloud';
    modal.querySelector('[data-nc-folder]').value = '/Recebimentos';
    modal.querySelector('[data-nc-form]').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await new Promise((r) => setTimeout(r, 10));

    const postCall = calls.find((c) => c.opts.method === 'POST');
    assert.ok(postCall);
    assert.deepEqual(JSON.parse(postCall.opts.body), {
      enabled: true,
      url: DEFAULT_NEXTCLOUD_URL,
      username: 'thiago',
      appPassword: 'token-gerado-no-nextcloud',
      remoteFolder: '/Recebimentos',
    });
    assert.match(modal.querySelector('[data-nc-status]').textContent, /Configuração salva/);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

