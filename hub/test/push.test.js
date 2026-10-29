import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PushService, validSubscription } from '../src/push.js';

const sub = (n = 1) => ({ endpoint: `https://push.example/${n}`, keys: { p256dh: 'BPabc_-1', auth: 'xyz_-9' } });
const make = (send, clock = { t: 1_000_000 }) => ({ clock, svc: new PushService(mkdtempSync(join(tmpdir(), 'push-')), { send, now: () => clock.t }) });

test('valida inscricao: so https e chaves base64url', () => {
  assert.equal(validSubscription(sub()), true);
  assert.equal(validSubscription({ ...sub(), endpoint: 'http://x' }), false);
  assert.equal(validSubscription({ endpoint: 'https://x', keys: { p256dh: 'a b', auth: 'c' } }), false);
  assert.equal(validSubscription(null), false);
});

test('inscricao e por conta, sem duplicar endpoint, e persiste a chave VAPID', () => {
  const { svc } = make(async () => {});
  assert.ok(svc.publicKey.length > 40);
  assert.equal(svc.subscribe('u1', sub(1), 'Chrome em Android'), true);
  svc.subscribe('u1', sub(1));
  svc.subscribe('u2', sub(2));
  assert.equal(svc.count('u1'), 1);
  assert.equal(svc.has('u2', sub(2).endpoint), true);
  assert.equal(svc.has('u1', sub(2).endpoint), false);
  assert.equal(svc.subscribe('u1', { endpoint: 'x' }), false);
  const again = new PushService(svc.file.replace(/push\.json$/, ''));
  assert.equal(again.publicKey, svc.publicKey);
  assert.equal(again.count('u1'), 1);
});

test('notify envia so para a conta, limita por maquina e remove inscricao expirada (410)', async () => {
  const calls = [];
  const { svc, clock } = make(async (s, body) => {
    calls.push({ s, body });
    if (s.endpoint.endsWith('/dead')) throw Object.assign(new Error('gone'), { statusCode: 410 });
  });
  svc.subscribe('u1', sub(1));
  svc.subscribe('u1', { endpoint: 'https://push.example/dead', keys: sub().keys });
  svc.subscribe('u2', sub(2));
  assert.equal(await svc.notify('u1', { title: 'T' }, { key: 'm1' }), 1);
  assert.equal(calls.length, 2);
  assert.equal(svc.count('u1'), 1, 'inscricao morta removida');
  assert.equal(await svc.notify('u1', { title: 'T' }, { key: 'm1' }), 0, 'dentro do intervalo');
  clock.t += 31_000;
  assert.equal(await svc.notify('u1', { title: 'T' }, { key: 'm1' }), 1);
  assert.equal(await svc.notify('u1', { title: 'T' }, { key: 'm1', force: true }), 1);
  assert.equal(await svc.notify('u3', { title: 'T' }), 0);
});
