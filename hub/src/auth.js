import { createRemoteJWKSet, jwtVerify } from 'jose';
import { createHmac } from 'node:crypto';
import { config } from './config.js';

let jwks;
function getJwks() {
  jwks ??= createRemoteJWKSet(new URL(`https://${config.accessTeamDomain}/cdn-cgi/access/certs`));
  return jwks;
}

/**
 * Valida o JWT que o Cloudflare Access injeta no header Cf-Access-Jwt-Assertion.
 * Devolve { email, admin } ou null. A borda ja barrou quem nao tem acesso, mas
 * validamos aqui tambem: quem alcancar o hub sem passar pelo Access nao entra.
 */
export async function authenticateOperator(headers) {
  if (config.insecureDev) return { email: 'dev@local', admin: true };
  const token = headers['cf-access-jwt-assertion'];
  if (!token) return null;
  try {
    const { payload } = await jwtVerify(token, getJwks(), {
      issuer: `https://${config.accessTeamDomain}`,
      audience: config.accessAud,
    });
    const email = String(payload.email || '').toLowerCase();
    if (!email) return null;
    const admin = config.adminEmails.length === 0 || config.adminEmails.includes(email);
    return { email, admin };
  } catch {
    return null;
  }
}

/**
 * Clients (agentes) entram pela borda com um Service Token do Access. O Access injeta o
 * JWT, cujo `common_name` e o Client ID do token. Exigimos esse JWT tambem aqui.
 */
export async function authenticateAgentEdge(headers) {
  if (config.insecureDev) return true;
  // Modo Tailscale: a borda e a malha privada; so aceita o host configurado.
  if (config.edgeAuth === 'tailscale') {
    return config.tailscaleHosts.includes(String(headers.host || '').toLowerCase());
  }
  // O Caddy adiciona este marcador somente no vhost TLS privado hub-int. A identidade propria do
  // agente (id + segredo, validada no server.js) continua obrigatoria; portanto o marcador sozinho
  // nunca concede acesso a uma maquina.
  if (headers['x-transacao-lan'] === '1' && String(headers.host || '').toLowerCase() === config.lanHost) return true;
  const token = headers['cf-access-jwt-assertion'];
  if (!token) return false;
  try {
    const { payload } = await jwtVerify(token, getJwks(), {
      issuer: `https://${config.accessTeamDomain}`,
      audience: config.accessAud,
    });
    return typeof payload.common_name === 'string' && payload.common_name.length > 0;
  } catch {
    return false;
  }
}

/** Servidores STUN/TURN, com credenciais TURN efemeras (use-auth-secret do coturn). */
export function iceServers(subject) {
  const servers = config.stunUrls.length ? [{ urls: config.stunUrls }] : [];
  if (config.turnUrls.length && config.turnSecret) {
    const username = `${Math.floor(Date.now() / 1000) + config.turnTtlSeconds}:${subject}`;
    const credential = createHmac('sha1', config.turnSecret).update(username).digest('base64');
    servers.push({ urls: config.turnUrls, username, credential });
  }
  return servers;
}
