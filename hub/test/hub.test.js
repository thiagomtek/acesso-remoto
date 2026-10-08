import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import WebSocket from 'ws';

process.env.INSECURE_DEV = '1';
const { createHub } = await import('../src/server.js');
const { Store } = await import('../src/store.js');
const { Accounts } = await import('../src/accounts.js');

const next = (ws, pred = () => true) =>
  new Promise((res) => {
    const h = (raw) => {
      const m = JSON.parse(raw);
      if (pred(m)) {
        ws.off('message', h);
        res(m);
      }
    };
    ws.on('message', h);
  });

// Cookie da conta de teste criada em setup(); o /operator exige sessao de conta.
let operatorCookie = '';
const connect = (url, headers) =>
  new Promise((res, rej) => {
    const h = url.endsWith('/operator') && !headers ? { cookie: operatorCookie } : headers;
    const ws = new WebSocket(url, { headers: h });
    ws.once('open', () => res(ws));
    ws.once('error', rej);
  });

const agentHeaders = (id, token, name = 'PC Teste') => ({
  'x-client-id': id,
  authorization: `Bearer ${token}`,
  'x-client-name': encodeURIComponent(name),
});

async function setup(extra = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'hub-'));
  const accounts = new Accounts(dir);
  const user = accounts.register('operador@teste.local', 'senha-de-teste-1');
  operatorCookie = `hub_session=${accounts.createSession(user.id)}`;
  const hub = createHub({ store: new Store(dir), accounts, ...extra });
  await new Promise((r) => hub.server.listen(0, r));
  return { hub, base: `ws://127.0.0.1:${hub.server.address().port}` };
}

test('transferencia de arquivos: operador envia ao agente e agente devolve download pela mesma sessao', async () => {
  const { hub, base } = await setup();
  const http = base.replace(/^ws/, 'http');
  const op = await connect(`${base}/operator`);
  const id = randomUUID(); const token = randomBytes(32).toString('base64url');
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, token) });
  const pending = next(agent, (m) => m.type === 'pending');
  await new Promise((resolve) => agent.once('open', resolve));
  await pending;
  const settings = next(agent, (m) => m.type === 'settings');
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  await settings;
  const ready = next(op, (m) => m.type === 'session-ready');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  const { sessionId } = await ready;

  const toAgent = next(agent, (m) => m.type === 'file-download');
  const sent = await fetch(`${http}/transfers/upload`, { method: 'POST', headers: { cookie: operatorCookie, 'x-transfer-session': sessionId, 'x-transfer-name': 'teste.txt' }, body: 'abc' });
  assert.equal(sent.status, 201);
  const delivered = await toAgent;
  assert.equal(delivered.name, 'teste.txt');
  const downloaded = await fetch(`${http}/transfers/${delivered.transferId}`, { headers: agentHeaders(id, token) });
  assert.equal(await downloaded.text(), 'abc');

  const completion = next(op, (m) => m.type === 'file-status' && m.transferId === delivered.transferId);
  agent.send(JSON.stringify({ type: 'status', event: 'file-delivery', data: { sessionId, transferId: delivered.transferId, delivered: true } }));
  assert.equal((await completion).delivered, true, 'o operador so recebe sucesso depois de o agente confirmar a gravacao');

  const toOperator = next(op, (m) => m.type === 'file-ready');
  const returned = await fetch(`${http}/transfers/upload`, { method: 'POST', headers: { ...agentHeaders(id, token), 'x-transfer-session': sessionId, 'x-transfer-name': 'volta.zip' }, body: 'xyz' });
  assert.equal(returned.status, 201);
  const available = await toOperator;
  assert.equal(available.name, 'volta.zip');
  const back = await fetch(`${http}/transfers/${available.transferId}`, { headers: { cookie: operatorCookie } });
  assert.equal(await back.text(), 'xyz');
  agent.close(); op.close(); hub.close();
});

