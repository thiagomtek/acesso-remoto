// Configuracoes de uma maquina, compartilhadas pelo cartao da lista e pela tela de visualizacao.
// Qualquer mudanca vai ao hub (update-client), que a aplica na hora no agente da maquina.

export const SETTINGS = [
  ['localOnly', 'Bloquear conexão estritamente à rede local (LAN)'],
  ['startWithSystem', 'Iniciar com o sistema'],
  ['webrtcEnabled', 'Habilitar streaming WebRTC (DLL nativa)'],
  ['nativeKeyboardHelper', 'Habilitar injeção de teclado via PowerShell'],
  ['autoUpdate', 'Permitir atualização automática do agente'],
  ['clipboardSync', 'Sincronizar área de transferência'],
  ['keepAwake', 'Manter computador ativo (anti-suspensão)'],
  ['teamsWatcher', 'Avisar sobre atividade no Teams'],
];

export const SETTINGS_GROUPS = [
  {
    title: 'Rede & Conectividade',
    keys: [
      ['localOnly', 'Bloquear conexão estritamente à rede local (LAN)'],
    ],
  },
  {
    title: 'Segurança & Antivírus (Modo Discreto)',
    keys: [
      ['startWithSystem', 'Iniciar com o sistema'],
      ['webrtcEnabled', 'Habilitar streaming WebRTC (DLL nativa)'],
      ['nativeKeyboardHelper', 'Habilitar injeção de teclado via PowerShell'],
      ['autoUpdate', 'Permitir atualização automática do agente'],
    ],
  },
  {
    title: 'Recursos Operacionais',
    keys: [
      ['clipboardSync', 'Sincronizar área de transferência'],
      ['keepAwake', 'Manter computador ativo (anti-suspensão)'],
      ['teamsWatcher', 'Avisar sobre atividade no Teams'],
    ],
  },
];

export const QUALITY = [
  ['auto', 'Automática (recomendado)'],
  ['max', 'Máxima nitidez'],
  ['balanced', 'Equilibrada'],
  ['economy', 'Economia de banda'],
];

/**
 * @param {object} client  maquina (id, name, settings)
 * @param {{admin:boolean, send:(m:object)=>void, withRemove?:boolean}} opts
 */
export function buildSettingsPanel(client, { admin, send, withRemove = false }) {
  const update = (settings) => send({ type: 'update-client', clientId: client.id, settings });
  const panel = document.createElement('div');
  panel.className = 'settings';

  for (const group of SETTINGS_GROUPS) {
    const gh = document.createElement('div');
    gh.className = 'settings-group-title';
    gh.textContent = group.title;
    panel.append(gh);

    for (const [key, label] of group.keys) {
      const l = document.createElement('label');
      l.className = 'opt';
      const cb = Object.assign(document.createElement('input'), { type: 'checkbox', checked: !!client.settings?.[key], disabled: !admin });
      cb.onchange = () => update({ [key]: cb.checked });
      l.append(cb, label);
      panel.append(l);
    }
  }

  const q = document.createElement('select');
  q.disabled = !admin;
  for (const [v, t] of QUALITY) {
    q.append(Object.assign(document.createElement('option'), { value: v, textContent: t, selected: client.settings?.quality === v }));
  }
  q.onchange = () => update({ quality: q.value });
  const ql = document.createElement('label');
  ql.className = 'opt';
  ql.append(q, 'Qualidade'); // a linha inverte a ordem (rotulo a esquerda, controle a direita)

  panel.append(ql);

  if (admin && withRemove) {
    const rm = Object.assign(document.createElement('button'), { textContent: 'Remover máquina' });
    rm.onclick = () => {
      if (confirm(`Remover "${client.name}"? O client será desconectado e o token revogado.`)) send({ type: 'revoke-client', clientId: client.id });
    };
    panel.append(rm);
  }
  if (!admin) {
    const note = Object.assign(document.createElement('div'), { className: 'sub', textContent: 'Somente administradores podem alterar.' });
    panel.append(note);
  }
  return panel;
}
