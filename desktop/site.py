#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
IMVU-TRACK :: site local (FastAPI + SSE)
- usa os motores validados do imvu_core.py
- tempo real: SSE com eventos do IMQ (entra/sai nas salas) e do vigia/caca
- roda em http://127.0.0.1:8765
"""
import asyncio
import base64
import json
import os
import queue
import threading
import time
from typing import Optional

from fastapi import FastAPI, Query
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import imvu_core
from imvu_core import Imvu, ImqWatcher

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")

app = FastAPI(title="IMVU-TRACK")
app.mount("/assets", StaticFiles(directory=WEB), name="assets")
imvu = Imvu()
loop = None
subscribers = set()
jobs = {}
lock = threading.Lock()
name_q = queue.Queue()
inside_cache = {}
last_seen = {}          # cid -> {room, ts}
watch_targets = {}      # cid -> nick (alerta ao vivo)


# ---------------- SSE broker ----------------
async def broadcast(evt):
    dead = []
    for q in list(subscribers):
        try:
            q.put_nowait(evt)
        except Exception:
            dead.append(q)
    for q in dead:
        subscribers.discard(q)


def push(evt):
    """chamado de qualquer thread"""
    if loop is None:
        return
    try:
        asyncio.run_coroutine_threadsafe(broadcast(evt), loop)
    except Exception:
        pass


def resolver_loop():
    """resolve cid -> nome/avatar dos eventos de movimento (com cache no core)"""
    while True:
        try:
            cid = name_q.get()
            if not cid:
                continue
            p = imvu.name_of(cid)
            if p and p.get("username"):
                push({"type": "person", "cid": str(cid), "person": p})
        except Exception:
            time.sleep(0.2)


def inside_loop():
    """mantem o cache de salas onde o bot esta dentro (roda em background, status fica instantaneo)"""
    while True:
        try:
            for rid in list(watcher.rooms)[:10]:
                try:
                    if imvu.is_inside(rid):
                        meta = imvu.room_meta(rid)
                        inside_cache[rid] = {
                            "roomId": rid,
                            "name": meta.get("name"),
                            "privacy": meta.get("privacy"),
                        }
                    else:
                        inside_cache.pop(rid, None)
                except Exception:
                    pass
        except Exception:
            pass
        time.sleep(20)


threading.Thread(target=resolver_loop, daemon=True).start()
threading.Thread(target=inside_loop, daemon=True).start()


def on_imq(evt):
    if evt.get("type") == "movement" and evt.get("cid"):
        cid = str(evt["cid"])
        last_seen[cid] = {"room": evt.get("room"), "action": evt.get("action"), "ts": time.time()}
        name_q.put(cid)
        if cid in watch_targets:
            p = imvu.name_of(cid)
            push({
                "type": "alert",
                "cid": cid,
                "nick": watch_targets[cid],
                "room": evt.get("room"),
                "action": evt.get("action"),
                "person": p,
            })
    push(evt)


# ---------------- IMQ ----------------
watcher = ImqWatcher(imvu, on_imq)


@app.on_event("startup")
async def startup():
    global loop
    loop = asyncio.get_running_loop()

    def boot():
        for _ in range(3):
            if imvu.login():
                push({"type": "status", "ok": True, "cid": imvu.cid, "name": imvu.name})
                break
            time.sleep(3)
        # assina as salas dos amigos + alguns explorados pra ter movimento
        try:
            rooms = list(imvu.room_lists(("friends", "explore")).keys())[:8]
            watcher.rooms.update(rooms)
        except Exception:
            rooms = []
        watcher.start(loop, rooms)
        push({"type": "log", "msg": "IMQ conectado: %d sala(s) monitorada(s)" % len(watcher.rooms)})

    threading.Thread(target=boot, daemon=True).start()


@app.get("/")
async def index():
    return FileResponse(os.path.join(WEB, "index.html"))


ALLOWED = ("api.imvu.com", "webasset-akm.imvu.com", "userimages-akm.imvu.com", "asset-server-akm.imvu.com")


@app.get("/api/img")
async def img(u: str = Query(...)):
    """proxy dos renders do IMVU (exigem sessao) - mesmo truque do catvu"""
    try:
        url = base64.urlsafe_b64decode(u.encode()).decode()
    except Exception:
        return Response(content=b"", media_type="text/plain", status_code=400)
    if not any(h in url for h in ALLOWED):
        return Response(content=b"", media_type="text/plain", status_code=403)
    try:
        r = imvu.s.get(url, timeout=35, headers={"User-Agent": imvu_core.UA})
        return Response(
            content=r.content,
            media_type=r.headers.get("Content-Type", "image/png"),
            headers={"Cache-Control": "public, max-age=900"},
        )
    except Exception as e:
        return Response(content=b"", media_type="text/plain", status_code=502)


@app.get("/api/status")
async def status():
    return {
        "ok": imvu.ok,
        "cid": imvu.cid,
        "name": imvu.name,
        "username": imvu.username,
        "imq": watcher.connected,
        "monitored": len(watcher.rooms),
        "inside": list(inside_cache.values()),
        "error": imvu.last_error,
    }


@app.get("/api/resolve")
async def resolve(nick: str = Query(...)):
    p = imvu.resolve(nick)
    if not p:
        return {"error": "nick nao encontrado"}
    return p


@app.get("/api/where")
async def where(nick: str = Query(...)):
    def job():
        r = imvu.where(nick)
        if r and r.get("profile"):
            try:
                r["outfit"] = imvu.outfit(r["profile"]["cid"])
            except Exception:
                r["outfit"] = {}
        push({"type": "where", "nick": nick, "data": r})
        # se nao achou a sala e o alvo ta offline: procura as salas proprias dele em background
        if r and not r.get("room"):
            def bg():
                try:
                    rid, meta, people = imvu.find_in_own_rooms(r["profile"]["cid"], 250)
                    if rid:
                        meta = imvu.decorate_room(dict(meta))
                        meta["people"] = len(people)
                        r["room"] = meta
                        r["people"] = people
                        push({"type": "where", "nick": nick, "data": r})
                        push({"type": "log", "msg": "achado na sala propria de %s: %s [%s]" % (nick, meta.get("name"), rid)})
                except Exception:
                    pass
            threading.Thread(target=bg, daemon=True).start()

    threading.Thread(target=job, daemon=True).start()
    return {"queued": nick}


@app.get("/api/friends")
async def friends():
    def job():
        fl = imvu.friends()
        # etapa 1: lista de amigos (rapido)
        rmap = imvu.room_map(("friends",))
        for f in fl:
            f["room"] = rmap.get(f["cid"])
        push({"type": "friends", "data": fl, "fast": True})
        # etapa 2: completa com as demais listas (em background, sem travar a UI)
        try:
            rmap2 = imvu.room_map(("explore", "recent"))
            for f in fl:
                if not f.get("room"):
                    f["room"] = rmap2.get(f["cid"])
            for f in fl:
                if f.get("room"):
                    imvu.decorate_room(f["room"])
            push({"type": "friends", "data": fl, "fast": False})
        except Exception:
            pass
    threading.Thread(target=job, daemon=True).start()
    return {"queued": True}


@app.get("/api/lastseen")
async def lastseen(cid: str = Query(...)):
    ls = last_seen.get(str(cid))
    if not ls:
        return {"cid": cid, "seen": False}
    return {"cid": cid, "seen": True, **ls}


@app.get("/api/deepscan")
async def deepscan(nick: str = Query(...), people: int = 40, rooms: int = 40):
    """BUSCA PROFUNDA pra alvo OFFLINE: varre as salas das pessoas que estao nas listas"""

    def job():
        jid = "deep-%d" % int(time.time() * 1000)
        p = imvu.resolve(nick)
        if not p:
            push({"type": "deep_done", "jid": jid, "error": "alvo nao encontrado"})
            return
        watch_targets[str(p["cid"])] = p["username"]
        push({"type": "deep_start", "jid": jid, "target": p})

        def prog(msg):
            push({"type": "deep_progress", "jid": jid, "msg": msg})

        try:
            res = imvu.deep_scan(p["cid"], people_limit=people, rooms_per=rooms, progress=prog)
        except Exception as e:
            push({"type": "deep_done", "jid": jid, "error": str(e)})
            return
        if res:
            rid, meta, people_in = res
            push({"type": "deep_hit", "jid": jid, "meta": meta, "people": people_in, "target": p})
            push({"type": "log", "msg": "BUSCA PROFUNDA achou %s em %s" % (p["username"], rid)})
        else:
            push({"type": "deep_done", "jid": jid, "scanned": people})

    threading.Thread(target=job, daemon=True).start()
    return {"queued": nick}


@app.get("/api/watch_target")
async def watch_target(nick: str = Query(...)):
    p = imvu.resolve(nick)
    if not p:
        return {"error": "alvo nao encontrado"}
    watch_targets[str(p["cid"])] = p["username"]
    push({"type": "log", "msg": "alerta ao vivo ligado para %s (cid %s)" % (p["username"], p["cid"])})
    return {"ok": True, "cid": p["cid"], "username": p["username"]}


@app.get("/api/enter")
async def enter(rid: str = Query(...)):
    """forca entrada do bot na sala (como participante real)"""

    def job():
        res = imvu.enter_room(rid)
        if res.get("ok"):
            watcher.watch(rid)
            try:
                meta = imvu.room_meta(rid)
                inside_cache[rid] = {"roomId": rid, "name": meta.get("name"), "privacy": meta.get("privacy")}
                people = imvu.roster(rid)
            except Exception:
                meta, people = {}, []
            push({"type": "enter", "rid": rid, "ok": True, "already": res.get("status") == "already", "meta": meta, "people": people})
            push({"type": "log", "msg": "bot %s na sala %s" % ("JA ESTAVA" if res.get("status") == "already" else "ENTROU", rid)})
        else:
            reason = "sala cheia (400)" if res.get("status") == 400 else (
                "sem permissao (403)" if res.get("status") == 403 else "falhou (%s)" % res.get("status"))
            push({"type": "enter", "rid": rid, "ok": False, "reason": reason})
            push({"type": "log", "msg": "entrada negada em %s: %s" % (rid, reason)})

    threading.Thread(target=job, daemon=True).start()
    return {"queued": rid}


@app.get("/api/leave")
async def leave(rid: str = Query(...)):
    def job():
        res = imvu.leave_room(rid)
        inside_cache.pop(rid, None)
        push({"type": "leave", "rid": rid, "ok": res.get("ok")})
        push({"type": "log", "msg": "bot saiu da sala %s (ok=%s)" % (rid, res.get("ok"))})

    threading.Thread(target=job, daemon=True).start()
    return {"queued": rid}


@app.get("/api/outfit")
async def outfit(cid: str = Query(...)):
    def job():
        o = imvu.outfit(cid)
        push({"type": "outfit", "cid": cid, "outfit": o})
    threading.Thread(target=job, daemon=True).start()
    return {"queued": cid}


@app.get("/api/room")
async def room(rid: str = Query(...)):
    def job():
        meta = imvu.room_meta(rid)
        people = imvu.roster(rid)
        watcher.watch(rid)
        push({"type": "room", "rid": rid, "meta": meta, "people": people})
    threading.Thread(target=job, daemon=True).start()
    return {"queued": rid}


@app.get("/api/scan")
async def scan(target: str = Query(...), owners: str = Query(""), maxn: int = 250):
    def job():
        jid = "scan-%d" % int(time.time() * 1000)
        push({"type": "scan_start", "jid": jid, "target": target})
        tp = imvu.resolve(target)
        if not tp:
            push({"type": "scan_done", "jid": jid, "error": "alvo nao encontrado"})
            return
        names = [n.strip() for n in owners.split(",") if n.strip()]
        cand = {}
        for nm in [target] + names:
            p = imvu.resolve(nm)
            if not p:
                continue
            push({"type": "scan_progress", "jid": jid, "msg": "varrendo salas de %s (cid %s)..." % (nm, p["cid"])})
            for rid, meta in imvu.enum_rooms(p["cid"], maxn).items():
                if rid not in cand:
                    cand[rid] = meta
        push({"type": "scan_progress", "jid": jid, "msg": "%d sala(s) candidata(s) - conferindo roster" % len(cand)})
        for rid, meta in cand.items():
            people = imvu.roster(rid)
            if any(x["cid"] == tp["cid"] for x in people):
                push(
                    {
                        "type": "scan_hit",
                        "jid": jid,
                        "meta": meta,
                        "people": people,
                        "target": tp,
                    }
                )
                return
        push({"type": "scan_done", "jid": jid, "scanned": len(cand)})
    threading.Thread(target=job, daemon=True).start()
    return {"queued": True}


class VigiaIn(BaseModel):
    nick: str
    interval: int = 15


@app.post("/api/vigia")
async def vigia(body: VigiaIn):
    key = body.nick.lower()
    with lock:
        stop = jobs.get(key)
        if stop:
            stop["stop"] = True
        st = {"stop": False, "nick": body.nick, "interval": max(5, body.interval)}
        jobs[key] = st

    def job():
        last = ""
        while not st["stop"]:
            try:
                r = imvu.where(body.nick)
                if r:
                    room = r.get("room") or {}
                    key2 = "%s|%s" % (r["profile"]["online"], room.get("roomId"))
                    if key2 != last:
                        last = key2
                        push({"type": "vigia", "nick": body.nick, "data": r})
            except Exception as e:
                push({"type": "log", "msg": "vigia erro: %s" % e})
            time.sleep(st["interval"])

    threading.Thread(target=job, daemon=True).start()
    return {"ok": True, "nick": body.nick}


@app.post("/api/vigia/stop")
async def vigia_stop(body: VigiaIn):
    with lock:
        st = jobs.get(body.nick.lower())
        if st:
            st["stop"] = True
    return {"ok": True}


@app.get("/api/stream")
async def stream():
    q = asyncio.Queue()
    subscribers.add(q)

    async def gen():
        try:
            yield "retry: 3000\n\n"
            while True:
                try:
                    evt = await asyncio.wait_for(q.get(), timeout=20)
                    yield "data: %s\n\n" % json.dumps(evt, ensure_ascii=False)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        finally:
            subscribers.discard(q)

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"},
    )



# ---------------- SPA: qualquer rota de pagina devolve o shell ----------------
@app.get("/{spa:path}")
async def spa(spa: str):
    if spa.startswith("api/"):
        return Response(content=b"not found", media_type="text/plain", status_code=404)
    return FileResponse(os.path.join(WEB, "index.html"))

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8765, log_level="warning")