test('operador autenticado recebe ticket LAN descartavel; o ticket nao e segredo persistido', async () => {
  const { hub, base } = await setup();
  const op = await connect(`${base}/operator`);
  const issued = next(op, (m) => m.type === 'lan-ticket');
  op.send(JSON.stringify({ type: 'lan-ticket' }));
  const ticket = await issued;
  assert.match(ticket.ticket, /^[a-f0-9-]{36}$/i);
  assert.equal(ticket.url, 'wss://hub-int.tththiago.com.br/operator');
  assert.equal(ticket.expiresInMs, 30_000);
  op.close(); hub.close();
});

test('clipboard de imagem no modo compativel usa transferencia temporaria e comando correlacionado', async () => {
  const { hub, base } = await setup();
  const http = base.replace(/^ws/, 'http');
  const op = await connect(`${base}/operator`);
  const id = randomUUID(); const token = randomBytes(32).toString('base64url');
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, token) });
  const pending = next(agent, (m) => m.type === 'pending');
  await new Promise((resolve) => agent.once('open', resolve));
  await pending;
  const settings = next(agent, (m) => m.type === 'settings');
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  await settings;
  const ready = next(op, (m) => m.type === 'session-ready');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  const { sessionId } = await ready;

  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const uploaded = await fetch(`${http}/transfers/upload`, {
    method: 'POST',
    headers: { cookie: operatorCookie, 'x-transfer-session': sessionId, 'x-transfer-name': 'clipboard.png', 'x-transfer-kind': 'clipboard-image' },
    body: png,
  });
  assert.equal(uploaded.status, 201);
  const receipt = await uploaded.json();
  assert.equal(receipt.size, png.length);

  const trace = `${sessionId}:9`;
  const command = next(agent, (m) => m.type === 'cmd' && m.data?.trace === trace);
  op.send(JSON.stringify({ type: 'cmd', sessionId, data: {
    t: 'clip-image', transferId: receipt.id, size: png.length, sha256: '0'.repeat(64), q: 9, trace,
  } }));
  const forwarded = await command;
  assert.equal(forwarded.data.transferId, receipt.id);
  assert.equal(forwarded.data.size, png.length);

  const downloaded = await fetch(`${http}/transfers/${receipt.id}`, { headers: agentHeaders(id, token) });
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), png);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal((await fetch(`${http}/transfers/${receipt.id}`, { headers: agentHeaders(id, token) })).status, 404,
    'imagem temporaria e removida depois do primeiro download');

  const result = next(op, (m) => m.type === 'input-result' && m.data?.trace === trace);
  agent.send(JSON.stringify({ type: 'status', event: 'input-result', data: {
    sessionId, trace, seq: 9, command: 'clip-image', transport: 'compat', result: 'applied',
  } }));
  assert.equal((await result).data.result, 'applied');
  agent.close(); op.close(); hub.close();
});

test('pasta compartilhada: preserva subpastas, baixa pasta em ZIP e remove arvore sem sair da raiz', async () => {
  const { hub, base } = await setup();
  const http = base.replace(/^ws/, 'http');
  const id = randomUUID(); const token = randomBytes(32).toString('base64url');
  const agent = await connect(`${base}/agent`, agentHeaders(id, token));
  const headers = agentHeaders(id, token);
  const url = `${http}/shared/file?clientId=${id}&path=${encodeURIComponent('sub/teste.txt')}`;
  assert.equal((await fetch(url, { method: 'PUT', headers, body: 'conteudo' })).status, 201);
  const nested = `${http}/shared/file?clientId=${id}&path=${encodeURIComponent('sub/interna/outro.txt')}`;
  assert.equal((await fetch(nested, { method: 'PUT', headers, body: 'segundo' })).status, 201);
  const manifest = await (await fetch(`${http}/shared/manifest?clientId=${id}`, { headers })).json();
  assert.deepEqual(manifest.files.map((f) => f.path), ['sub/interna/outro.txt', 'sub/teste.txt']);
  assert.equal(await (await fetch(url, { headers })).text(), 'conteudo');
  const archive = await fetch(`${http}/shared/archive?clientId=${id}&path=sub`, { headers });
  assert.equal(archive.status, 200);
  assert.match(archive.headers.get('content-type'), /application\/zip/);
  assert.match(archive.headers.get('content-disposition'), /sub\.zip/);
  const zip = Buffer.from(await archive.arrayBuffer());
  assert.deepEqual([...zip.subarray(0, 2)], [0x50, 0x4b]);
  assert.ok(zip.includes(Buffer.from('sub/teste.txt')) && zip.includes(Buffer.from('sub/interna/outro.txt')), 'ZIP preserva a pasta raiz e suas subpastas');
  assert.equal((await fetch(`${http}/shared/file?clientId=${id}&path=..%2Ffora.txt`, { method: 'PUT', headers, body: 'x' })).status, 403);
  assert.equal((await fetch(`${http}/shared/file?clientId=${id}&path=sub`, { method: 'DELETE', headers })).status, 204);
  assert.deepEqual((await (await fetch(`${http}/shared/manifest?clientId=${id}`, { headers })).json()).files, []);
  agent.close(); hub.close();
});

