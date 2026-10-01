#!/bin/bash
# Compila e organiza os pacotes finais na pasta dist de forma limpa e modular.
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# 1. Compila os fontes para out/
echo "Compilando fontes..."
./compile.sh

# 2. Limpa e recria a estrutura da pasta dist/
echo "Organizando estrutura da pasta dist..."
rm -rf dist
mkdir -p dist/Server/certs dist/Server/updates dist/Client/certs

# 3. Empacota os JARs executaveis diretamente nas pastas correspondentes
(
    cd out
    jar --create --file ../dist/Server/transacao-server.jar --main-class com.transacao.server.ServerApp com/transacao/common com/transacao/server
    jar --create --file ../dist/Client/transacao-client.jar --main-class com.transacao.client.ClientApp com/transacao/common com/transacao/client
)

# 4. Copia o JAR do client para a pasta de atualizacoes do servidor (auto-update)
cp dist/Client/transacao-client.jar dist/Server/updates/transacao-client.jar

# 5. Copia os certificados especificos de cada lado
if [ -d "certs" ]; then
    [ -f "certs/server.jks" ] && cp certs/server.jks dist/Server/certs/
    [ -f "certs/server-truststore.jks" ] && cp certs/server-truststore.jks dist/Server/certs/
    [ -f "certs/client.jks" ] && cp certs/client.jks dist/Client/certs/
    [ -f "certs/client-truststore.jks" ] && cp certs/client-truststore.jks dist/Client/certs/
fi

# 6. Criar scripts de inicializacao para Mac (.command)
cat << 'EOF' > dist/Server/iniciar-servidor.command
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

if [ -x "$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java" ]; then
    JAVA_CMD="$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Java nao encontrado nesta maquina. Instale o Java (JRE 17+)."
    read -p "Pressione Enter para sair..."
    exit 1
fi

"$JAVA_CMD" -Djava.net.preferIPv4Stack=true -jar "$DIR/transacao-server.jar"
EOF
chmod +x dist/Server/iniciar-servidor.command

cat << 'EOF' > dist/Client/iniciar-client.command
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

if [ -x "$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java" ]; then
    JAVA_CMD="$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Java nao encontrado nesta maquina. Instale o Java (JRE 17+)."
    read -p "Pressione Enter para sair..."
    exit 1
fi

"$JAVA_CMD" -Djava.net.preferIPv4Stack=true -jar "$DIR/transacao-client.jar"
EOF
chmod +x dist/Client/iniciar-client.command

# 7. Criar scripts Windows nos pacotes (para manter paridade)
if [ -f "scripts/resolve-java.ps1" ]; then
    cp scripts/resolve-java.ps1 dist/Server/ 2>/dev/null || true
    cp scripts/resolve-java.ps1 dist/Client/ 2>/dev/null || true
fi

# 8. Gera o pacote ZIP de instalacao do Client
echo "Gerando pacote ZIP de instalacao do Client..."
(
    cd dist/Client
    zip -r ../transacao-client-instalador.zip . -q
)

# 9. Gera o ZIP de extras para auto-update
echo "Gerando pacote de extras para auto-update..."
mkdir -p dist/_extras_stage
cp -r dist/Client/* dist/_extras_stage/
rm -f dist/_extras_stage/transacao-client.jar
(
    cd dist/_extras_stage
    zip -r ../Server/updates/transacao-client-extras.zip . -q
)
rm -rf dist/_extras_stage

echo "🎉 Build concluido com sucesso! Estrutura pronta em dist/"
