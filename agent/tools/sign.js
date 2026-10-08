// Assina um arquivo com a chave privada Ed25519 de ~/.remote-hub/update-signing.key e imprime a assinatura em base64.
// Uso: node agent/tools/sign.js <arquivo>
const { createPrivateKey, sign } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const file = process.argv[2];
if (!file) {
  console.error('uso: node sign.js <arquivo>');
  process.exit(2);
}
const keyFile = path.join(os.homedir(), '.remote-hub', 'update-signing.key');
if (!fs.existsSync(keyFile)) {
  console.error('Chave nao encontrada: ' + keyFile + ' (gere com agent/tools/gen-update-key.js)');
  process.exit(1);
}
const key = createPrivateKey({ key: Buffer.from(fs.readFileSync(keyFile, 'utf8').trim(), 'base64'), format: 'der', type: 'pkcs8' });
process.stdout.write(sign(null, fs.readFileSync(file), key).toString('base64'));