test('registro automatico, aprovacao, configuracao e sinalizacao', async () => {
  const { hub, base } = await setup();
  const op = await connect(`${base}/operator`);
  const id = randomUUID();
  const token = randomBytes(32).toString('base64url');

  // Maquina nova se registra sozinha e fica pendente.
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, token) });
  const pending = next(agent, (m) => m.type === 'pending');
  await new Promise((r) => agent.once('open', r));
  await pending;
  const listed = await next(op, (m) => m.type === 'clients' && m.clients.some((c) => c.id === id));
  assert.equal(listed.clients.find((c) => c.id === id).approved, false);
  assert.equal(listed.clients.find((c) => c.id === id).name, 'PC Teste');

  // Sem aprovacao nao abre sessao.
  const err = next(op, (m) => m.type === 'error');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  assert.equal((await err).error, 'not-approved');

  // Aprovar entrega as configuracoes.
  const settings = next(agent, (m) => m.type === 'settings');
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  assert.equal((await settings).settings.allowRemoteControl, true);

  const upd = next(agent, (m) => m.type === 'settings' && m.settings.keepAwake === true);
  op.send(JSON.stringify({ type: 'update-client', clientId: id, settings: { keepAwake: true, evil: 1 } }));
  assert.equal((await upd).settings.evil, undefined);

  // Renomear: so admin, vai pro store e volta no broadcast da lista.
  const renamed = next(op, (m) => m.type === 'clients' && m.clients.find((c) => c.id === id)?.name === 'Recepção');
  op.send(JSON.stringify({ type: 'update-client', clientId: id, name: 'Recepção' }));
  assert.equal((await renamed).clients.find((c) => c.id === id).name, 'Recepção');

  // Estatisticas de CPU/RAM: chegam do agente, nao vao pro disco (so em memoria), aparecem no broadcast.
  const withStats = next(op, (m) => m.type === 'clients' && m.clients.find((c) => c.id === id)?.stats?.cpuPct != null);
  agent.send(JSON.stringify({ type: 'stats', data: { cpuPct: 7.5, memUsedMb: 2048, memTotalMb: 8192, bogus: 'x', nan: NaN } }));
  const statsClient = (await withStats).clients.find((c) => c.id === id);
  assert.deepEqual(statsClient.stats, { cpuPct: 7.5, memUsedMb: 2048, memTotalMb: 8192 });

  const opened = next(agent, (m) => m.type === 'session-open');
  const ready = next(op, (m) => m.type === 'session-ready');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  const { sessionId } = await ready;
  assert.equal((await opened).sessionId, sessionId);

  const offer = next(agent, (m) => m.type === 'rtc');
  op.send(JSON.stringify({ type: 'rtc', sessionId, data: { sdp: 'x' } }));
  assert.deepEqual((await offer).data, { sdp: 'x' });
  const answer = next(op, (m) => m.type === 'rtc');
  agent.send(JSON.stringify({ type: 'rtc', sessionId, data: { sdp: 'y' } }));
  assert.deepEqual((await answer).data, { sdp: 'y' });

  // Modo compativel: a sonda faz o caminho inteiro operador → agente → operador.
  const ping = next(agent, (m) => m.type === 'cmd' && m.data?.t === 'latency-ping');
  const pong = next(op, (m) => m.type === 'compat-pong' && m.sessionId === sessionId);
  op.send(JSON.stringify({ type: 'cmd', sessionId, data: { t: 'latency-ping', n: 42 } }));
  assert.deepEqual((await ping).data, { t: 'latency-ping', n: 42 });
  agent.send(JSON.stringify({ type: 'compat-pong', sessionId, data: { n: 42 } }));
  assert.deepEqual((await pong).data, { n: 42 });

  agent.close();
  op.close();
  hub.close();
});

