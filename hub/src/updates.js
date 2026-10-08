import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// Pacote de atualizacao do agente publicado em <updatesDir>:
//   agent-update.zip       jar novo (+ arquivos extras)
//   agent-update.zip.sig   assinatura Ed25519 do zip (base64) - o agente so aceita se bater com a chave dele
//   meta.json              { version, jarSha256, zipSha256, size, createdAt }
// O hub NAO assina nem valida a assinatura: so entrega. Quem confia/valida e o agente.
const FILES = new Set(['agent-update.zip', 'agent-update.zip.sig']);
const HEX64 = /^[0-9a-f]{64}$/;

export class Updates {
  constructor(dir) {
    this.dir = dir;
    this.cache = null;
  }

  /** Metadados da versao publicada, ou null se nao ha pacote completo e valido. */
  meta() {
    const mf = join(this.dir, 'meta.json');
    try {
      const st = statSync(mf);
      if (this.cache && this.cache.mtime === st.mtimeMs) return this.cache.meta;
      const meta = JSON.parse(readFileSync(mf, 'utf8'));
      if (!HEX64.test(meta.jarSha256 || '') || !HEX64.test(meta.zipSha256 || '')) return null;
      for (const f of FILES) if (!existsSync(join(this.dir, f))) return null;
      this.cache = { mtime: st.mtimeMs, meta };
      return meta;
    } catch {
      return null;
    }
  }

  /** Caminho de um arquivo do pacote (lista fechada de nomes: nada de caminhos arbitrarios). */
  filePath(name) {
    return FILES.has(name) ? join(this.dir, name) : null;
  }
}

export const isHex64 = (s) => HEX64.test(s || '');
