#!/bin/bash
# Compila os fontes do projeto (sem build tool) para a pasta out/
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

mkdir -p out

# Coleta todos os arquivos .java
find src -name "*.java" > out/sources.txt

# Compila
javac -d out -encoding UTF-8 @out/sources.txt
rm -f out/sources.txt

echo "✅ Compilacao concluida com sucesso. Classes em: out/"
