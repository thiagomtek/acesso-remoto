#!/bin/bash
# Compila o agente e publica a atualizacao a partir do servidor Linux (equivale a build.ps1 + publish-update.ps1,
# mais a parte do package.ps1 que atualiza o installer-base do hub).
#
#  - compila agent/src (+ ../src) e monta o jar com o mesmo manifest do build.ps1;
#  - monta agent-update.zip (assistente.jar + transacao-agent.jar, mesmo conteudo), calcula os hashes e ASSINA o zip
#    com a chave privada Ed25519 (~/.remote-hub/update-signing.key, via agent/tools/sign.js);
#  - grava no hub: zip e .sig primeiro, meta.json por ultimo (e ele que libera a versao para os agentes);
#  - copia o MESMO jar para installer-base (instalacoes novas ja vem na versao publicada). Sem isso, um agente
#    instalado do zip antigo seria "atualizado" para o jar publicado a cada oferta.
#
# Requer: javac/jar (JDK 17+), node, zip, sha256sum. A chave privada tem copia em
# /mnt/d/Servidores/Configuracoes/Atualizacao/ (coberta pelo restic): se ~/.remote-hub sumir, restaure de la.
#
# Uso: agent/publish-update.sh [--target DIR_DATA_DO_HUB] [--no-installer-base] [--include-libs]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="/mnt/d/Servidores/Servicos/remote-hub/data"
INSTALLER_BASE=1
INCLUDE_LIBS=0
while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --no-installer-base) INSTALLER_BASE=0; shift ;;
        --include-libs) INCLUDE_LIBS=1; shift ;;
        *) echo "argumento desconhecido: $1" >&2; exit 64 ;;
    esac
done

for bin in javac jar node zip sha256sum; do
    command -v "$bin" >/dev/null || { echo "Falta '$bin' no PATH." >&2; exit 1; }
done
[ -f "$HOME/.remote-hub/update-signing.key" ] || { echo "Chave privada ausente: ~/.remote-hub/update-signing.key (restaure de /mnt/d/Servidores/Configuracoes/Atualizacao/)." >&2; exit 1; }
[ -d "$TARGET" ] || { echo "Pasta de dados do hub nao existe: $TARGET" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
OUT="$WORK/out"; mkdir -p "$OUT"

cd "$HERE"
LIBVER="0.19.0"
find src -name '*.java' > "$WORK/srcs.txt"
javac -d "$OUT" -encoding UTF-8 -cp "lib/*" -sourcepath ../src @"$WORK/srcs.txt"
cp -r src/META-INF "$OUT/META-INF"
printf 'Main-Class: com.transacao.agent.AgentApp\r\nClass-Path: lib/webrtc-java-%s.jar lib/webrtc-java-%s-windows-x86_64.jar lib/webrtc-java-%s-windows-aarch64.jar lib/webrtc-java-%s-macos-x86_64.jar lib/webrtc-java-%s-macos-aarch64.jar\r\n' \
    "$LIBVER" "$LIBVER" "$LIBVER" "$LIBVER" "$LIBVER" > "$WORK/manifest.txt"
JAR="$WORK/transacao-agent.jar"
jar --create --file "$JAR" --manifest "$WORK/manifest.txt" --date="$(date -u +%Y-%m-%dT%H:%M:%SZ)" -C "$OUT" .

STAGE="$WORK/stage"; mkdir -p "$STAGE"
cp "$JAR" "$STAGE/transacao-agent.jar"
cp "$JAR" "$STAGE/assistente.jar"
ENTRIES=(assistente.jar transacao-agent.jar)
if [ "$INCLUDE_LIBS" = 1 ]; then
    mkdir -p "$STAGE/lib"; cp lib/*.jar "$STAGE/lib/"; ENTRIES+=(lib)
fi
ZIP="$WORK/agent-update.zip"
(cd "$STAGE" && zip -q -r -X -9 "$ZIP" "${ENTRIES[@]}")

JARH="$(sha256sum "$JAR" | cut -d' ' -f1)"
ZIPH="$(sha256sum "$ZIP" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$ZIP")"
VERSION="$(date +%Y%m%d-%H%M%S)"
node "$HERE/tools/sign.js" "$ZIP" > "$WORK/agent-update.zip.sig"
[ -s "$WORK/agent-update.zip.sig" ] || { echo "Falha ao assinar o pacote." >&2; exit 1; }
node -e 'const [v,j,z,s]=process.argv.slice(1);process.stdout.write(JSON.stringify({version:v,jarSha256:j,zipSha256:z,size:Number(s),createdAt:new Date().toISOString()},null,4))' \
    "$VERSION" "$JARH" "$ZIPH" "$SIZE" > "$WORK/meta.json"

mkdir -p "$TARGET/updates"
cp "$ZIP" "$TARGET/updates/agent-update.zip"
cp "$WORK/agent-update.zip.sig" "$TARGET/updates/agent-update.zip.sig"
cp "$WORK/meta.json" "$TARGET/updates/meta.json"

if [ "$INSTALLER_BASE" = 1 ]; then
    mkdir -p "$TARGET/installer-base"
    cp "$JAR" "$TARGET/installer-base/transacao-agent.jar"
    cp "$JAR" "$TARGET/installer-base/assistente.jar"
fi

echo "Publicado: versao $VERSION | jar ${JARH:0:12} | $(( SIZE / 1024 )) KB | libs=$INCLUDE_LIBS | installer-base=$INSTALLER_BASE"
echo "Destino: $TARGET"
