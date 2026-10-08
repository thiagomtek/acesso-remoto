// Configuracao do hub, toda via variaveis de ambiente (arquivo .env no compose).
const env = process.env;

export const config = {
  port: Number(env.PORT || 8080),
  dataDir: env.DATA_DIR || './data',
  // Pacote de auto-atualizacao do agente (publicado por agent/publish-update.ps1).
  updatesDir: env.UPDATES_DIR || '',

  // Cloudflare Access (operadores humanos). Em producao ambos sao obrigatorios.
  accessTeamDomain: env.CF_ACCESS_TEAM_DOMAIN || '', // ex: meutime.cloudflareaccess.com
  accessAud: env.CF_ACCESS_AUD || '',
  // Emails que podem ADMINISTRAR (criar/revogar clients, mudar configuracoes).
  // Vazio = todo operador autenticado pelo Access e administrador.
  adminEmails: (env.ADMIN_EMAILS || '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean),

  // true = maquina nova entra ja aprovada (totalmente automatico). false (padrao) = aparece
  // no painel como "nova" e um operador aprova com um clique antes de poder ser acessada.
  autoApprove: env.AUTO_APPROVE === '1',
  maxPending: Number(env.MAX_PENDING || 50),

  // So para desenvolvimento local: dispensa o JWT do Access.
  insecureDev: env.INSECURE_DEV === '1',

  // TURN (coturn com use-auth-secret).
  turnUrls: (env.TURN_URLS || '').split(',').map((s) => s.trim()).filter(Boolean),
  turnSecret: env.TURN_SECRET || '',
  turnTtlSeconds: Number(env.TURN_TTL || 6 * 3600),
  stunUrls: (env.STUN_URLS || 'stun:stun.l.google.com:19302').split(',').map((s) => s.trim()).filter(Boolean),

  // Cloudflare derruba WebSockets ociosos em ~100s: o keep-alive tem que ser bem menor.
  pingIntervalMs: Number(env.PING_INTERVAL_MS || 25000),

  // Entrada LAN pelo Caddy do homelab. Nunca substitui a rota Cloudflare: e usada apenas quando
  // o navegador obtem um ticket efemero de uma sessao Access ja autenticada.
  lanHost: (env.LAN_HUB_HOST || 'hub-int.tththiago.com.br').toLowerCase(),
  lanOperatorUrl: env.LAN_OPERATOR_URL ?? (env.EDGE_AUTH === 'tailscale' ? '' : 'wss://hub-int.tththiago.com.br/operator'),

  // 'cloudflare' (padrao): agentes entram com Service Token validado via JWT do Access.
  // 'tailscale': sem Cloudflare; a malha privada do Tailscale e a borda. Hosts aceitos em
  // TAILSCALE_HOSTS (separados por virgula, com porta, ex: servidor.tail074692.ts.net:8787).
  // A identidade propria do agente (id + segredo) e o login por conta do operador continuam obrigatorios.
  edgeAuth: env.EDGE_AUTH === 'tailscale' ? 'tailscale' : 'cloudflare',
  tailscaleHosts: (env.TAILSCALE_HOSTS || '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean),
};

export function validateConfig() {
  const problems = [];
  if (config.edgeAuth === 'tailscale') {
    if (config.tailscaleHosts.length === 0) problems.push('TAILSCALE_HOSTS ausente');
  } else if (!config.insecureDev) {
    if (!config.accessTeamDomain) problems.push('CF_ACCESS_TEAM_DOMAIN ausente');
    if (!config.accessAud) problems.push('CF_ACCESS_AUD ausente');
  }
  return problems;
}
