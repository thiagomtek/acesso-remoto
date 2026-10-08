// Gera o par de chaves Ed25519 que assina os pacotes de atualizacao do agente.
// A chave PRIVADA fica so em ~/.remote-hub/update-signing.key (fora do repositorio, nunca versionar).
// A chave PUBLICA (impressa) vai embutida no agente (AgentUpdater.PUBLIC_KEY_B64): cada agente so
// aceita atualizacao assinada por esta chave, mesmo que o hub ou o canal fossem comprometidos.
// Uso: node agent/tools/gen-update-key.js
const { generateKeyPairSync } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const dir = path.join(os.homedir(), '.remote-hub');
const file = path.join(dir, 'update-signing.key');
if (fs.existsSync(file)) {
  console.error('Ja existe ' + file + '. Apague-o manualmente se quiser gerar outra (isso invalida os agentes ja instalados).');
  process.exit(1);
}
fs.mkdirSync(dir, { recursive: true });
const { publicKey, privateKey } = generateKeyPairSync('ed25519');
fs.writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'), { mode: 0o600 });
console.log('Chave privada gravada em ' + file);
console.log('PUBLIC_KEY_B64 = ' + publicKey.export({ type: 'spki', format: 'der' }).toString('base64'));
