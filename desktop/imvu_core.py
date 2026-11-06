#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
IMVU CORE - motores testados do IMVU Next (api.imvu.com), usados pelo site e pelo bot.
Tudo aqui foi validado ao vivo:
  - login (POST /login) -> sauce + cid + session_id
  - resolve nick -> cid
  - listas oficiais do IMVU (Join Your Friends / explore / feature / recent)
  - roster autenticado de sala (GET /chat/chat-<roomId>/participants)
  - metadados de sala (GET /room/room-<roomId>)
  - enumeracao de salas por dono (GET /room/room-<cid>-<n>)
  - presenca (GET /presence/presence-<cid>)
"""
import base64
import json
import os
import ssl
import threading
import time

import requests

API = "https://api.imvu.com"
WS_URL = "wss://wss-imq.imvu.com/streaming/imvu_pre"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

HERE = os.path.dirname(os.path.abspath(__file__))


def b64(s):
    return base64.b64encode(str(s).encode("utf-8")).decode("ascii")


def b64d(s):
    try:
        return base64.b64decode(s).decode("utf-8", "replace")
    except Exception:
        return str(s)


def load_cfg(path=None):
    path = path or os.path.join(HERE, "bot.cfg")
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"username": "", "password": ""}


class Imvu:
    def __init__(self, username=None, password=None):
        cfg = load_cfg()
        self.username = username or cfg.get("username")
        self.password = password or cfg.get("password")
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": UA, "Accept": "application/json"})
        self.cid = None
        self.sauce = None
        self.session_id = None
        self.name = None
        self.ok = False
        self.last_error = None
        self._names = {}
        self._map_cache = {"key": None, "t": 0, "data": {}}
        self._roster_cache = {}

    # ---------------- auth ----------------
    def login(self):
        try:
            r = self.s.post(
                API + "/login",
                json={
                    "gdpr_cookie_acceptance": True,
                    "username": self.username,
                    "password": self.password,
                    "remember_device": True,
                },
                headers={"Origin": "https://www.imvu.com", "Referer": "https://www.imvu.com/"},
                timeout=30,
            )
            if r.status_code not in (200, 201):
                self.last_error = "login HTTP %s" % r.status_code
                return False
            env = r.json()
            data = list(env["denormalized"].values())[0]["data"]
            self.sauce = data["sauce"]
            self.s.headers["x-imvu-sauce"] = self.sauce
            self.s.headers["x-imvu-application"] = "next_desktop/1"
            me = self.s.get(API + "/login/me", timeout=20).json()
            mn = list(me["denormalized"].values())[0]
            self.cid = str(mn["relations"]["quick_chat_profile"])
            self.session_id = mn["data"]["session_id"]
            u = self.s.get(API + "/user/user-" + self.cid, timeout=20).json()
            for k, v in u.get("denormalized", {}).items():
                d = v.get("data") or {}
                if k.endswith("user-" + self.cid) and d.get("display_name"):
                    self.name = d["display_name"]
            self.ok = True
            self.last_error = None
            return True
        except Exception as e:
            self.last_error = str(e)
            self.ok = False
            return False

    # ---------------- REST ----------------
    def _get(self, path):
        try:
            return self.s.get(API + path, timeout=25).json()
        except Exception:
            return None

    def resolve(self, nick):
        """nick -> {cid, username, display_name, online, avatar}"""
        j = self._get("/user?username=" + requests.utils.quote(str(nick)))
        if not j:
            return None
        for k, v in j.get("denormalized", {}).items():
            if "/user/user-" in k:
                d = v.get("data") or {}
                return {
                    "cid": k.split("/")[-1].split("-")[-1],
                    "username": d.get("username", nick),
                    "display_name": d.get("display_name", nick),
                    "online": bool(d.get("online")),
                    "avatar": d.get("thumbnail_url") or d.get("avatar_image") or "",
                    "render": d.get("avatar_image") or "",
                    "portrait": d.get("avatar_portrait_image") or "",
                    "is_vip": bool(d.get("is_vip")),
                    "is_ap": bool(d.get("is_ap")),
                }
        return None

    def outfit(self, cid):
        """look atual do avatar -> render de corpo inteiro (image_dressup)"""
        j = self._get("/profile_outfit/profile_outfit-%s" % cid)
        out = {"products": [], "render": "", "look": ""}
        if not j:
            return out
        for k, v in j.get("denormalized", {}).items():
            d = v.get("data") or {}
            if d.get("look_url"):
                out["look"] = d["look_url"]
                ids = d["look_url"].split("/look/")[-1]
                out["render"] = API + "/image_dressup/" + ids
                out["products"] = [p.get("product_id") for p in (d.get("products") or [])]
        return out

    def presence(self, cid):
        j = self._get("/presence/presence-%s" % cid)
        if not j:
            return None
        for v in j.get("denormalized", {}).values():
            return bool((v.get("data") or {}).get("online"))
        return None

    def room_meta(self, rid):
        j = self._get("/room/room-%s" % rid)
        out = {}
        if not j:
            return out
        for v in j.get("denormalized", {}).values():
            d = v.get("data") or {}
            if d.get("name"):
                out = {
                    "roomId": rid,
                    "name": d.get("name"),
                    "privacy": d.get("privacy"),
                    "occupancy": d.get("occupancy"),
                    "capacity": d.get("capacity"),
                    "owner": d.get("owner_avatarname"),
                    "owner_cid": d.get("customers_id"),
                    "join_url": d.get("join_room_url"),
                    "vip_only": d.get("vip_only"),
                    "render": ("https:" + d["rendered_image"]) if str(d.get("rendered_image", "")).startswith("//") else d.get("rendered_image", ""),
                    "cover": ("https:" + d["image_url"]) if str(d.get("image_url", "")).startswith("//") else d.get("image_url", ""),
                    "description": (d.get("description") or "")[:220],
                }
                break
        return out

    def roster(self, rid, ttl=12):
        """participantes da sala (autenticado) - com cache curto"""
        c = self._roster_cache.get(rid)
        if c and (time.time() - c[0]) < ttl:
            return c[1]
        people = []
        j = self._get("/chat/chat-%s/participants" % rid)
        if j:
            for k, v in j.get("denormalized", {}).items():
                if k.endswith("/participants"):
                    for it in v.get("data", {}).get("items", []) or []:
                        node = j.get("denormalized", {}).get(it) or {}
                        ref = (node.get("relations") or {}).get("ref")
                        un = j.get("denormalized", {}).get(ref) or {}
                        d = un.get("data") or {}
                        people.append(
                            {
                                "cid": (ref or "").split("-")[-1],
                                "username": d.get("username", "?"),
                                "display_name": d.get("display_name", "?"),
                                "avatar": d.get("thumbnail_url") or d.get("avatar_image") or "",
                                "render": d.get("avatar_image") or "",
                                "portrait": d.get("avatar_portrait_image") or "",
                                "is_vip": bool(d.get("is_vip")),
                            }
                        )
        self._roster_cache[rid] = (time.time(), people)
        return people

    def room_lists(self, lids=("friends", "explore", "feature", "recent")):
        """listas oficiais do IMVU -> {roomId: listId}"""
        out = {}
        for lid in lids:
            j = self._get("/room_list/room_list-%s-%s/rooms" % (self.cid, lid))
            if not j:
                continue
            for k, v in j.get("denormalized", {}).items():
                if k.endswith("/rooms"):
                    for it in v.get("data", {}).get("items", []) or []:
                        rid = it.split("/")[-1].replace("room-", "")
                        if rid and rid not in out:
                            out[rid] = lid
        return out

    def room_map(self, lids=("friends", "explore", "feature", "recent"), ttl=25):
        """{cid: {sala}} - quem esta em qual sala, varrendo as listas oficiais (com cache)"""
        key = tuple(lids)
        c = self._map_cache
        if c.get("key") == key and (time.time() - c.get("t", 0)) < ttl:
            return c["data"]
        rooms = self.room_lists(lids)
        m = {}
        for rid in rooms:
            people = self.roster(rid)
            if not people:
                continue
            for p in people:
                m.setdefault(
                    p["cid"],
                    {"roomId": rid, "people": len(people), "source": rooms[rid], "name": "", "privacy": ""},
                )
        self._map_cache = {"key": key, "t": time.time(), "data": m}
        return m

    def decorate_room(self, info):
        """preenche nome/privacidade/ocupacao de uma sala (1 request)"""
        if not info or not info.get("roomId"):
            return info
        meta = self.room_meta(info["roomId"])
        if meta:
            info.update(meta)
        return info

    def enter_room(self, rid):
        """FORCA ENTRADA: o bot entra como participante real da sala.
        POST /chat/chat-<rid>/participants  body {"id": user url}
        201 = entrou | 400 = sala cheia | 403 = sem permissao (ou ja esta dentro)"""
        try:
            if self.is_inside(rid):
                return {"ok": True, "status": "already", "room": rid, "inside": True}
        except Exception:
            pass
        body = {"id": API + "/user/user-" + str(self.cid)}
        try:
            r = self.s.post(
                API + "/chat/chat-%s/participants" % rid, json=body, timeout=20
            )
            return {
                "ok": r.status_code in (200, 201),
                "status": r.status_code,
                "room": rid,
                "inside": r.status_code in (200, 201),
            }
        except Exception as e:
            return {"ok": False, "status": 0, "room": rid, "error": str(e)}

    def leave_room(self, rid):
        try:
            r = self.s.delete(
                API + "/chat/chat-%s/participants/user-%s" % (rid, self.cid), timeout=20
            )
            return {"ok": r.status_code in (200, 204), "status": r.status_code, "room": rid}
        except Exception as e:
            return {"ok": False, "status": 0, "room": rid, "error": str(e)}

    def is_inside(self, rid):
        people = self.roster(rid)
        return any(p["cid"] == str(self.cid) for p in people)

    def name_of(self, cid):
        """cid -> {cid, username, display_name, avatar} (com cache)"""
        cid = str(cid)
        if cid in self._names:
            return self._names[cid]
        info = {"cid": cid, "username": cid, "display_name": cid, "avatar": ""}
        j = self._get("/user/user-%s" % cid)
        if j:
            for k, v in j.get("denormalized", {}).items():
                d = v.get("data") or {}
                if k.endswith("user-" + cid) and d.get("username"):
                    info = {
                        "cid": cid,
                        "username": d.get("username"),
                        "display_name": d.get("display_name") or d.get("username"),
                        "avatar": d.get("thumbnail_url") or d.get("avatar_image") or "",
                    }
                    break
        self._names[cid] = info
        return info

    def people_in_lists(self, lids=("friends", "explore", "feature", "recent"), limit=400):
        """pessoas que aparecem nas listas oficiais do IMVU (candidatas a co-presenca)"""
        out = {}
        rooms = self.room_lists(lids)
        for rid in rooms:
            for p in self.roster(rid):
                out.setdefault(p["cid"], {"cid": p["cid"], "username": p["username"], "display_name": p["display_name"], "room": rid})
            if len(out) >= limit:
                break
        return list(out.values())

    def deep_scan(self, target_cid, people_limit=40, rooms_per=40, progress=None):
        """BUSCA PROFUNDA: 1 salto - das pessoas que estao nas salas conhecidas,
        varre as salas DELAS (1..N) e procura o alvo dentro.
        Serve pra alvo OFFLINE (nao e amigo do bot, entao nao aparece nas listas)."""
        target_cid = str(target_cid)
        cands = self.people_in_lists(limit=people_limit)
        if progress:
            progress("pessoas candidatas: %d" % len(cands))
        found = None
        done = 0
        for person in cands:
            if person["cid"] == target_cid:
                continue
            try:
                rooms = self.enum_rooms(person["cid"], rooms_per)
            except Exception:
                continue
            for rid in rooms:
                people = self.roster(rid)
                if any(x["cid"] == target_cid for x in people):
                    found = (rid, self.room_meta(rid), people)
                    if progress:
                        progress("ACHEI em %s (%s)" % (rid, rooms[rid].get("name")))
                    return found
            done += 1
            if progress and done % 5 == 0:
                progress("varridas %d/%d pessoas (%d salas checadas)" % (done, len(cands), done * rooms_per))
        if progress:
            progress("terminou: %d pessoas varridas, alvo nao achado" % done)
        return found

    def friends(self):
        j = self._get("/user/user-%s/friends?limit=200" % self.cid)
        out = []
        if not j:
            return out
        items = []
        for k, v in j.get("denormalized", {}).items():
            if k.endswith("/friends") or "/friends?" in k:
                items = v.get("data", {}).get("items", []) or []
                break
        for it in items:
            node = j.get("denormalized", {}).get(it) or {}
            ref = (node.get("relations") or {}).get("ref")
            un = j.get("denormalized", {}).get(ref) or {}
            d = un.get("data") or {}
            out.append(
                {
                    "cid": (ref or "").split("-")[-1],
                    "username": d.get("username", "?"),
                    "display_name": d.get("display_name", "?"),
                    "online": bool(d.get("online")),
                    "avatar": d.get("thumbnail_url") or d.get("avatar_image") or "",
                    "render": d.get("avatar_image") or "",
                }
            )
        return out

    def enum_rooms(self, cid, maxn=250):
        """varre <cid>-1..<cid>-maxn -> {roomId: meta}"""
        out = {}
        for n in range(1, maxn + 1):
            rid = "%s-%s" % (cid, n)
            try:
                j = self.s.get(API + "/room/room-%s" % rid, timeout=8).json()
            except Exception:
                continue
            for v in j.get("denormalized", {}).values():
                d = v.get("data") or {}
                if d.get("name"):
                    out[rid] = {
                        "roomId": rid,
                        "name": d.get("name"),
                        "privacy": d.get("privacy"),
                        "occupancy": d.get("occupancy"),
                        "capacity": d.get("capacity"),
                        "owner": d.get("owner_avatarname"),
                    }
        return out

    def find_in_rooms(self, target_cid, room_ids):
        """procura o cid alvo dentro dos rosters das salas -> (roomId, meta, people)"""
        for rid in room_ids:
            people = self.roster(rid)
            if any(p["cid"] == str(target_cid) for p in people):
                return rid, self.room_meta(rid), people
        return None, None, []

    def find_in_own_rooms(self, cid, maxn=250):
        """procura o cid dentro das salas PROPRIAS dele (ele pode estar numa privada sua)"""
        own = self.enum_rooms(cid, maxn)
        for rid, meta in own.items():
            people = self.roster(rid)
            if any(x["cid"] == str(cid) for x in people):
                return rid, meta, people
        return None, None, []

    def catvu_rooms(self, nick, timeout=12):
        """salas que o indice do catvu ja conhece pro alvo (fonte extra p/ offline)"""
        out = {}
        try:
            r = requests.get(
                "https://catvu.live/api/private-room/find",
                params={"username": nick},
                timeout=timeout,
                headers={"User-Agent": UA},
            )
            j = r.json()
            for rm in j.get("rooms") or []:
                if rm.get("roomId"):
                    out[rm["roomId"]] = rm.get("roomName") or rm["roomId"]
        except Exception:
            pass
        return out

    def where(self, nick, include_own=False):
        """onde o nick esta agora: presenca + sala (listas oficiais).
        NAO depende do flag online: o roster e a verdade.
        include_own=True varre as salas proprias (lento, usar em background)."""
        p = self.resolve(nick)
        if not p:
            return None
        # 1) lista de amigos (rapida: poucas salas)
        room = self.room_map(("friends",)).get(p["cid"])
        # 2) se nao achou, varre as outras listas
        if not room:
            room = self.room_map(("explore", "recent", "feature")).get(p["cid"])
        # 3) indice do catvu (salas ja catalogadas do alvo)
        if not room:
            for rid in self.catvu_rooms(nick).keys():
                people = self.roster(rid)
                if any(x["cid"] == p["cid"] for x in people):
                    room = {"roomId": rid, "name": rid, "privacy": "?", "source": "catvu"}
                    break
        # 4) opcional: salas proprias do alvo
        if not room and include_own:
            rid, meta, people = self.find_in_own_rooms(p["cid"], 250)
            if rid:
                room = dict(meta)
                room["people"] = len(people)
        if room:
            self.decorate_room(room)
        people = self.roster(room["roomId"]) if room and room.get("roomId") else []
        return {"profile": p, "room": room, "people": people}


# ---------------- IMQ (websocket) ----------------
class ImqWatcher:
    """assina filas de chat e emite eventos de entrada/saida (sem entrar na sala)"""

    def __init__(self, imvu, on_event):
        self.imvu = imvu
        self.on_event = on_event
        self.rooms = set()
        self.task = None
        self.connected = False

    async def _run(self, ws_url):
        import asyncio

        import websockets

        cid = self.imvu.cid
        sid = self.imvu.session_id
        while True:
            try:
                async with websockets.connect(
                    ws_url, ssl=ssl._create_unverified_context(), open_timeout=20
                ) as ws:
                    await ws.send(
                        json.dumps(
                            {
                                "record": "msg_c2g_connect",
                                "user_id": cid,
                                "cookie": b64(sid),
                                "metadata": [
                                    {"record": "metadata", "key": "platform", "value": b64("web")}
                                ],
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
                    self.connected = True
                    while True:
                        try:
                            raw = await asyncio.wait_for(ws.recv(), timeout=45)
                        except asyncio.TimeoutError:
                            continue
                        except Exception:
                            break
                        try:
                            m = json.loads(raw)
                        except Exception:
                            continue
                        rec = m.get("record")
                        if rec == "msg_g2c_result":
                            if m.get("error"):
                                break
                            await ws.send(json.dumps({"record": "msg_c2g_open_floodgates"}))
                            for rid in list(self.rooms):
                                await ws.send(
                                    json.dumps(
                                        {
                                            "record": "msg_c2g_subscribe",
                                            "queues": ["inv:/chat/chat-%s" % rid],
                                        }
                                    )
                                )
                        elif rec == "msg_g2c_send_message":
                            try:
                                obj = json.loads(b64d(m.get("message", "")))
                            except Exception:
                                continue
                            if isinstance(obj, dict) and obj.get("action"):
                                for o in obj.get("objects", []) or []:
                                    cid2 = str(o).split("-")[-1]
                                    self.on_event(
                                        {
                                            "type": "movement",
                                            "action": obj.get("action"),
                                            "room": (m.get("queue") or "").split("chat-")[-1],
                                            "cid": cid2,
                                        }
                                    )
            except Exception as e:
                self.connected = False
                self.on_event({"type": "imq_error", "message": str(e)})
                await asyncio.sleep(5)

    def start(self, loop, room_ids=None):
        import asyncio

        if room_ids:
            self.rooms.update(room_ids)
        self.task = loop.create_task(self._run(WS_URL))

    def watch(self, room_id):
        self.rooms.add(room_id)
