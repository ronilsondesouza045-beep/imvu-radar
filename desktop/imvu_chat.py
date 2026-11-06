#!/usr/bin/env python
# -*- coding: utf-8 -*-
# ============================================================
#  IMVU-CHAT  -  le o chat de salas SEM entrar nelas
#  Canal oficial: IMQ (inv:/chat/chat-<roomId>) - o bot se inscreve
#  no chat da sala e recebe as mensagens em tempo real, sem ser
#  membro e sem entrar na sala.
#
#  Uso:
#    python imvu_chat.py                     -> salas mais cheias das listas
#    python imvu_chat.py 226939065-59        -> uma sala especifica
#    python imvu_chat.py 352553534-19 60     -> sala + 60 segundos
# ============================================================

import asyncio
import base64
import json
import ssl
import sys
import time

import requests
import websockets

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

def _creds():
    import json as _j, os as _o
    try:
        with open(_o.path.join(_o.path.dirname(_o.path.abspath(__file__)), "bot.cfg"), "r", encoding="utf-8") as _f:
            _j2 = _j.load(_f)
        return _j2.get("username", ""), _j2.get("password", "")
    except Exception:
        return "", ""

USER, PASSWORD = _creds()
API = "https://api.imvu.com"
WS = "wss://wss-imq.imvu.com/streaming/imvu_pre"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def b64(s):
    return base64.b64encode(str(s).encode("utf-8")).decode("ascii")


def b64d(s):
    try:
        return base64.b64decode(s).decode("utf-8", "replace")
    except Exception:
        return str(s)


def login():
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept": "application/json"})
    r = s.post(
        API + "/login",
        json={
            "gdpr_cookie_acceptance": True,
            "username": USER,
            "password": PASSWORD,
            "remember_device": True,
        },
        headers={"Origin": "https://www.imvu.com", "Referer": "https://www.imvu.com/"},
        timeout=30,
    )
    d = list(r.json()["denormalized"].values())[0]["data"]
    s.headers["x-imvu-sauce"] = d["sauce"]
    s.headers["x-imvu-application"] = "next_desktop/1"
    me = s.get(API + "/login/me", timeout=20).json()
    mn = list(me["denormalized"].values())[0]
    return s, str(mn["relations"]["quick_chat_profile"]), mn["data"]["session_id"]


def busiest_rooms(s, cid, limit=4):
    cands = []
    for lid in ("friends", "explore", "feature", "recent"):
        try:
            rc = s.get("%s/room_list/room_list-%s-%s/rooms" % (API, cid, lid), timeout=20).json()
        except Exception:
            continue
        for k, v in rc.get("denormalized", {}).items():
            if not k.endswith("/rooms"):
                continue
            for it in v.get("data", {}).get("items", []) or []:
                rid = it.split("/")[-1].replace("room-", "")
                try:
                    rr = s.get("%s/room/room-%s" % (API, rid), timeout=10).json()
                except Exception:
                    continue
                for k2, v2 in rr.get("denormalized", {}).items():
                    d = v2.get("data") or {}
                    if d.get("name"):
                        cands.append((int(d.get("occupancy") or 0), rid, d.get("name"), d.get("privacy")))
    seen, out = set(), []
    for c in sorted(cands, key=lambda x: -x[0]):
        if c[1] in seen:
            continue
        seen.add(c[1])
        out.append(c)
        if len(out) >= limit:
            break
    return out


async def run(rooms, seconds):
    s, cid, sid = login()
    print("[i] logado cid", cid, flush=True)
    for occ, rid, nm, priv in rooms:
        print("[i] alvo: %s [%s] <%s> occ=%s" % (nm, rid, priv, occ), flush=True)
    got = 0
    t0 = time.time()
    async with websockets.connect(
        WS, ssl=ssl._create_unverified_context(), open_timeout=20
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "record": "msg_c2g_connect",
                    "user_id": cid,
                    "cookie": b64(sid),
                    "metadata": [{"record": "metadata", "key": "platform", "value": b64("web")}],
                }
            )
        )

        async def ping():
            while True:
                await asyncio.sleep(15)
                try:
                    await ws.send(json.dumps({"record": "msg_c2g_ping"}))
                except Exception:
                    return

        asyncio.ensure_future(ping())
        while time.time() - t0 < seconds:
            try:
                m = json.loads(await asyncio.wait_for(ws.recv(), timeout=20))
            except asyncio.TimeoutError:
                continue
            rec = m.get("record")
            if rec == "msg_g2c_result":
                if m.get("error"):
                    print("[x] auth:", m.get("error"), flush=True)
                    return
                await ws.send(json.dumps({"record": "msg_c2g_open_floodgates"}))
                for _, rid, _, _ in rooms:
                    q = "inv:/chat/chat-%s" % rid
                    await ws.send(json.dumps({"record": "msg_c2g_subscribe", "queues": [q]}))
                    print("[i] inscrito no chat:", q, flush=True)
            elif rec == "msg_g2c_joined_queue":
                print("  [entrou na fila]", m.get("queue"), flush=True)
            elif rec == "msg_g2c_create_mount":
                print("  [mount]", m.get("queue"), m.get("mount"), "tipo", m.get("type"), flush=True)
            elif rec == "msg_g2c_send_message":
                got += 1
                uid = b64d(m.get("user_id", ""))
                raw = b64d(m.get("message", ""))
                try:
                    obj = json.loads(raw)
                    txt = obj.get("content") or obj.get("message") or raw
                except Exception:
                    txt = raw
                print("  [msg de %s] %s" % (uid, str(txt)[:200]), flush=True)
            elif rec == "msg_g2c_state_change":
                print("  [state]", m.get("queue"), json.dumps(m.get("properties"))[:200], flush=True)
    print("[i] mensagens capturadas:", got, flush=True)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    s, cid, sid = login()
    if args:
        rooms = []
        for rid in args:
            if "-" not in rid:
                continue
            try:
                rr = s.get("%s/room/room-%s" % (API, rid), timeout=10).json()
                nm, priv, occ = "?", "?", 0
                for k, v in rr.get("denormalized", {}).items():
                    d = v.get("data") or {}
                    if d.get("name"):
                        nm, priv, occ = d["name"], d.get("privacy"), int(d.get("occupancy") or 0)
                rooms.append((occ, rid, nm, priv))
            except Exception:
                rooms.append((0, rid, "?", "?"))
        secs = int(args[-1]) if args[-1].isdigit() else 60
    else:
        rooms = busiest_rooms(s, cid)
        secs = 60
    if not rooms:
        print("[x] nenhuma sala alvo")
        sys.exit(1)
    asyncio.run(run(rooms, secs))
