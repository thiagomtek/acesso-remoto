# Gera keystores e truststores self-signed para autenticacao SSL mutua
# entre o server e o client do app de transacao de arquivos.
#
# Requer o keytool do JDK no PATH.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$password = "changeit"

$files = @("server.jks", "client.jks", "server.cer", "client.cer", "server-truststore.jks", "client-truststore.jks")
foreach ($f in $files) {
    if (Test-Path $f) { Remove-Item $f -Force }
}

Write-Host "Gerando par de chaves do servidor..."
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore server.jks -storepass $password -keypass $password `
    -dname "CN=Server, OU=Dev, O=Transacao, L=Local, ST=Local, C=BR"

Write-Host "Gerando par de chaves do client..."
keytool -genkeypair -alias client -keyalg RSA -keysize 2048 -validity 3650 `
    -keystore client.jks -storepass $password -keypass $password `
    -dname "CN=Client, OU=Dev, O=Transacao, L=Local, ST=Local, C=BR"

Write-Host "Exportando certificados publicos..."
keytool -exportcert -alias server -keystore server.jks -storepass $password -file server.cer
keytool -exportcert -alias client -keystore client.jks -storepass $password -file client.cer

Write-Host "Montando truststore do servidor (confia no client)..."
keytool -importcert -alias client -keystore server-truststore.jks -storepass $password -file client.cer -noprompt

Write-Host "Montando truststore do client (confia no servidor)..."
keytool -importcert -alias server -keystore client-truststore.jks -storepass $password -file server.cer -noprompt

Write-Host ""
Write-Host "Certificados gerados com sucesso em $here"
Write-Host "Senha de todos os keystores/truststores: $password"
