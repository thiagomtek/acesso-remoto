import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const hash = (value) => createHash('sha256').update(value).digest('hex');

/** Contas do painel: senhas derivadas com scrypt; sessoes persistem somente o hash do token. */
export class Accounts {
  constructor(dir) {
    this.file = join(dir, 'accounts.json');
    this.data = existsSync(this.file) ? JSON.parse(readFileSync(this.file, 'utf8')) : { users: {}, enrollments: {}, sessions: {} };
    this.data.users ||= {}; this.data.enrollments ||= {}; this.data.sessions ||= {};
    // Remove sessoes vencidas ou orfas na inicializacao, sem manter estado paralelo em memoria.
    let changed = false;
    for (const [key, entry] of Object.entries(this.data.sessions)) {
      if (!entry || entry.expiresAt < Date.now() || !this.data.users[entry.userId]) {
        delete this.data.sessions[key];
        changed = true;
      }
    }
    if (changed) this.save();
  }
  save() { const tmp = `${this.file}.tmp`; writeFileSync(tmp, JSON.stringify(this.data, null, 2)); renameSync(tmp, this.file); }
  register(email, password) {
    email = String(email || '').trim().toLowerCase();
    if (!EMAIL.test(email)) throw new Error('email-invalido');
    if (typeof password !== 'string' || password.length < 7 || password.length > 200) throw new Error('senha-invalida');
    if (Object.values(this.data.users).some((u) => u.email === email)) throw new Error('email-existente');
    const salt = randomBytes(16); const digest = scryptSync(password, salt, 32);
    const user = { id: randomUUID(), email, salt: salt.toString('base64'), passwordHash: digest.toString('base64'), createdAt: Date.now() };
    this.data.users[user.id] = user; this.save(); return this.public(user);
  }
  login(email, password) {
    email = String(email || '').trim().toLowerCase();
    const user = Object.values(this.data.users).find((u) => u.email === email);
    if (!user || typeof password !== 'string') return null;
    const candidate = scryptSync(password, Buffer.from(user.salt, 'base64'), 32);
    if (!timingSafeEqual(candidate, Buffer.from(user.passwordHash, 'base64'))) return null;
    return this.public(user);
  }
  setPassword(email, password) {
    email = String(email || '').trim().toLowerCase();
    if (typeof password !== 'string' || password.length < 7 || password.length > 200) throw new Error('senha-invalida');
    const user = Object.values(this.data.users).find((u) => u.email === email);
    if (!user) return this.register(email, password);
    const salt = randomBytes(16); const digest = scryptSync(password, salt, 32);
    user.salt = salt.toString('base64'); user.passwordHash = digest.toString('base64'); this.save();
    return this.public(user);
  }
  changePassword(userId, currentPassword, newPassword) {
    const user = this.data.users[userId];
    if (!user || typeof currentPassword !== 'string') throw new Error('senha-atual-invalida');
    const candidate = scryptSync(currentPassword, Buffer.from(user.salt, 'base64'), 32);
    if (!timingSafeEqual(candidate, Buffer.from(user.passwordHash, 'base64'))) throw new Error('senha-atual-invalida');
    if (typeof newPassword !== 'string' || newPassword.length < 7 || newPassword.length > 200) throw new Error('senha-invalida');
    const salt = randomBytes(16); const digest = scryptSync(newPassword, salt, 32);
    user.salt = salt.toString('base64'); user.passwordHash = digest.toString('base64'); this.save();
    return this.public(user);
  }
  createSession(userId) {
    const token = randomBytes(32).toString('base64url');
    this.data.sessions[hash(token)] = { userId, expiresAt: Date.now() + 7 * 86400_000 };
    this.save();
    return token;
  }
  session(cookie) {
    const token = /(?:^|;\s*)hub_session=([^;]+)/.exec(String(cookie || ''))?.[1];
    const key = token ? hash(token) : '';
    const entry = key ? this.data.sessions[key] : null;
    if (!entry || entry.expiresAt < Date.now() || !this.data.users[entry.userId]) {
      if (entry) { delete this.data.sessions[key]; this.save(); }
      return null;
    }
    return this.public(this.data.users[entry.userId]);
  }
  logout(cookie) {
    const token = /(?:^|;\s*)hub_session=([^;]+)/.exec(String(cookie || ''))?.[1];
    const key = token ? hash(token) : '';
    if (key && this.data.sessions[key]) { delete this.data.sessions[key]; this.save(); }
  }
  revokeOtherSessions(userId, cookie) {
    const token = /(?:^|;\s*)hub_session=([^;]+)/.exec(String(cookie || ''))?.[1];
    const keep = token ? hash(token) : '';
    let changed = false;
    for (const [key, entry] of Object.entries(this.data.sessions)) if (entry.userId === userId && key !== keep) {
      delete this.data.sessions[key];
      changed = true;
    }
    if (changed) this.save();
  }
  createEnrollment(userId) {
    if (!this.data.users[userId]) throw new Error('usuario-invalido');
    const token = randomBytes(32).toString('base64url');
    // Um instalador e emitido para uma maquina. O prazo folgado evita que um ZIP baixado hoje
    // deixe de funcionar antes de ser levado ate o computador de destino.
    this.data.enrollments[hash(token)] = { userId, expiresAt: Date.now() + 30 * 86400_000, used: false };
    this.save();
    return token;
  }
  resolveEnrollment(token) {
    const row = this.data.enrollments[hash(String(token || ''))];
    return row && !row.used && row.expiresAt >= Date.now() && this.data.users[row.userId] ? row.userId : null;
  }
  consumeEnrollment(token) {
    const key = hash(String(token || ''));
    const row = this.data.enrollments[key];
    if (!row || row.used || row.expiresAt < Date.now() || !this.data.users[row.userId]) return null;
    row.used = true;
    this.save();
    return row.userId;
  }
  /** Compatibilidade: com uma unica conta, agentes legados sem matricula pertencem a ela. */
  onlyUserId() {
    const ids = Object.keys(this.data.users);
    return ids.length === 1 ? ids[0] : null;
  }
  owns(userId, client) { return !!userId && client?.ownerId === userId; }
  public(user) { return user ? { id: user.id, email: user.email } : null; }
}
