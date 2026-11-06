/* IMVU-TRACK :: SPA - uma função por página, renders reais do IMVU */
const $ = s => document.querySelector(s);
const api = (p, o) => fetch(p, o).then(r => r.json()).catch(() => ({}));
const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const b64u = u => btoa(unescape(encodeURIComponent(u)));
const img = u => (u ? '/api/img?u=' + b64u(u) : '');
const page = (location.pathname.replace(/^\//, '') || 'onde').split('/')[0];
const PAGES = ['onde', 'vigia', 'amigos', 'sala', 'caca', 'entrada', 'movimento'];
const app = $('#app');
const mvState = {};

/* ---------- helpers de render ---------- */
function setBg(url) {
  const b = $('#bg');
  if (url) { b.style.backgroundImage = "url('" + img(url) + "')"; b.style.opacity = '.42'; }
  else { b.style.backgroundImage = ''; b.style.opacity = '.42'; }
}
function tagRoom(priv) {
  return priv === 'private' ? '<span class="tag priv">PRIVADA</span>' : '<span class="tag pub">PUBLICA</span>';
}
function personTile(p) {
  const photo = p.avatar || p.render;
  return '<div class="ptile" data-cid="' + esc(p.cid) + '" data-user="' + esc(p.username) + '" data-name="' + esc(p.display_name) + '" data-render="' + esc(p.render || '') + '" data-photo="' + esc(p.avatar || '') + '">' +
    (p.is_vip ? '<span class="vip">VIP</span>' : '') +
    '<span class="live"></span>' +
    '<img loading="lazy" src="' + (photo ? esc(photo) : '') + '" alt="' + esc(p.display_name || p.username) + '" />' +
    '<div class="ovl"><div class="n">' + esc(p.display_name || p.username) + '</div>' +
    '<div class="c">' + esc(p.username) + '</div></div></div>';
}
function peopleGrid(people, empty) {
  if (!people || !people.length) return '<div class="empty">' + (empty || 'ninguem visivel') + '</div>';
  return '<div class="people">' + people.map(personTile).join('') + '</div>';
}
function roomHeader(meta) {
  if (!meta || !meta.name) return '<div class="empty">sala nao encontrada</div>';
  return '<div class="roomline" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:6px 0 12px">' +
    '<span class="roomname">' + esc(meta.name) + '</span>' +
    '<span class="mono muted">[' + esc(meta.roomId) + ']</span>' + tagRoom(meta.privacy) +
    '<span class="mono muted">' + (meta.occupancy || '?') + '/' + (meta.capacity || '?') + '</span>' +
    (meta.owner ? '<span class="muted">dono: ' + esc(meta.owner) + '</span>' : '') + '</div>';
}
function loading(msg) {
  return '<div class="bar"><i></i></div><div class="muted">' + esc(msg) + '</div>';
}

/* ---------- ficha do personagem ---------- */
function openFicha(cid, user, name, render, photo) {
  CURRENT = { cid, user, name, render, photo };
  $('#sheet').innerHTML =
    '<button class="close" onclick="document.getElementById(\'modal\').classList.remove(\'on\')">fechar</button>' +
    '<h3>' + esc(name || user) + '</h3><div class="muted">' + esc(user) + ' · cid ' + esc(cid) + '</div>' +
    '<div class="cols">' +
    '<figure><img class="photo" src="' + esc(photo || render) + '"/><figcaption>foto no jogo</figcaption></figure>' +
    '<figure><img class="big" src="' + esc(render || photo) + '"/><figcaption>avatar 3D</figcaption></figure>' +
    '<figure><img class="big" id="dressImg" src=""/><figcaption>look completo</figcaption></figure>' +
    '</div><div id="outfitBox"></div>';
  $('#modal').classList.add('on');
  api('/api/outfit?cid=' + encodeURIComponent(cid));
}

/* ---------- paginas ---------- */
const RENDER = {
  onde: () => `
    <div class="card">
      <h1>Onde está</h1>
      <p class="sub">digita o nick e descobre a sala atual, quem tá dentro e o look da pessoa</p>
      <div class="row">
        <input id="nick" placeholder="ex: IR0NI" value="IR0NI" />
        <button id="go">Rastrear</button>
      </div>
      <div id="out"></div>
    </div>`,

  vigia: () => `
    <div class="card">
      <h1>Vigia</h1>
      <p class="sub">monitora uma pessoa e avisa em tempo real quando ela troca de sala</p>
      <div class="row">
        <input id="nick" placeholder="nick" value="IR0NI" />
        <input id="int" value="15" style="max-width:90px" title="segundos" />
        <button id="go">Vigiar</button>
        <button class="ghost" id="stop">Parar</button>
      </div>
      <div id="out"></div>
    </div>
    <div class="card"><h2>Linha do tempo</h2><div id="tl" class="scroll"></div></div>`,

  amigos: () => `
    <div class="card">
      <h1>Amigos do bot</h1>
      <p class="sub">lista oficial "Join Your Friends" - cada amigo e a sala onde ele está agora</p>
      <div class="row"><button id="go">Carregar</button></div>
      <div id="out"></div>
    </div>`,

  sala: () => `
    <div class="card">
      <h1>Sala</h1>
      <p class="sub">roomId - render 3D da sala e quem está dentro</p>
      <div class="row">
        <input id="rid" placeholder="ex: 352553534-19" value="352553534-19" />
        <button id="go">Abrir</button>
      </div>
      <div id="out"></div>
    </div>`,

  caca: () => `
    <div class="card">
      <h1>Caça por sala</h1>
      <p class="sub">alvo + dono(s) da sala - varre <span class="mono">dono-1..N</span> e procura o alvo dentro</p>
      <div class="row"><input id="tgt" placeholder="alvo (nick)" value="IR0NI" /></div>
      <div class="row">
        <input id="own" placeholder="dono da sala (ou lista)" />
        <input id="max" value="250" style="max-width:90px" title="range" />
        <button id="go">Cacar</button>
      </div>
      <div id="out"></div>
    </div>`,

  entrada: () => `
    <div class="card">
      <h1>Força entrada</h1>
      <p class="sub">o bot entra na sala como participante real e passa a vigiar o movimento</p>
      <div class="row">
        <input id="rid" placeholder="roomId" value="364530428-44" />
        <button class="warn" id="go">Forçar entrada</button>
        <button class="ghost" id="leave">Sair da sala</button>
      </div>
      <div id="out"></div>
    </div>`,

  movimento: () => `
    <div class="card">
      <h1>Movimento</h1>
      <p class="sub">quem entra e sai, em tempo real, com nome e avatar</p>
      <div class="row">
        <input id="rid" placeholder="roomId" value="222899498-262" />
        <button id="go">Monitorar</button>
        <button class="ghost sm" id="clear">limpar</button>
      </div>
      <div id="out" class="scroll"></div>
    </div>`
};

/* ---------- setup por pagina ---------- */
function setup() {
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('on', a.dataset.p === page));
  app.innerHTML = (RENDER[page] || RENDER.onde)();

  const q = s => document.querySelector('#app ' + s);

  if (page === 'onde') {
    q('#go').onclick = () => runWhere(q('#nick').value);
    q('#nick').onkeydown = e => { if (e.key === 'Enter') runWhere(q('#nick').value); };
    setTimeout(() => runWhere(q('#nick').value), 600);
  }
  if (page === 'vigia') {
    q('#go').onclick = () => {
      q('#out').innerHTML = loading('iniciando vigia…');
      api('/api/vigia', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ nick: q('#nick').value, interval: parseInt(q('#int').value) || 15 }) });
    };
    q('#stop').onclick = () => {
      api('/api/vigia/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ nick: q('#nick').value, interval: 15 }) });
      q('#out').innerHTML = '<div class="empty">vigilia parada</div>';
    };
  }
  if (page === 'amigos') {
    q('#go').onclick = () => { q('#out').innerHTML = loading('lendo listas oficiais…'); api('/api/friends'); };
    setTimeout(() => { q('#out').innerHTML = loading('lendo listas oficiais…'); api('/api/friends'); }, 500);
  }
  if (page === 'sala') {
    q('#go').onclick = () => { q('#out').innerHTML = loading('abrindo sala…'); api('/api/room?rid=' + encodeURIComponent(q('#rid').value)); };
    q('#rid').onkeydown = e => { if (e.key === 'Enter') { q('#out').innerHTML = loading('abrindo sala…'); api('/api/room?rid=' + encodeURIComponent(q('#rid').value)); } };
    setTimeout(() => { q('#out').innerHTML = loading('abrindo sala…'); api('/api/room?rid=' + encodeURIComponent(q('#rid').value)); }, 500);
  }
  if (page === 'caca') {
    q('#go').onclick = () => {
      q('#out').innerHTML = loading('iniciando varredura…');
      api('/api/scan?target=' + encodeURIComponent(q('#tgt').value) + '&owners=' + encodeURIComponent(q('#own').value) + '&maxn=' + (parseInt(q('#max').value) || 250));
    };
  }
  if (page === 'entrada') {
    q('#go').onclick = () => { q('#out').innerHTML = loading('forcando entrada…'); api('/api/enter?rid=' + encodeURIComponent(q('#rid').value)); };
    q('#leave').onclick = () => { q('#out').innerHTML = loading('saindo…'); api('/api/leave?rid=' + encodeURIComponent(q('#rid').value)); };
  }
  if (page === 'movimento') {
    q('#go').onclick = () => { q('#out').innerHTML = ''; mvState.seen = {}; api('/api/room?rid=' + encodeURIComponent(q('#rid').value)); };
    q('#clear').onclick = () => { q('#out').innerHTML = ''; mvState.seen = {}; };
  }
}

