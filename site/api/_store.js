import { put, get } from "@vercel/blob";

const LEGACY_TTL = 300_000;
const legacy = { at: 0, data: null };

async function readBlob(name) {
  try {
    const r = await get(name, { access: "private", useCache: false });
    if (!r || !r.stream) return null;
    const text = await new Response(r.stream).text();
    if (!text) return null;
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function writeBlob(name, obj) {
  await put(name, JSON.stringify(obj === null ? {} : obj), {
    access: "private",
    addRandomSuffix: false,
    allowOverwrite: true,
    contentType: "application/json",
    cacheControlMaxAge: 60,
  });
}

/**
 * Migracao do formato antigo (gist), somente leitura e cacheada.
 * Depois da primeira leitura o valor vai pro Blob e o gist nao e mais tocado.
 */
async function readLegacy() {
  if (legacy.data && Date.now() - legacy.at < LEGACY_TTL) return legacy.data;
  try {
    const id = process.env.GH_GIST_ID;
    const token = process.env.GH_TOKEN;
    if (!id || !token) return null;
    const r = await fetch(`https://api.github.com/gists/${id}`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "imvu-radar-site",
      },
      cache: "no-store",
    });
    if (!r.ok) return null;
    const j = await r.json();
    const files = j.files || {};
    const out = {};
    if (files["state.json"]) {
      try {
        Object.assign(out, JSON.parse(files["state.json"].content));
      } catch {}
    }
    for (const n of ["status.json", "command.json", "config.json"]) {
      if (files[n]) {
        try {
          out[n] = JSON.parse(files[n].content);
        } catch {}
      }
    }
    legacy.data = out;
    legacy.at = Date.now();
    return out;
  } catch {
    return null;
  }
}

function fromLegacy(name, state) {
  if (name === "status.json") return state["status.json"] || state.status || {};
  if (name === "command.json") {
    return state["command.json"] !== undefined ? state["command.json"] : state.command || null;
  }
  if (name === "config.json") {
    if (state["config.json"]) return state["config.json"];
    return { rooms: state.rooms || {}, version: state.version || null };
  }
  return {};
}

async function readFile(name) {
  const v = await readBlob(name);
  if (v !== null) return v;
  const state = await readLegacy();
  if (!state) return name === "command.json" ? null : {};
  const fallback = fromLegacy(name, state);
  // migra na primeira leitura; se falhar, segue com o fallback em memoria
  try {
    await writeBlob(name, fallback);
  } catch {}
  return fallback;
}

async function writeFile(name, obj) {
  await writeBlob(name, obj);
}

function body(req) {
  if (!req.body) return {};
  if (typeof req.body === "string") {
    try {
      return JSON.parse(req.body);
    } catch {
      return {};
    }
  }
  return req.body;
}

function noStore(res) {
  res.setHeader("Cache-Control", "no-store, max-age=0");
}

export { readFile, writeFile, body, noStore };