test('comandos nao geram auditoria e confirmacoes funcionais continuam chegando', async () => {
  const { hub, base } = await setup();
  const op = await connect(`${base}/operator`);
  const id = randomUUID(); const token = randomBytes(32).toString('base64url');
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, token) });
  const pending = next(agent, (m) => m.type === 'pending');
  await new Promise((resolve) => agent.once('open', resolve));
  await pending;
  const settings = next(agent, (m) => m.type === 'settings');
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  await settings;
  const ready = next(op, (m) => m.type === 'session-ready');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  const { sessionId } = await ready;
  const trace = `${sessionId}:1`;

  const command = next(agent, (m) => m.type === 'cmd' && m.data?.trace === trace);
  op.send(JSON.stringify({ type: 'cmd', sessionId, data: { t: 'clip-image', q: 1, trace, transferId: 'arquivo', size: 10 } }));
  assert.equal((await command).data.q, 1);

  const agentAck = next(agent, (m) => m.type === 'input-result-ack' && m.trace === trace);
  const result = next(op, (m) => m.type === 'input-result' && m.data?.trace === trace);
  agent.send(JSON.stringify({ type: 'status', event: 'input-result', data: {
    sessionId, trace, seq: 1, command: 'clip-image', transport: 'compat', result: 'applied',
  } }));
  await agentAck;
  assert.equal((await result).data.result, 'applied');

  const logId = randomUUID();
  let logAck = next(agent, (m) => m.type === 'agent-log-ack' && m.id === logId);
  const centralLog = { type: 'status', event: 'agent-log', data: { id: logId, code: 'connection', level: 'warn', detail: 'reconexao tecnica' } };
  agent.send(JSON.stringify(centralLog));
  await logAck;
  // Confirmacao perdida pode causar reenvio: o hub confirma de novo, mas nao duplica a linha.
  logAck = next(agent, (m) => m.type === 'agent-log-ack' && m.id === logId);
  agent.send(JSON.stringify(centralLog));
  await logAck;

  assert.equal(hub.store.accessLogsDir, undefined);
  const agentRows = readFileSync(join(hub.store.logsDir, `${id}.jsonl`), 'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(agentRows.filter((x) => x.id === logId).length, 1);
  agent.close(); op.close(); hub.close();
});

test('impostor com ID conhecido e segredo errado e recusado; reconexao legitima funciona', async () => {
  const { hub, base } = await setup();
  const id = randomUUID();
  const token = randomBytes(32).toString('base64url');

  const first = await connect(`${base}/agent`, agentHeaders(id, token));
  first.close();
  await assert.rejects(connect(`${base}/agent`, agentHeaders(id, randomBytes(32).toString('base64url'))));
  const again = await connect(`${base}/agent`, agentHeaders(id, token));
  again.close();
  await assert.rejects(connect(`${base}/agent`, agentHeaders('nao-e-uuid', token)));
  hub.close();
});

