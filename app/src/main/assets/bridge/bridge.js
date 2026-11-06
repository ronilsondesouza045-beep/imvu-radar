/* ============================================================
   IMVU BRIDGE (Android/WebView)  v1.1
   Roda DENTRO da pagina do IMVU (WebView do app) usando a sessao
   do navegador. Se o app estiver presente, entrega os dados por
   window.AndroidBridge.onRoster(json); se nao, desenha o painel.
   ============================================================ */
(() => {
  const API = 'https://api.imvu.com';
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const log = (...a) => console.log('[IMVU Bridge]', ...a);
  const m = location.pathname.match(/room-(\d+-\d+)/);
  let rid = m ? m[1] : null;
  let last = new Map();
  let panel = null;

  function makePanel() {
    if (panel || typeof window.AndroidBridge === 'undefined') return;
    panel = document.createElement('div');
    panel.id = '__imvu_bridge';
    panel.style.cssText = 'position:fixed;right:8px;bottom:8px;width:300px;max-height:60vh;overflow:auto;z-index:2147483000;background:rgba(8,11,19,.95);color:#e9eefb;border:1px solid rgba(130,170,255,.35);border-radius:14px;padding:10px;font:12px/1.45 system-ui,sans-serif';
    (document.body || document.documentElement).appendChild(panel);
  }

  function draw(meta, people, note) {
    const priv = meta && meta.privacy === 'private';
    const payload = {
      roomId: rid,
      name: (meta && meta.name) || '',
      privacy: (meta && meta.privacy) || '',
      occupancy: (meta && meta.occupancy != null) ? meta.occupancy : -1,
      capacity: (meta && meta.capacity != null) ? meta.capacity : -1,
      owner: (meta && meta.owner) || '',
      count: people.length,
      note: note || '',
      people: people.map(p => ({ cid: p.cid, username: p.username, display_name: p.display_name, avatar: p.avatar }))
    };
    try {
      if (window.AndroidBridge && window.AndroidBridge.onRoster) {
        window.AndroidBridge.onRoster(JSON.stringify(payload));
      }
    } catch (e) { log('falha ao entregar ao app', e.message); }

    makePanel();
    if (!panel) return;
    const rows = people.map(p => `
      <div style="display:flex;gap:7px;align-items:center;padding:3px 0;border-bottom:1px solid rgba(130,170,255,.1)">
        <img src="${esc(p.avatar || '')}" style="width:24px;height:24px;border-radius:7px;object-fit:cover;background:#111">
        <div style="min-width:0"><div style="font-weight:650;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(p.display_name || p.username)}</div>
        <div style="font-size:10px;opacity:.65;font-family:monospace">${esc(p.username)} · ${esc(p.cid)}</div></div></div>`).join('');
    panel.innerHTML = `<div style="font-weight:800">${esc((meta && meta.name) || 'SALA ' + rid)}</div>
      <div style="font-size:10.5px;opacity:.7;font-family:monospace">[${esc(rid)}] · ${priv ? 'PRIVADA' : 'PUBLICA'} · ${(meta && meta.occupancy != null) ? meta.occupancy + '/' + meta.capacity : '?'}</div>
      <div style="font-size:10.5px;opacity:.6;margin:4px 0 6px">${people.length} pessoa(s)</div>`
      + (people.length ? rows : '<div style="opacity:.7;font-size:11px">ninguem visivel</div>');
  }

  async function readRoom() {
    if (!rid) return;
    let meta = null, note = '';
    try {
      const r = await fetch(`${API}/room/room-${rid}`, { credentials: 'include' });
      if (r.ok) {
        const j = await r.json();
        for (const [k, v] of Object.entries(j.denormalized || {})) {
          const d = (v && v.data) || null;
          if (d && d.name) { meta = { name: d.name, privacy: d.privacy, occupancy: d.occupancy, capacity: d.capacity, owner: d.owner_avatarname }; break; }
        }
      } else {
        let msg = ''; try { msg = (await r.json()).message || ''; } catch (_) { }
        note = 'restrita: ' + r.status + (msg ? ' · ' + msg : '');
      }
    } catch (e) { note = 'erro: ' + e.message; }

    let people = [];
    try {
      const r2 = await fetch(`${API}/chat/chat-${rid}/participants`, { credentials: 'include' });
      if (r2.ok) {
        const j2 = await r2.json();
        for (const [k, v] of Object.entries(j2.denormalized || {})) {
          if (!k.endsWith('/participants')) continue;
          for (const it of ((v.data && v.data.items) || [])) {
            const node = j2.denormalized[it] || {};
            const ref = (node.relations && node.relations.ref) || '';
            const d = (j2.denormalized[ref] && j2.denormalized[ref].data) || {};
            people.push({ cid: ref.split('-').pop(), username: d.username, display_name: d.display_name, avatar: d.thumbnail_url });
          }
        }
      } else {
        let msg = ''; try { msg = (await r2.json()).message || ''; } catch (_) { }
        note = 'roster bloqueado: ' + r2.status + (msg ? ' · ' + msg : '');
      }
    } catch (e) { note = 'erro: ' + e.message; }

    const now = new Map(people.map(p => [p.cid, p]));
    for (const [c, p] of now) if (!last.has(c)) log('ENTROU', p.username || c);
    for (const [c, p] of last) if (!now.has(c)) log('SAIU', p.username || c);
    last = now;
    draw(meta, people, note);
  }

  function checkRoute() {
    const m2 = location.pathname.match(/room-(\d+-\d+)/);
    const r2 = m2 ? m2[1] : null;
    if (r2 !== rid) { rid = r2; last = new Map(); readRoom(); }
  }

  window.__bridgeTick = readRoom;
  readRoom();
  setInterval(readRoom, 4000);
  setInterval(checkRoute, 1500);
  log('ativo (webview) - sala', rid);
})();