/* ---------- acoes ---------- */
function runWhere(nick) {
  const out = document.querySelector('#app #out');
  if (!out) return;
  const t0 = Date.now();
  out.innerHTML = loading('rastreando ' + nick + '… 0s');
  const iv = setInterval(() => { const el = out.querySelector('.muted'); if (el) el.textContent = 'rastreando ' + nick + '… ' + ((Date.now() - t0) / 1000).toFixed(0) + 's'; }, 400);
  setTimeout(() => clearInterval(iv), 30000);
  api('/api/where?nick=' + encodeURIComponent(nick));
}

/* ---------- SSE ---------- */
let CURRENT = {};
const es = new EventSource('/api/stream');
es.onmessage = ev => {
  const e = JSON.parse(ev.data);
  const out = document.querySelector('#app #out');
  if (e.type === 'status') { /* boot */ }
  else if (e.type === 'log') { }
  else if (e.type === 'where') {
    const d = e.data || {}, p = d.profile || {}, room = d.room || null, people = d.people || [];
    if (room && room.render) setBg(room.render);
    if (page === 'onde' && out) {
      const priv = room && room.privacy === 'private';
      const offline = !p.online;
      out.innerHTML =
        '<div class="hero"><div class="pic">' +
        '<img src="' + img(p.render || p.avatar) + '" alt="" />' +
        (p.avatar ? '<img class="photo" src="' + esc(p.avatar) + '" alt="" />' : '') +
        '</div><div>' +
        '<div class="nm">' + esc(p.display_name || p.username) + '</div>' +
        '<div class="un">' + esc(p.username) + ' · <span class="mono">cid ' + esc(p.cid) + '</span></div>' +
        '<div class="badges">' +
        '<span class="tag ' + (p.online ? 'on' : 'off') + '">' + (p.online ? 'ONLINE' : 'OFFLINE') + '</span>' +
        (p.is_vip ? '<span class="tag vip">VIP</span>' : '') + (p.is_ap ? '<span class="tag vip">AP</span>' : '') +
        (room ? '<span class="tag ' + (priv ? 'priv' : 'pub') + '">' + (priv ? 'SALA PRIVADA' : 'SALA PUBLICA') + '</span>' : '') +
        '</div>' +
        (room ? '<div class="roomname">' + esc(room.name) + '</div>' +
          '<div class="mono muted" style="margin:4px 0 2px">[' + esc(room.roomId) + '] · ' + (room.occupancy || '?') + '/' + (room.capacity || '?') + (room.owner ? ' · dono ' + esc(room.owner) : '') + '</div>'
          : '') +
        '</div></div>' +
        (room ? '' :
          '<div class="card" style="margin-top:16px;background:rgba(255,95,126,.07);border-color:rgba(255,95,126,.28)">' +
          '<h2 style="color:#ff8fa3">Status OFFLINE no IMVU</h2>' +
          '<p class="sub" style="margin-bottom:10px">O IMVU marca offline, mas isso <b>não impede</b> ele estar dentro de uma sala — o roster é a verdade. ' +
          'Ele não é amigo do bot, então não aparece nas listas "Join Your Friends". Dá pra caçar de três formas:</p>' +
          '<div class="row">' +
          '<button class="warn" data-act="deep" data-nick="' + esc(p.username) + '">🔍 Busca profunda</button>' +
          '<button data-act="watch" data-nick="' + esc(p.username) + '">🔔 Alerta ao vivo</button>' +
          '<button class="ghost" data-act="lastseen" data-cid="' + esc(p.cid) + '">🕐 Visto por último</button>' +
          '</div>' +
          '<div id="deepBox" class="muted" style="font-size:12.5px">A busca profunda varre as salas de quem está nas salas conhecidas (1 salto). Pode levar minutos.</div>' +
          '</div>') +
        '<div class="kv"><div><b>dentro da sala</b>' + people.length + ' pessoa(s)</div>' +
        '<div><b>origem</b>' + esc((room && room.source) || 'listas do imvu') + '</div>' +
        '<div><b>status imvu</b>' + (p.online ? 'online' : 'offline') + '</div></div>' +
        (room ? '<h2 style="margin-top:18px">Quem está na sala</h2>' + peopleGrid(people, 'ninguem visivel') : '');
    } else if (page === 'vigia' && out) {
      out.innerHTML = '<div class="kv"><div><b>status</b>' + (p.online ? 'online' : 'offline') + '</div>' +
        '<div><b>sala</b>' + (room ? esc(room.name) + ' [' + esc(room.roomId) + ']' : 'sem leitura') + '</div>' +
        '<div><b>pessoas</b>' + people.length + '</div></div>';
      const tl = document.querySelector('#app #tl');
      if (tl) {
        const line = document.createElement('div');
        line.className = 'line';
        line.innerHTML = '<img src="' + esc((people.find(x => x.cid === p.cid) || {}).avatar || p.avatar || '') + '" />' +
          '<div class="i"><div class="t">' + new Date().toLocaleTimeString('pt-BR') + '</div>' +
          '<div class="n">' + esc(p.display_name || p.username) + '</div></div>' +
          '<span class="a in">' + (room ? esc(room.name) : 'offline') + '</span>';
        tl.prepend(line);
        while (tl.children.length > 40) tl.lastChild.remove();
      }
    }
  }
  else if (e.type === 'outfit') {
    const o = e.outfit || {};
    const di = document.getElementById('dressImg');
    if (di) di.src = o.render ? img(o.render) : '';
    const box = document.getElementById('outfitBox');
    if (box && o.products && o.products.length) {
      box.innerHTML = '<h2 style="margin-top:18px">Look atual (' + o.products.length + ' itens)</h2>' +
        '<div class="chips">' + o.products.slice(0, 40).map(p => '<span class="chip">' + esc(p) + '</span>').join('') + '</div>';
    }
  }
  else if (e.type === 'friends') {
    if (page !== 'amigos' || !out) return;
    const fl = e.data || [];
    out.innerHTML = fl.length ?
      '<table><thead><tr><th></th><th>amigo</th><th>status</th><th>sala</th></tr></thead><tbody>' +
      fl.map(f => {
        const r = f.room;
        return '<tr><td><img class="mini" src="' + esc(f.avatar || f.render) + '" /></td>' +
          '<td><b>' + esc(f.display_name || f.username) + '</b><div class="muted mono" style="font-size:10.5px">' + esc(f.username) + '</div></td>' +
          '<td><span class="tag ' + (f.online ? 'on' : 'off') + '">' + (f.online ? 'ONLINE' : 'offline') + '</span></td>' +
          '<td class="' + (r && r.privacy === 'private' ? 'tag priv' : 'muted') + '">' + (r ? esc(r.name || r.roomId) + ' <span class="mono">[' + esc(r.roomId) + ']</span>' : '-') + '</td></tr>';
      }).join('') + '</tbody></table>' : '<div class="empty">bot sem amigos ainda</div>';
  }
  else if (e.type === 'room') {
    const meta = e.meta || {}, people = e.people || [];
    if (meta.render) setBg(meta.render);
    if (page === 'sala' && out) {
      out.innerHTML = roomHeader(meta) +
        (meta.description ? '<div class="muted" style="margin:-6px 0 12px;white-space:pre-wrap;font-size:12px">' + esc(meta.description.slice(0, 180)) + '</div>' : '') +
        '<h2>Dentro da sala (' + people.length + ')</h2>' + peopleGrid(people);
    } else if (page === 'entrada' && out) {
      out.innerHTML = roomHeader(meta) + peopleGrid(people);
    }
  }
  else if (e.type === 'scan_start') { if (page === 'caca' && out) out.innerHTML = loading('iniciando varredura…'); }
  else if (e.type === 'scan_progress') { if (page === 'caca' && out) out.innerHTML = loading(e.msg); }
  else if (e.type === 'scan_hit') {
    if (page === 'caca' && out) {
      if (e.meta && e.meta.render) setBg(e.meta.render);
      out.innerHTML = '<div class="tag on">ACHEI</div>' + roomHeader(e.meta) + peopleGrid(e.people || []);
    }
  }
  else if (e.type === 'scan_done') { if (page === 'caca' && out) out.innerHTML = e.error ? '<div class="empty">' + esc(e.error) + '</div>' : '<div class="empty">varreu ' + e.scanned + ' sala(s), alvo nao achado</div>'; }
  else if (e.type === 'enter') {
    if (page !== 'entrada' || !out) return;
    if (e.ok) {
      if (e.meta && e.meta.render) setBg(e.meta.render);
      out.innerHTML = '<div class="tag on">' + (e.already ? 'BOT JA ESTAVA NA SALA' : 'BOT DENTRO DA SALA') + '</div>' + roomHeader(e.meta) + peopleGrid(e.people || []);
    } else {
      out.innerHTML = '<div class="tag off">ENTRADA NEGADA</div><div class="sub" style="margin-top:10px">' + esc(e.reason) + ' - sala cheia exige o dono (aumentar capacidade ou convidar o bot como mod)</div>';
    }
  }
  else if (e.type === 'leave') { if (page === 'entrada' && out) out.innerHTML = '<div class="empty">bot saiu da sala ' + esc(e.rid) + '</div>'; }
  else if (e.type === 'movement') {
    if (page !== 'movimento' || !out) return;
    const act = e.action === 'created' ? ['in', 'ENTROU'] : (e.action === 'deleted' ? ['out', 'SAIU'] : ['upd', 'ATUALIZOU']);
    const key = e.room + '|' + e.cid;
    let row = out.querySelector('[data-k="' + key + '"]');
    if (!row) {
      row = document.createElement('div');
      row.className = 'line';
      row.dataset.k = key;
      row.innerHTML = '<img src=""/><div class="i"><div class="t">' + new Date().toLocaleTimeString('pt-BR') + ' · ' + esc(e.room) + '</div><div class="n">carregando…</div><div class="c"></div></div><span class="a"></span>';
      out.prepend(row);
      while (out.querySelectorAll('.line').length > 60) out.querySelectorAll('.line')[out.querySelectorAll('.line').length - 1].remove();
    }
    row.querySelector('.a').className = 'a ' + act[0];
    row.querySelector('.a').textContent = act[1];
  }
  else if (e.type === 'person') {
    const p = e.person || {};
    document.querySelectorAll('.line').forEach(r => {
      const c = r.querySelector('.c');
      if (c && r.dataset.k && r.dataset.k.endsWith('|' + e.cid)) {
        r.querySelector('.n').textContent = p.display_name || p.username || e.cid;
        c.textContent = p.username + ' · cid ' + e.cid;
        const im = r.querySelector('img');
        if (im && p.avatar && !im.getAttribute('src')) im.src = esc(p.avatar);
      }
    });
  }
  else if (e.type === 'deep_start') {
    const box = document.getElementById('deepBox');
    if (box) box.innerHTML = loading('iniciando busca profunda de ' + esc(e.target.username) + '…');
  }
  else if (e.type === 'deep_progress') {
    const box = document.getElementById('deepBox');
    if (box) box.innerHTML = '<div class="bar"><i></i></div><div class="muted">' + esc(e.msg) + '</div>';
  }
  else if (e.type === 'deep_hit') {
    const box = document.getElementById('deepBox');
    if (e.meta && e.meta.render) setBg(e.meta.render);
    if (box) box.innerHTML = '<div class="tag on">ACHEI NA BUSCA PROFUNDA</div>' + roomHeader(e.meta) + peopleGrid(e.people || []);
    const out2 = document.querySelector('#app #out');
    if (out2 && page === 'onde') {
      const head = out2.querySelector('.kv');
      if (head) head.insertAdjacentHTML('afterend', '<div class="kv"><div><b>busca profunda</b>achou em ' + esc(e.meta.name) + '</div></div>');
    }
  }
  else if (e.type === 'deep_done') {
    const box = document.getElementById('deepBox');
    if (box) box.innerHTML = e.error ? '<div class="empty">' + esc(e.error) + '</div>'
      : '<div class="empty">busca terminou (' + (e.scanned || 0) + ' varridas) - alvo nao achado nas salas conhecidas</div>';
  }
  else if (e.type === 'alert') {
    const act = e.action === 'created' ? 'ENTROU' : (e.action === 'deleted' ? 'SAIU' : 'mudou de estado');
    const box = document.getElementById('deepBox');
    const html = '<div class="tag on">ALERTA AO VIVO: ' + esc((e.person && (e.person.display_name || e.person.username)) || e.nick) +
      ' ' + act + ' na sala ' + esc(e.room) + '</div>';
    if (box) box.innerHTML = html;
    else {
      const out2 = document.querySelector('#app #out');
      if (out2) out2.insertAdjacentHTML('afterbegin', html);
    }
  }
};