test('com AUTO_APPROVE a maquina ja entra aprovada', async () => {
  const { config } = await import('../src/config.js');
  config.autoApprove = true;
  const { hub, base } = await setup();
  const id = randomUUID();
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, randomBytes(32).toString('base64url')) });
  const first = next(agent, () => true);
  await new Promise((r) => agent.once('open', r));
  assert.equal((await first).type, 'settings');
  config.autoApprove = false;
  agent.close();
  hub.close();
});

test('modo compativel: frames binarios do agente chegam so ao operador da sessao; comandos voltam ao agente', async () => {
  const { hub, base } = await setup();
  const op = await connect(`${base}/operator`);
  const id = randomUUID();
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, randomBytes(32).toString('base64url')) });
  await new Promise((r) => agent.once('open', r));
  const listed = next(op, (m) => m.type === 'clients' && m.clients.some((c) => c.id === id));
  await listed;
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  await next(agent, (m) => m.type === 'settings');

  const ready = next(op, (m) => m.type === 'session-ready');
  op.send(JSON.stringify({ type: 'session-open', clientId: id }));
  const { sessionId } = await ready;

  const idBytes = Buffer.from(sessionId.replace(/-/g, ''), 'hex');
  const frame = Buffer.concat([Buffer.from([1]), idBytes, Buffer.from([73, 0, 0, 7, 128, 0, 0, 4, 56])]);
  const got = new Promise((res) => op.once('message', (d, isBinary) => res({ d, isBinary })));
  agent.send(frame);
  const { d, isBinary } = await got;
  assert.equal(isBinary, true);
  assert.deepEqual(Buffer.from(d), frame);

  // frame de uma sessao que nao e do agente e descartado
  let leaked = false;
  const spy = () => { leaked = true; };
  op.on('message', spy);
  agent.send(Buffer.concat([Buffer.from([1]), Buffer.alloc(16, 7), Buffer.from([73])]));
  await new Promise((r) => setTimeout(r, 150));
  op.off('message', spy);
  assert.equal(leaked, false);

  // comando do operador chega ao agente
  const cmd = next(agent, (m) => m.type === 'cmd');
  op.send(JSON.stringify({ type: 'cmd', sessionId, data: { t: 'mm', x: 0.5, y: 0.25 } }));
  const received = await cmd;
  assert.deepEqual(received.data, { t: 'mm', x: 0.5, y: 0.25 });

  agent.close();
  op.close();
  hub.close();
});

