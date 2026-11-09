#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
IMVU-PUBLIC - rastreio de salas publicas (hibrido com o tracker privado).

Modulo aditivo: NAO altera o fluxo privado (imvu_core.Imvu.where / /api/vigia)
nem o sistema de notificacao existente. O host conecta callbacks; o CLI imprime
tudo no console.

Recursos:
  1. task asyncio que varre as salas publicas das listas oficiais do IMVU e
     confere o roster de cada uma atras do alvo. Salas privadas ficam de fora:
     quem cuida delas e o tracker privado que ja existe.
  2. quando o alvo aparece numa publica: GET do roster da sala, download dos
     retratos (avatar_portrait_image) para avatars/<roomId>/ e print dos nicks.
  3. deep links oficiais da sala:
       imvu://room/<nick_dono>/<nome_da_sala_com+>
       https://www.imvu.com/next/chat/room-<roomId>/

CLI:
    python imvu_public.py <nick> [<nick2> ...] [--interval 20] [--avatars DIR] [--once]

Como biblioteca (site.py):
    tracker = PublicTracker(imvu, on_alert=cb, on_log=cb, on_rooms=cb)
    tracker.start(asyncio.get_running_loop())
    tracker.add_target("nick")
"""
import asyncio
import functools
import os
import re
import sys
import threading
import time
import urllib.parse

from imvu_core import Imvu

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_AVATARS_DIR = os.path.join(HERE, "avatars")
PUBLIC_LISTS = ("explore", "feature", "recent", "friends")
PORTRAIT_TIMEOUT = 30


def _safe(part, limit=80):
    s = re.sub(r"[^A-Za-z0-9._-]+", "_", str(part or "")).strip("._-")
    return (s or "sem_nome")[:limit]


def is_public_room(meta):
    """privacidade desconhecida conta como publica; 'private' fica pro tracker privado"""
    return str((meta or {}).get("privacy") or "").strip().lower() != "private"


def deep_links(meta):
    """links oficiais da sala -> {imvu, web, owner, room_name, roomId}"""
    meta = meta or {}
    rid = str(meta.get("roomId") or meta.get("room_id") or "").strip()
    owner = str(meta.get("owner") or meta.get("owner_avatarname") or "").strip()
    name = str(meta.get("name") or "").strip()
    slug = urllib.parse.quote(name, safe="-_.~").replace("%20", "+")
    imvu_link = ""
    if owner and name:
        imvu_link = "imvu://room/%s/%s" % (urllib.parse.quote(owner, safe="-_.~"), slug)
    web_link = "https://www.imvu.com/next/chat/room-%s/" % rid if rid else ""
    return {"imvu": imvu_link, "web": web_link, "owner": owner, "room_name": name, "roomId": rid}


def _portrait_url(person):
    for key in ("portrait", "avatar_portrait_image", "avatar", "render"):
        url = str((person or {}).get(key) or "").strip()
        if not url:
            continue
        if url.startswith("//"):
            url = "https:" + url
        elif url.startswith("/"):
            url = "https://api.imvu.com" + url
        if url.startswith("http"):
            return url
    return ""


def _existing(path_base):
    for ext in (".png", ".jpg", ".jpeg", ".webp", ".gif"):
        p = path_base + ext
        try:
            if os.path.exists(p) and os.path.getsize(p) > 0:
                return p
        except OSError:
            pass
    return ""


def download_avatar(session, person, folder):
    """GET no avatar_portrait_image do ocupante e grava em folder. Retorna o caminho ou ''."""
    url = _portrait_url(person)
    if not url:
        return ""
    base = os.path.join(
        folder,
        "%s_%s" % (_safe(person.get("cid"), 32), _safe(person.get("username"), 48)),
    )
    cached = _existing(base)
    if cached:
        return cached
    try:
        r = session.get(
            url,
            timeout=PORTRAIT_TIMEOUT,
            headers={"User-Agent": session.headers.get("User-Agent", "")},
        )
    except Exception:
        return ""
    if r.status_code != 200 or not r.content:
        return ""
    ctype = (r.headers.get("Content-Type") or "").split(";")[0].strip().lower()
    ext = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "image/gif": ".gif",
    }.get(ctype, ".png")
    path = base + ext
    try:
        os.makedirs(folder, exist_ok=True)
        with open(path, "wb") as f:
            f.write(r.content)
        return path
    except OSError:
        return ""


def _console_log(msg):
    print(msg, flush=True)


async def _to_thread(fn, *args, **kwargs):
    """roda chamada blocking (requests) fora do event loop"""
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, functools.partial(fn, *args, **kwargs))


class PublicTracker:
    """Varre salas publicas numa task asyncio; blocking vai pro executor."""

    def __init__(
        self,
        imvu,
        avatars_dir=None,
        interval=20,
        roster_ttl=8,
        room_cache_ttl=60,
        on_alert=None,
        on_log=None,
        on_rooms=None,
    ):
        self.imvu = imvu
        self.avatars_dir = avatars_dir or DEFAULT_AVATARS_DIR
        self.interval = max(5, int(interval))
        self.roster_ttl = max(0, int(roster_ttl))
        self.room_cache_ttl = max(0, int(room_cache_ttl))
        self.on_alert = on_alert or (lambda evt: None)
        self.on_log = on_log or _console_log
        self.on_rooms = on_rooms
        self.targets = {}                      # cid(str) -> nick
        self._last_hit = {}                    # cid -> roomId (alerta ja disparado)
        self._targets_lock = threading.Lock()
        self._scan_lock = threading.Lock()
        self._room_cache = {"t": 0.0, "ids": []}
        self._watched_rooms = set()
        self._task = None
        self._stop = False

    # ---------------- controle ----------------
    def start(self, loop):
        """agenda a task asyncio (chamar da thread do loop, ex: startup do FastAPI)"""
        if self._task and not self._task.done():
            return self._task
        self._stop = False
        self._task = loop.create_task(self.run_forever())
        return self._task

    def stop(self):
        self._stop = True
        if self._task and not self._task.done():
            self._task.cancel()

    def add_target(self, nick):
        if not self.imvu.ok:
            self.on_log("[public] login ainda nao concluido; alvo %s nao registrado" % nick)
            return None
        p = self.imvu.resolve(str(nick).strip())
        if not p:
            return None
        cid = str(p.get("cid"))
        with self._targets_lock:
            self.targets[cid] = p.get("username") or nick
        self.on_log(
            "[public] rastreando %s (cid %s)"
            % (p.get("display_name") or p.get("username") or nick, cid)
        )
        return p

    def remove_target(self, nick_or_cid):
        key = str(nick_or_cid or "").strip()
        low = key.lower()
        with self._targets_lock:
            for cid, nick in list(self.targets.items()):
                if key == cid or low == str(nick).lower():
                    self.targets.pop(cid, None)
                    self._last_hit.pop(cid, None)
                    self.on_log("[public] parou de rastrear %s (cid %s)" % (nick, cid))
                    return True
        return False

    def snapshot(self):
        with self._targets_lock:
            return [{"cid": cid, "nick": nick} for cid, nick in self.targets.items()]

    # ---------------- loop asyncio ----------------
    async def run_forever(self):
        while not self._stop:
            try:
                if self.imvu.ok and self.targets:
                    await _to_thread(self.scan_once)
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.on_log("[public] erro na varredura: %s" % e)
            try:
                await asyncio.sleep(self.interval)
            except asyncio.CancelledError:
                break

    def scan_target(self, nick, force=True):
        """checagem unica de um nick (usada pelo /api/public/scan)"""
        p = self.add_target(nick)
        if not p:
            return []
        return self.scan_once(force_cid=str(p["cid"]))

    # ---------------- varredura ----------------
    def scan_once(self, force_cid=None):
        """varre as publicas, confere roster, alerta na entrada. Retorna os hits."""
        if not self._scan_lock.acquire(blocking=False):
            return []
        try:
            with self._targets_lock:
                targets = dict(self.targets)
            if not self.imvu.ok or not targets:
                return []
            room_ids = self._public_rooms()
            if not room_ids:
                return []
            found = {}
            for rid in room_ids:
                people = self.imvu.roster(rid, ttl=self.roster_ttl)
                if not people:
                    continue
                by_cid = {str(p.get("cid")): p for p in people}
                for cid in targets:
                    if cid in by_cid:
                        found[cid] = (rid, people)
            hits = []
            for cid, (rid, people) in found.items():
                evt = self._handle_hit(
                    cid, targets.get(cid, cid), rid, people, force=(force_cid == cid)
                )
                if evt:
                    hits.append(evt)
            for cid in list(self._last_hit):
                if cid not in found:
                    rid = self._last_hit.pop(cid, "")
                    self._emit_leave(cid, targets.get(cid, cid), rid)
            return hits
        finally:
            self._scan_lock.release()

    def _public_rooms(self):
        """ids de salas publicas das listas oficiais (cache curto; novas entram no IMQ)"""
        now = time.time()
        cache = self._room_cache
        if cache["ids"] and (now - cache["t"]) < self.room_cache_ttl:
            return list(cache["ids"])
        try:
            rooms = self.imvu.room_lists(PUBLIC_LISTS) or {}
        except Exception:
            rooms = {}
        ids = []
        for rid, lid in rooms.items():
            # explore/feature sao publicas por definicao; recent/friends podem ser privadas
            if lid in ("explore", "feature"):
                ids.append(rid)
                continue
            meta = self.imvu.room_meta(rid)
            if meta and is_public_room(meta):
                ids.append(rid)
        cache["t"], cache["ids"] = now, ids
        new = [rid for rid in ids if rid not in self._watched_rooms]
        if new:
            self._watched_rooms.update(new)
            if self.on_rooms:
                try:
                    self.on_rooms(new)
                except Exception:
                    pass
        return ids

    def _handle_hit(self, cid, nick, rid, people, force=False):
        meta = self.imvu.room_meta(rid) or {"roomId": rid}
        meta.setdefault("roomId", rid)
        if not is_public_room(meta):
            return None  # privada: assunto do tracker privado
        if not meta.get("owner"):
            head = str(rid).split("-")[0]
            owner_cid = str(meta.get("owner_cid") or (head if head.isdigit() else ""))
            if owner_cid:
                meta["owner"] = self.imvu.name_of(owner_cid).get("username") or ""
        links = deep_links(meta)
        new_hit = self._last_hit.get(cid) != rid
        self._last_hit[cid] = rid
        avatars = self._save_avatars(rid, people)  # garante retratos de quem entrou depois
        if not (new_hit or force):
            return None
        evt = {
            "type": "public_hit",
            "target": {"cid": cid, "nick": nick},
            "room": meta,
            "people": people,
            "avatars": avatars,
            "links": links,
            "ts": time.time(),
        }
        self._print_alert(evt)
        try:
            self.on_alert(evt)
        except Exception:
            pass
        return evt

    def _save_avatars(self, rid, people):
        folder = os.path.join(self.avatars_dir, _safe(rid, 64))
        out = []
        for p in people:
            path = download_avatar(self.imvu.s, p, folder)
            out.append({"person": p, "file": path})
        return out

    def _print_alert(self, evt):
        t = evt["target"]
        room = evt["room"]
        links = evt["links"]
        people = evt["people"]
        lines = [
            "=" * 64,
            "[PUBLIC] ALVO EM SALA PUBLICA: %s (cid %s)" % (t.get("nick"), t.get("cid")),
            "  sala : %s  (id %s)"
            % (room.get("name") or "?", room.get("roomId") or "?"),
            "  dono : %s | ocupacao: %s/%s | privacidade: %s"
            % (
                room.get("owner") or "?",
                room.get("occupancy") or "?",
                room.get("capacity") or "?",
                room.get("privacy") or "?",
            ),
        ]
        if links.get("imvu"):
            lines.append("  IMVU : %s" % links["imvu"])
        lines.append("  WEB  : %s" % (links.get("web") or "-"))
        lines.append("  ocupantes (%d):" % len(people))
        for item in evt.get("avatars") or []:
            p = item.get("person") or {}
            extra = ("  -> " + item["file"]) if item.get("file") else "  (avatar sem url/falhou)"
            lines.append(
                "    - %s [%s] cid=%s%s"
                % (p.get("display_name") or "?", p.get("username") or "?", p.get("cid") or "?", extra)
            )
        lines.append("-" * 64)
        print("\n".join(lines), flush=True)

    def _emit_leave(self, cid, nick, rid):
        self.on_log("[public] %s saiu da sala publica %s" % (nick, rid))
        try:
            self.on_alert(
                {
                    "type": "public_leave",
                    "target": {"cid": cid, "nick": nick},
                    "room": {"roomId": rid},
                    "ts": time.time(),
                }
            )
        except Exception:
            pass


# ---------------- CLI ----------------
def _parse_args(argv):
    opts = {"interval": 20, "avatars": None, "once": False}
    nicks = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--interval" and i + 1 < len(argv):
            opts["interval"] = int(argv[i + 1])
            i += 2
            continue
        if a == "--avatars" and i + 1 < len(argv):
            opts["avatars"] = argv[i + 1]
            i += 2
            continue
        if a == "--once":
            opts["once"] = True
            i += 1
            continue
        if not a.startswith("--"):
            nicks.append(a)
        i += 1
    return nicks, opts


async def _cli_run(tracker, once):
    if once:
        hits = await _to_thread(tracker.scan_once)
        print("[i] %d hit(s) nesta varredura" % len(hits), flush=True)
        return
    await tracker.run_forever()


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    nicks, opts = _parse_args(argv)
    if not nicks:
        print(__doc__)
        return 1
    imvu = Imvu()
    print("[i] logando como %s..." % (imvu.username or "?"), flush=True)
    if not imvu.login():
        print("[x] login falhou: %s" % (imvu.last_error or "sem detalhe"), flush=True)
        return 2
    print("[i] logado: cid %s (%s)" % (imvu.cid, imvu.name), flush=True)
    tracker = PublicTracker(
        imvu, avatars_dir=opts["avatars"], interval=opts["interval"]
    )
    for nick in nicks:
        if not tracker.add_target(nick):
            print("[x] nick nao resolveu: %s" % nick, flush=True)
    if not tracker.targets:
        print("[x] nenhum alvo valido", flush=True)
        return 3
    print(
        "[i] vigiando salas publicas a cada %ss (privadas: tracker privado existente)"
        % tracker.interval,
        flush=True,
    )
    try:
        asyncio.run(_cli_run(tracker, opts["once"]))
    except KeyboardInterrupt:
        print("\n[i] encerrado", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
