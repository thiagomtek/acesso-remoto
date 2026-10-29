// Service worker do PWA: recebe os avisos push (atividade no Teams) e abre o painel ao tocar.
// Nao guarda nada em cache: o painel exige login e sempre vai a rede.

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
// Necessario para o navegador considerar o app instalavel; repassa tudo a rede.
self.addEventListener('fetch', () => {});

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { title: 'Acesso Remoto', body: event.data?.text() || '' }; }
  event.waitUntil((async () => {
    // Painel aberto e visivel: ele mesmo ja avisa (sirene/modal); evita notificacao duplicada.
    if (!data.test) {
      const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      if (wins.some((w) => w.visibilityState === 'visible' && w.focused)) return;
    }
    await self.registration.showNotification(data.title || 'Acesso Remoto', {
      body: data.body || '',
      tag: data.tag || 'teams',
      renotify: true,
      requireInteraction: true,
      vibrate: [500, 200, 500, 200, 500, 200, 500],
      icon: '/icons/icon-192.png',
      badge: '/icons/badge.png',
      data: { clientId: data.clientId || '' },
    });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const open = wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (open) { await open.focus(); return; }
    await self.clients.openWindow('/');
  })());
});