test('auto-update: oferece o pacote so a maquina aprovada com jar diferente; download exige credencial', async () => {
  const { mkdirSync, writeFileSync } = await import('node:fs');
  const { Updates } = await import('../src/updates.js');
  const dir = mkdtempSync(join(tmpdir(), 'upd-'));
  mkdirSync(dir, { recursive: true });
  const newJar = 'a'.repeat(64);
  const zipBytes = Buffer.from('conteudo-do-zip');
  writeFileSync(join(dir, 'agent-update.zip'), zipBytes);
  writeFileSync(join(dir, 'agent-update.zip.sig'), 'assinatura');
  writeFileSync(join(dir, 'meta.json'), JSON.stringify({ version: 'v2', jarSha256: newJar, zipSha256: 'b'.repeat(64), size: zipBytes.length }));

  const { hub, base } = await setup({ updates: new Updates(dir) });
  const port = hub.server.address().port;
  const op = await connect(`${base}/operator`);
  const id = randomUUID();
  const token = randomBytes(32).toString('base64url');
  const agent = new WebSocket(`${base}/agent`, { headers: agentHeaders(id, token) });
  const pending = next(agent, (m) => m.type === 'pending'); // ouvinte ANTES do open: o hub manda 'pending' logo ao conectar
  await new Promise((r) => agent.once('open', r));
  await pending;

  // pendente: hello com jar diferente NAO recebe oferta; download tambem e negado
  let offered = false;
  const spy = (raw) => { if (JSON.parse(raw).type === 'update') offered = true; };
  agent.on('message', spy);
  agent.send(JSON.stringify({ type: 'hello', hostname: 'pc', jarSha256: 'c'.repeat(64) }));
  await new Promise((r) => setTimeout(r, 150));
  assert.equal(offered, false, 'maquina pendente nao recebe oferta');
  const auth = { 'x-client-id': id, authorization: `Bearer ${token}` };
  assert.equal((await fetch(`http://127.0.0.1:${port}/updates/agent-update.zip`, { headers: auth })).status, 401);

  // aprovar -> a oferta chega na hora
  const upd = next(agent, (m) => m.type === 'update');
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  const offer = await upd;
  assert.equal(offer.jarSha256, newJar);
  assert.equal(offer.version, 'v2');

  // download: com credencial 200 e bytes identicos; sem credencial 401; nome fora da lista 404
  const ok = await fetch(`http://127.0.0.1:${port}/updates/agent-update.zip`, { headers: auth });
  assert.equal(ok.status, 200);
  assert.deepEqual(Buffer.from(await ok.arrayBuffer()), zipBytes);
  assert.equal((await fetch(`http://127.0.0.1:${port}/updates/agent-update.zip`)).status, 401);
  assert.equal((await fetch(`http://127.0.0.1:${port}/updates/meta.json`, { headers: auth })).status, 404);
  assert.equal((await fetch(`http://127.0.0.1:${port}/updates/..%2f..%2fclients.json`, { headers: auth })).status, 404);

  // agente ja na versao publicada: nada a oferecer
  agent.close();
  const agent2 = new WebSocket(`${base}/agent`, { headers: auth });
  const got = [];
  agent2.on('message', (raw) => got.push(JSON.parse(raw).type));
  await new Promise((r) => agent2.once('open', r));
  agent2.send(JSON.stringify({ type: 'hello', hostname: 'pc', jarSha256: newJar }));
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(got.includes('update'), false, 'jar igual ao publicado: sem oferta');

  agent2.close();
  op.close();
  hub.close();
});

test('configuracao antiga receivedFilesDir e removida ao carregar e rejeitada se enviada', async () => {
  const { mkdirSync, writeFileSync, readFileSync } = await import('node:fs');
  const dir = mkdtempSync(join(tmpdir(), 'hub-'));
  mkdirSync(dir, { recursive: true });
  const id = randomUUID();
  writeFileSync(join(dir, 'clients.json'), JSON.stringify({
    [id]: { id, name: 'Velha', tokenHash: 'a'.repeat(64), approved: true, settings: { allowRemoteControl: true, receivedFilesDir: 'recebidos', quality: 'auto' }, info: {} },
  }));
  const store = new Store(dir);
  assert.equal('receivedFilesDir' in store.get(id).settings, false, 'valor antigo descartado ao carregar');
  assert.equal(store.get(id).settings.quality, 'auto', 'demais configuracoes preservadas');
  store.update(id, { settings: { receivedFilesDir: 'D:/entrada', keepAwake: true } });
  assert.equal('receivedFilesDir' in store.get(id).settings, false, 'campo removido nao e mais aceito');
  assert.equal(store.get(id).settings.keepAwake, true);
  assert.equal('receivedFilesDir' in JSON.parse(readFileSync(join(dir, 'clients.json'), 'utf8'))[id].settings, false);
});

