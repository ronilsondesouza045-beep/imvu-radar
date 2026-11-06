#!/usr/bin/env python
# -*- coding: utf-8 -*-
# ============================================================
#  IMVU-LIVE  -  tempo real via IMQ (websocket do IMVU)
#  Igual BotPower: presenca/estado de amigos chega AO VIVO,
#  incluindo a sala atual (publica ou privada).
#
#  Uso:
#    pip install websockets requests
#    python imvu_live.py <nick>          -> vigiar um nick
#    python imvu_live.py --amigos        -> vigiar todos os amigos
#    python imvu_live.py <nick> --raw    -> dump cru das mensagens
# ============================================================

import asyncio
import base64
import json
import ssl
import sys
import urllib.parse

import requests

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
if sys.stderr and hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

try:
    import websockets
except ImportError:
    print("rode: pip install websockets requests")
    sys.exit(1)

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
WS_URLS = [
    "wss://wss-imq.imvu.com/streaming/imvu_pre",
    "wss://imq.imvu.com:444/streaming/imvu_pre",
]
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def b64enc(s):
    return base64.b64encode(str(s).encode("utf-8")).decode("ascii")


def b64dec(s):
    try:
        return base64.b64decode(s).decode("utf-8")
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
    if r.status_code not in (200, 201):
        print("[x] login falhou:", r.status_code, r.text[:300])
        sys.exit(1)
    data = list(r.json()["denormalized"].values())[0]["data"]
    s.headers["x-imvu-sauce"] = data["sauce"]
    s.headers["x-imvu-application"] = "next_desktop/1"
    me = s.get(API + "/login/me", timeout=20).json()
    mnode = list(me["denormalized"].values())[0]
    cid = mnode["relations"]["quick_chat_profile"]
    session_id = mnode["data"]["session_id"]
    return s, str(cid), session_id


def resolve_nick(s, nick):
    r = s.get(API + "/user", params={"username": nick}, timeout=20).json()
    for k, v in r["denormalized"].items():
        if "/user/user-" in k:
            cid = k.split("/")[-1].split("-")[-1]
            d = v.get("data", {})
            return cid, d.get("display_name", nick), d.get("username", nick)
    return None, None, None


def friends_cids(s, my_cid):
    r = s.get(API + "/user/user-%s/friends?limit=200" % my_cid, timeout=20).json()
    out = []
    for k, v in r["denormalized"].items():
        if k.startswith(API + "/user/user-") and v.get("relations", {}).get("viewer_friend"):
            cid = k.split("/")[-1].split("-")[-1]
            d = v.get("data", {})
            out.append((cid, d.get("display_name", cid), d.get("username", cid)))
    return out


def room_roster(s, room_id):
    """roster autenticado de uma sala (via /chat/chat-<roomId>)"""
    try:
        r = s.get(API + "/chat/chat-%s" % room_id, timeout=20).json()
    except Exception as e:
        return None, None, []
    items = []
    for k, v in r.get("denormalized", {}).items():
        if k.endswith("/participants"):
            items = v.get("data", {}).get("items", []) or []
    people = []
    for it in items:
        ref = it
        node = r.get("denormalized", {}).get(it) or {}
        if node.get("relations", {}).get("ref"):
            ref = node["relations"]["ref"]
        un = r.get("denormalized", {}).get(ref) or {}
        d = un.get("data", {})
        people.append((ref.split("-")[-1], d.get("username", "?"), d.get("display_name", "?")))
    meta = {}
    for k, v in r.get("denormalized", {}).items():
        if "/chat/chat-" in k and v.get("data"):
            meta = v["data"]
    return meta, room_id, people


def room_meta(s, room_id):
    try:
        r = s.get(API + "/room/room-%s" % room_id, timeout=20).json()
        for k, v in r.get("denormalized", {}).items():
            if v.get("data"):
                return v["data"]
    except Exception:
        pass
    return {}


