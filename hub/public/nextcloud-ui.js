// Modal de configuracao e sincronizacao bidirecional da pasta recebimentos com o Nextcloud pelo Hub.

export const DEFAULT_NEXTCLOUD_URL = 'https://servidor.tail074692.ts.net:8445/';

export async function requestNextcloudSync(clientId, onProgress = null) {
  const url = `/api/nextcloud/sync?clientId=${encodeURIComponent(clientId)}`;
  const res = await fetch(url, { method: 'POST', credentials: 'same-origin', cache: 'no-store' });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Falha ao solicitar sincronização ao agente.');
  if (!data.requestId) throw new Error('Hub não confirmou a solicitação de sincronização.');

  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const statusRes = await fetch(`/api/nextcloud?clientId=${encodeURIComponent(clientId)}`, {
      credentials: 'same-origin', cache: 'no-store',
    });
    if (!statusRes.ok) throw new Error('Falha ao consultar o estado da sincronização.');
    const status = await statusRes.json();
    const nc = status.nextcloud || {};
    if (nc.syncing && onProgress) onProgress(nc);
    if (nc.completedRequestId === data.requestId) {
      if (nc.completedError) throw new Error(nc.completedError);
      return nc;
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  throw new Error('O agente não confirmou a sincronização em dois minutos. Verifique se ele está online.');
}

function ensureModal(hostEl = document.body) {
  let modal = document.getElementById('nextcloud-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'nextcloud-modal';
    modal.className = 'account-modal nextcloud-modal';
    modal.hidden = true;
    modal.innerHTML = `
      <button class="account-backdrop" type="button" data-nc-close aria-label="Fechar configurações do Nextcloud"></button>
      <section class="account-dialog nextcloud-dialog" role="dialog" aria-modal="true" aria-labelledby="nc-title">
        <header class="account-head">
          <div>
            <p class="account-eyebrow">Espelho Bidirecional · Recebimentos</p>
            <h2 id="nc-title">Nextcloud</h2>
          </div>
          <button class="btn ghost account-close" type="button" data-nc-close aria-label="Fechar">×</button>
        </header>
        <form class="account-form nextcloud-form" data-nc-form>
          <label class="nc-toggle">
            <input type="checkbox" data-nc-enabled>
            <span>Ativar espelhamento automático com o Nextcloud</span>
          </label>
          <label>URL do Nextcloud
            <input type="url" data-nc-url placeholder="https://servidor.tail074692.ts.net:8445/" required>
          </label>
          <label>Usuário do Nextcloud
            <input type="text" data-nc-user autocomplete="username" placeholder="Usuário da conta no Nextcloud">
          </label>
          <label>Chave de API / Senha de aplicativo
            <input type="password" data-nc-pass autocomplete="new-password" placeholder="Gerada em Segurança → Senhas de aplicativo">
          </label>
          <label>Pasta no Nextcloud
            <input type="text" data-nc-folder placeholder="/ (raiz da conta) ou /Recebimentos">
          </label>
          <p class="account-message" data-nc-status role="status" aria-live="polite"></p>
          <div class="account-form-actions nextcloud-actions">
            <button class="btn ghost" type="button" data-nc-sync>Sincronizar agora</button>
            <div class="grow"></div>
            <button class="btn ghost" type="button" data-nc-close>Fechar</button>
            <button class="btn primary" type="submit" data-nc-save>Salvar</button>
          </div>
        </form>
      </section>`;
    for (const btn of modal.querySelectorAll('[data-nc-close]')) {
      btn.onclick = () => closeNextcloudModal();
    }
  }
  const targetParent = document.fullscreenElement || hostEl || document.body;
  if (modal.parentElement !== targetParent) targetParent.append(modal);
  return modal;
}

export function closeNextcloudModal() {
  const modal = document.getElementById('nextcloud-modal');
  if (modal) modal.hidden = true;
}

export async function openNextcloudModal(client, opts = {}) {
  if (!client?.id) return null;
  const modal = ensureModal(opts.container || document.body);
  const titleEl = modal.querySelector('#nc-title');
  const form = modal.querySelector('[data-nc-form]');
  const enabledInput = modal.querySelector('[data-nc-enabled]');
  const urlInput = modal.querySelector('[data-nc-url]');
  const userInput = modal.querySelector('[data-nc-user]');
  const passInput = modal.querySelector('[data-nc-pass]');
  const folderInput = modal.querySelector('[data-nc-folder]');
  const statusEl = modal.querySelector('[data-nc-status]');
  const saveBtn = modal.querySelector('[data-nc-save]');
  const syncBtn = modal.querySelector('[data-nc-sync]');

  titleEl.textContent = `Nextcloud — ${client.name || 'Máquina'}`;
  enabledInput.checked = false;
  urlInput.value = DEFAULT_NEXTCLOUD_URL;
  userInput.value = '';
  passInput.value = '';
  passInput.placeholder = 'Gerada em Segurança → Senhas de aplicativo';
  folderInput.value = '/';

  const setStatus = (text, ok = false) => {
    statusEl.textContent = text || '';
    statusEl.classList.toggle('ok', Boolean(ok));
  };

  const applyServerState = (nc) => {
    if (!nc) return;
    enabledInput.checked = Boolean(nc.enabled);
    urlInput.value = nc.url || DEFAULT_NEXTCLOUD_URL;
    userInput.value = nc.username || '';
    folderInput.value = nc.remoteFolder || '/';
    passInput.value = '';
    passInput.placeholder = nc.hasPassword
      ? '•••••••• (já configurada — deixe em branco para manter)'
      : 'Gerada em Segurança → Senhas de aplicativo';
    if (nc.syncing) {
      setStatus(nc.totalFiles > 0
        ? `Sincronizando ${nc.processedFiles} de ${nc.totalFiles} arquivos…`
        : 'Sincronização em andamento no agente…', true);
    } else if (nc.lastError) {
      setStatus(`Erro: ${nc.lastError}`, false);
    } else if (nc.lastSyncAt) {
      const timeStr = new Date(nc.lastSyncAt).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
      setStatus(`Espelho ativo · Última sincronização às ${timeStr}`, true);
    } else if (nc.enabled) {
      setStatus('Espelho ativo e aguardando sincronização.', true);
    } else {
      setStatus('Configure o usuário e a senha de aplicativo do Nextcloud para espelhar a pasta recebimentos.', true);
    }
  };

  modal.hidden = false;
  setStatus('Carregando configuração…', true);

  try {
    const res = await fetch(`/api/nextcloud?clientId=${encodeURIComponent(client.id)}`, {
      credentials: 'same-origin',
      cache: 'no-store',
    });
    if (res.ok) {
      const data = await res.json();
      applyServerState(data.nextcloud);
    } else {
      setStatus('Não foi possível carregar a configuração do Nextcloud.');
    }
  } catch {
    setStatus('Não foi possível conectar ao Hub para carregar o Nextcloud.');
  }

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    saveBtn.disabled = true;
    syncBtn.disabled = true;
    setStatus(enabledInput.checked ? 'Validando credenciais e salvando configuração…' : 'Salvando configuração…', true);
    try {
      const payload = {
        enabled: enabledInput.checked,
        url: urlInput.value.trim() || DEFAULT_NEXTCLOUD_URL,
        username: userInput.value.trim(),
        appPassword: passInput.value,
        remoteFolder: folderInput.value.trim() || '/',
      };
      const res = await fetch(`/api/nextcloud?clientId=${encodeURIComponent(client.id)}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        credentials: 'same-origin',
        cache: 'no-store',
        body: JSON.stringify(payload),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setStatus(data.error || 'Falha ao salvar configuração do Nextcloud.', false);
        return;
      }
      applyServerState(data.nextcloud);
      setStatus(
        payload.enabled
          ? 'Configuração salva. O agente iniciará a sincronização; clique em Sincronizar para confirmar o resultado.'
          : 'Configuração do Nextcloud salva (espelhamento desativado).',
        true,
      );
      opts.onSynced?.(data);
    } catch {
      setStatus('Falha de conexão ao salvar configuração do Nextcloud.', false);
    } finally {
      saveBtn.disabled = false;
      syncBtn.disabled = false;
    }
  };

  syncBtn.onclick = async () => {
    saveBtn.disabled = true;
    syncBtn.disabled = true;
    setStatus('Sincronizando com o Nextcloud…', true);
    try {
      const nc = await requestNextcloudSync(client.id, (progress) => {
        if (progress.totalFiles > 0) {
          setStatus(`Sincronizando ${progress.processedFiles} de ${progress.totalFiles} arquivos…`, true);
        }
      });
      applyServerState(nc);
      setStatus(`Sincronização concluída (${nc.lastChanges || 0} alteração(ões)).`, true);
      opts.onSynced?.({ nextcloud: nc });
    } catch (e) {
      setStatus(e.message || 'Falha de conexão ao sincronizar com o Nextcloud.', false);
    } finally {
      saveBtn.disabled = false;
      syncBtn.disabled = false;
    }
  };

  return modal;
}