test('historico de atualizacao: versao, oferta, aplicando, falha e reversao ficam registrados na maquina', async () => {
  const { mkdirSync, writeFileSync } = await import('node:fs');
  const { Updates } = await import('../src/updates.js');
  const dir = mkdtempSync(join(tmpdir(), 'upd-'));
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'agent-update.zip'), 'zip');
  writeFileSync(join(dir, 'agent-update.zip.sig'), 'sig');
  writeFileSync(join(dir, 'meta.json'), JSON.stringify({ version: 'v2', jarSha256: 'a'.repeat(64), zipSha256: 'b'.repeat(64), size: 3 }));
  const { hub, base } = await setup({ updates: new Updates(dir) });
  const op = await connect(`${base}/operator`);
  const id = randomUUID();
  const hdr = agentHeaders(id, randomBytes(32).toString('base64url'));
  const histOf = (m) => m.clients.find((c) => c.id === id)?.history || [];

  const agent = new WebSocket(`${base}/agent`, { headers: hdr });
  const first = next(agent, (m) => m.type === 'pending');
  await new Promise((r) => agent.once('open', r));
  await first;
  op.send(JSON.stringify({ type: 'approve-client', clientId: id }));
  await next(agent, (m) => m.type === 'settings');

  // primeira informacao de versao + oferta do pacote novo
  const seen = next(op, (m) => m.type === 'clients' && histOf(m).some((h) => h.type === 'offered'));
  agent.send(JSON.stringify({ type: 'hello', hostname: 'pc', jarSha256: 'c'.repeat(64) }));
  let h = histOf(await seen);
  assert.deepEqual(h.map((x) => x.type), ['version', 'offered']);
  assert.equal(h[0].to, 'cccccccc');
  assert.deepEqual([h[1].from, h[1].to], ['cccccccc', 'aaaaaaaa']);

  // eventos do agente durante a atualizacao
  const ev = next(op, (m) => m.type === 'clients' && histOf(m).some((x) => x.type === 'rollback'));
  agent.send(JSON.stringify({ type: 'status', event: 'update-applying', data: { from: 'cccccccc', to: 'aaaaaaaa' } }));
  agent.send(JSON.stringify({ type: 'status', event: 'update-failed', data: { to: 'aaaaaaaa', error: 'ASSINATURA INVALIDA: pacote recusado' } }));
  agent.send(JSON.stringify({ type: 'status', event: 'update-rollback', data: { badVersion: 'aaaaaaaa', currentVersion: 'cccccccc' } }));
  h = histOf(await ev);
  assert.deepEqual(h.map((x) => x.type), ['version', 'offered', 'applying', 'failed', 'rollback']);
  assert.match(h[3].text, /ASSINATURA INVALIDA/);
  assert.ok(h.every((x) => typeof x.t === 'number'), 'cada evento tem horario');

  const diagnostic = next(op, (m) => m.type === 'clients' && histOf(m).some((x) => x.type === 'diagnostic'));
  agent.send(JSON.stringify({ type: 'status', event: 'agent-diagnostic', data: { code: 'service-mode', detail: 'Servico nao liberou o jar para atualizacao' } }));
  h = histOf(await diagnostic);
  assert.match(h.at(-1).text, /^\[service-mode\] Servico nao liberou/);

  // a oferta do mesmo alvo nao se repete no historico; reconectar com a versao nova registra a mudanca
  agent.close();
  const agent2 = new WebSocket(`${base}/agent`, { headers: hdr });
  await new Promise((r) => agent2.once('open', r));
  const upd = next(op, (m) => m.type === 'clients' && histOf(m).some((x) => x.type === 'version' && x.to === 'aaaaaaaa'));
  agent2.send(JSON.stringify({ type: 'hello', hostname: 'pc', jarSha256: 'a'.repeat(64) }));
  h = histOf(await upd);
  assert.equal(h.filter((x) => x.type === 'offered').length, 1, 'oferta registrada uma vez por alvo');
  assert.deepEqual([h.at(-1).type, h.at(-1).from, h.at(-1).to], ['version', 'cccccccc', 'aaaaaaaa']);

  // limite: guarda so os ultimos 100 (e o painel recebe os ultimos 30)
  for (let i = 0; i < 120; i++) hub.store.addHistory(id, { type: 'failed', to: 'x', text: String(i) });
  assert.equal(hub.store.get(id).history.length, 100);
  const pub = next(op, (m) => m.type === 'clients');
  hub.store.addHistory(id, { type: 'failed', text: 'ultimo' });
  agent2.send(JSON.stringify({ type: 'hello', hostname: 'pc2', jarSha256: 'a'.repeat(64) }));
  assert.ok(histOf(await pub).length <= 30);

  agent2.close();
  op.close();
  hub.close();
});
