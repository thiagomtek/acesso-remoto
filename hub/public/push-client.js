// Lado do navegador do PWA: registra o service worker, assina/cancela o Web Push deste
// dispositivo e oferece a instalacao do app.

const b64ToBytes = (b64) => {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
};

const post = (path, body) => fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body || {}) });

export function deviceLabel(ua = navigator.userAgent) {
  const os = /Android/i.test(ua) ? 'Android' : /iPhone|iPad/i.test(ua) ? 'iOS' : /Windows/i.test(ua) ? 'Windows' : /Mac/i.test(ua) ? 'macOS' : /Linux/i.test(ua) ? 'Linux' : 'Dispositivo';
  const br = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navegador';
  return `${br} em ${os}`;
}

export class PushClient {
  constructor() {
    this.reg = null;
    this.installPrompt = null;
    this.supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
    this.ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
    this.onChange = () => {};
    window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); this.installPrompt = e; this.onChange(); });
    window.addEventListener('appinstalled', () => { this.installPrompt = null; this.onChange(); });
  }

  get installed() { return matchMedia('(display-mode: standalone)').matches || navigator.standalone === true; }

  async register() {
    if (!('serviceWorker' in navigator)) return null;
    try { this.reg = await navigator.serviceWorker.register('/sw.js'); } catch { this.reg = null; }
    this.onChange();
    return this.reg;
  }

  async subscription() {
    if (!this.supported) return null;
    const reg = this.reg || (await navigator.serviceWorker.getRegistration('/sw.js'));
    return reg ? reg.pushManager.getSubscription() : null;
  }

  /** 'unsupported' | 'denied' | 'on' | 'off' */
  async state() {
    if (!this.supported) return 'unsupported';
    if (Notification.permission === 'denied') return 'denied';
    const sub = await this.subscription();
    return sub ? 'on' : 'off';
  }

  async enable() {
    if (!this.supported) throw new Error('Este navegador não suporta notificações push' + (this.ios ? ' (no iPhone, instale o app na tela inicial primeiro).' : '.'));
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') throw new Error('Permissão de notificação negada.');
    const reg = this.reg || (await this.register());
    if (!reg) throw new Error('Service worker indisponível.');
    await navigator.serviceWorker.ready;
    const { publicKey } = await (await fetch('/api/push/key')).json();
    let sub = await reg.pushManager.getSubscription();
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(publicKey) });
    const res = await post('/api/push/subscribe', { subscription: sub.toJSON(), label: deviceLabel() });
    if (!res.ok) throw new Error('O hub recusou a inscrição.');
    this.onChange();
  }

  async disable() {
    const sub = await this.subscription();
    if (sub) {
      await post('/api/push/unsubscribe', { endpoint: sub.endpoint }).catch(() => {});
      await sub.unsubscribe().catch(() => {});
    }
    this.onChange();
  }

  async test() {
    const res = await post('/api/push/test');
    return res.ok ? (await res.json()).sent : 0;
  }

  async install() {
    if (!this.installPrompt) return false;
    this.installPrompt.prompt();
    await this.installPrompt.userChoice.catch(() => {});
    this.installPrompt = null;
    this.onChange();
    return true;
  }
}
