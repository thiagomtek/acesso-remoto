// Bolinha flutuante do visualizador: arrastavel para qualquer lugar da tela; ao clicar (sem arrastar)
// abre/fecha o menu. Substitui a barra fixa que cobria controles da tela remota (como a barra da RDP,
// mas sem ocupar espaco). Quando parada some quase por completo; o mouse em cima a traz de volta.

const SIZE = 44;
const MARGIN = 8;
const DRAG_THRESHOLD = 5;
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/**
 * @param {HTMLElement} orb   elemento da bolinha
 * @param {HTMLElement} menu  menu que abre ao lado dela
 * @param {{storage?:Storage|null, key?:string, onOpen?:()=>void, onClose?:()=>void}} opts
 */
export function mountOrb(orb, menu, { storage = null, key = 'viewerOrbPos', onOpen = () => {}, onClose = () => {} } = {}) {
  let drag = null;
  let idleTimer = null;
  const vw = () => window.innerWidth || 1024;
  const vh = () => window.innerHeight || 768;

  // Posicao guardada como fracao da area livre: sobrevive a mudar o tamanho da janela / tela cheia.
  let frac = { x: 1, y: 0.4 }; // padrao: encostada na direita, perto do meio
  try {
    const saved = JSON.parse(storage?.getItem(key) || 'null');
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) frac = { x: clamp(saved.x, 0, 1), y: clamp(saved.y, 0, 1) };
  } catch { /* sem storage: usa o padrao */ }

  const toPx = () => ({
    x: MARGIN + frac.x * Math.max(0, vw() - SIZE - 2 * MARGIN),
    y: MARGIN + frac.y * Math.max(0, vh() - SIZE - 2 * MARGIN),
  });

  function place() {
    const { x, y } = toPx();
    orb.style.left = `${Math.round(x)}px`;
    orb.style.top = `${Math.round(y)}px`;
    placeMenu();
  }

  /** O menu abre para o lado com mais espaco, sem sair da tela. */
  function placeMenu() {
    if (menu.hidden) return;
    const { x, y } = toPx();
    const mw = menu.offsetWidth || 230;
    const mh = menu.offsetHeight || 260;
    const left = x + SIZE / 2 > vw() / 2 ? x - mw - 8 : x + SIZE + 8;
    const top = clamp(y, MARGIN, Math.max(MARGIN, vh() - mh - MARGIN));
    menu.style.left = `${Math.round(clamp(left, MARGIN, Math.max(MARGIN, vw() - mw - MARGIN)))}px`;
    menu.style.top = `${Math.round(top)}px`;
  }

  function setOpen(open) {
    const was = !menu.hidden;
    menu.hidden = !open;
    orb.classList.toggle('open', open);
    orb.setAttribute('aria-expanded', String(open));
    if (open) {
      placeMenu();
      wake();
      if (!was) onOpen();
    } else {
      scheduleIdle();
      if (was) onClose();
    }
  }

  function wake() {
    clearTimeout(idleTimer);
    orb.classList.remove('idle');
  }

  function scheduleIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => { if (menu.hidden) orb.classList.add('idle'); }, 3000);
    idleTimer.unref?.();
  }

  orb.addEventListener('pointerdown', (e) => {
    drag = { sx: e.clientX, sy: e.clientY, ox: toPx().x, oy: toPx().y, moved: false, id: e.pointerId };
    try { orb.setPointerCapture?.(e.pointerId); } catch { /* ignora */ }
    wake();
    e.stopPropagation();
  });
  orb.addEventListener('pointermove', (e) => {
    wake();
    if (!drag) return;
    const dx = e.clientX - drag.sx;
    const dy = e.clientY - drag.sy;
    if (!drag.moved && Math.hypot(dx, dy) >= DRAG_THRESHOLD) drag.moved = true;
    if (!drag.moved) return;
    const free = (n) => Math.max(1, n);
    frac = {
      x: clamp((drag.ox + dx - MARGIN) / free(vw() - SIZE - 2 * MARGIN), 0, 1),
      y: clamp((drag.oy + dy - MARGIN) / free(vh() - SIZE - 2 * MARGIN), 0, 1),
    };
    place();
  });
  const end = () => {
    if (!drag) return;
    const moved = drag.moved;
    drag = null;
    if (moved) {
      try { storage?.setItem(key, JSON.stringify(frac)); } catch { /* sem storage */ }
      scheduleIdle();
    } else {
      setOpen(menu.hidden); // clique simples (sem arrastar): abre/fecha
    }
  };
  orb.addEventListener('pointerup', (e) => { e.stopPropagation(); end(); });
  orb.addEventListener('pointercancel', () => { drag = null; });
  orb.addEventListener('pointerenter', wake);
  orb.addEventListener('pointerleave', () => { if (menu.hidden) scheduleIdle(); });
  window.addEventListener('resize', place);
  menu.addEventListener('pointerdown', (e) => e.stopPropagation());

  place();
  scheduleIdle();
  return {
    open: () => setOpen(true),
    close: () => setOpen(false),
    toggle: () => setOpen(menu.hidden),
    place,
    get isOpen() { return !menu.hidden; },
  };
}