/* ---------- ficha (clique) ---------- */
document.addEventListener('click', ev => {
  const t = ev.target.closest('.ptile');
  if (t) {
    openFicha(t.dataset.cid, t.dataset.user, t.dataset.name, t.dataset.render, t.dataset.photo);
    return;
  }
  const act = ev.target.closest('[data-act]');
  if (act) {
    const a = act.dataset.act, nick = act.dataset.nick, cid = act.dataset.cid;
    const box = document.getElementById('deepBox');
    if (a === 'deep') {
      if (box) box.innerHTML = loading('preparando busca profunda…');
      api('/api/deepscan?nick=' + encodeURIComponent(nick) + '&people=40&rooms=40');
    } else if (a === 'watch') {
      api('/api/watch_target?nick=' + encodeURIComponent(nick)).then(r => {
        if (box) box.innerHTML = '<div class="tag on">ALERTA AO VIVO LIGADO</div><div class="muted" style="margin-top:8px">qualquer entrada/saída dele em qualquer sala monitorada aparece aqui na hora</div>';
      });
    } else if (a === 'lastseen') {
      api('/api/lastseen?cid=' + encodeURIComponent(cid)).then(r => {
        if (box) box.innerHTML = r.seen
          ? '<div class="muted">visto por último: sala <b>' + esc(r.room) + '</b> · ' + esc(r.action) + ' · ' + new Date(r.ts * 1000).toLocaleString('pt-BR') + '</div>'
          : '<div class="muted">sem registro de movimento ainda (nenhuma sala monitorada registrou essa pessoa)</div>';
      });
    }
    return;
  }
  if (ev.target.id === 'modal') $('#modal').classList.remove('on');
});

/* ---------- status ---------- */
async function poll() {
  const s = await api('/api/status');
  const bp = $('#botPill');
  bp.innerHTML = 'bot: <b>' + esc(s.username || '-') + '</b> · <span class="mono">cid ' + esc(s.cid || '-') + '</span>';
  bp.className = 'pill' + (s.ok ? ' live' : ' off');
  const ip = $('#imqPill');
  ip.innerHTML = 'IMQ: ' + (s.imq ? '<b>online</b>' : 'offline');
  ip.className = 'pill' + (s.imq ? ' live' : ' off');
}
poll(); setInterval(poll, 4000);
setInterval(() => { $('#clock').textContent = new Date().toLocaleTimeString('pt-BR'); }, 1000);
setup();