async def run():
    raw = "--raw" in sys.argv
    inbox = "--inbox" in sys.argv
    targets = []
    only_friends = "--amigos" in sys.argv
    s, my_cid, session_id = login()
    print("[i] logado: cid", my_cid, "session", session_id[:8] + "...")

    if inbox:
        print("[i] modo INBOX: esperando convites/mensagens do IMVU (convide o bot pra sala)")
        targets = []
    elif only_friends:
        targets = friends_cids(s, my_cid)
        print("[i] vigiando %d amigo(s)" % len(targets))
        for cid, name, uname in targets:
            print("    - %s [%s] cid=%s" % (name, uname, cid))
    else:
        nick = sys.argv[1]
        tcid, dname, uname = resolve_nick(s, nick)
        if not tcid:
            print("[x] nick nao resolveu:", nick)
            sys.exit(1)
        targets = [(tcid, dname, uname)]
        print("[i] alvo: %s [%s] cid=%s" % (dname, uname, tcid))

    name_by_cid = {t[0]: (t[1], t[2]) for t in targets}

    last_ws = WS_URLS[0]
    try:
        async with websockets.connect(
            last_ws,
            ssl=ssl._create_unverified_context(),
            ping_interval=None,
            open_timeout=20,
        ) as ws:
            await ws.send(
                json.dumps(
                    {
                        "record": "msg_c2g_connect",
                        "user_id": my_cid,
                        "cookie": b64enc(session_id),
                        "metadata": [
                            {"record": "metadata", "key": "platform", "value": b64enc("web")},
                        ],
                    }
                )
            )
            print("[i] conectado em", last_ws, "- autenticando...")

            async def ping_loop():
                while True:
                    await asyncio.sleep(15)
                    try:
                        await ws.send(json.dumps({"record": "msg_c2g_ping"}))
                    except Exception:
                        return

            ping_task = asyncio.ensure_future(ping_loop())
            subscribed = False
            auth_ok = False
            while True:
                try:
                    raw_msg = await asyncio.wait_for(ws.recv(), timeout=45)
                except asyncio.TimeoutError:
                    continue
                except Exception as e:
                    print("[x] conexao caiu:", e)
                    break
                try:
                    msg = json.loads(raw_msg)
                except Exception:
                    if raw:
                        print("[raw]", raw_msg[:300])
                    continue
                rec = msg.get("record")
                if raw and rec not in ("msg_g2c_pong",):
                    print("[raw]", json.dumps(msg)[:400])

                if rec == "msg_g2c_result":
                    if msg.get("error"):
                        print("[x] auth IMQ falhou:", msg.get("error"))
                        break
                    auth_ok = True
                    print("[i] autenticado no IMQ")
                    await ws.send(json.dumps({"record": "msg_c2g_open_floodgates"}))
                    if inbox:
                        for q in ["/user/%s" % my_cid, "inv:/profile/%s" % my_cid]:
                            await ws.send(json.dumps({"record": "msg_c2g_subscribe", "queues": [q]}))
                            print("[i] inbox inscrita em", q)
                    for tcid, _, _ in targets:
                        q = "inv:/presence/presence-%s" % tcid
                        await ws.send(json.dumps({"record": "msg_c2g_subscribe", "queues": [q]}))
                        print("[i] inscrito em", q)
                    subscribed = True

                elif rec == "msg_g2c_state_change":
                    q = msg.get("queue", "")
                    props = {}
                    for p in msg.get("properties", []):
                        try:
                            props[p["key"]] = b64dec(p["value"])
                        except Exception:
                            props[p["key"]] = p.get("value")
                    cid = q.split("presence-")[-1]
                    nm, un = name_by_cid.get(cid, (cid, cid))
                    if "presence" in q:
                        print("  [presence] %s [%s]:" % (nm, un))
                        for k, v in props.items():
                            print("      %s = %s" % (k, v))
                    else:
                        print("  [state %s] %s" % (q, json.dumps(props, ensure_ascii=False)[:300]))

                elif rec == "msg_g2c_send_message":
                    try:
                        uid = b64dec(msg.get("user_id", ""))
                        m = json.loads(b64dec(msg.get("message", "")))
                    except Exception:
                        uid = msg.get("user_id")
                        m = msg.get("message")
                    txt = json.dumps(m, ensure_ascii=False)
                    print("  [msg %s] %s" % (uid, txt[:400]))
                    # detecta convite de sala e ja puxa o roster
                    import re
                    for hit in set(re.findall(r"room-(\d+-\d+)", txt)):
                        meta = room_meta(s, hit)
                        print("  >>> SALA DETECTADA: %s | %s | %s" % (
                            hit, meta.get("name", "?"), meta.get("privacy", "?")))
                        _, _, people = room_roster(s, hit)
                        if people:
                            print("      dentro (%d):" % len(people))
                            for cid, un, dn in people:
                                print("        - %s  [%s / %s]" % (dn, un, cid))
                        else:
                            print("      roster vazio (bot ainda nao entrou na sala)")

                elif rec == "msg_g2c_joined_queue":
                    print("  [i] joined queue:", msg.get("queue"))

                elif rec == "msg_g2c_pong":
                    pass

                elif not auth_ok and rec != "msg_g2c_result":
                    print("  [i] msg durante auth:", rec)

    except Exception as e:
        print("[x] falha de conexao (%s):" % last_ws, e)
        print("    (se o IMQ caiu, rode de novo)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    asyncio.run(run())
