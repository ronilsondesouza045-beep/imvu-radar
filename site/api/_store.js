const GH_API = "https://api.github.com";

function env(name) {
  const v = process.env[name];
  if (!v) throw new Error("missing env " + name);
  return v;
}

async function readGist() {
  const id = env("GH_GIST_ID");
  const token = env("GH_TOKEN");
  const r = await fetch(`${GH_API}/gists/${id}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "imvu-radar-site",
      "Cache-Control": "no-cache",
    },
    cache: "no-store",
  });
  if (!r.ok) throw new Error("gist read " + r.status);
  return r.json();
}

function parse(content, fallback) {
  if (!content) return fallback;
  try {
    return JSON.parse(content);
  } catch {
    return fallback;
  }
}

/**
 * Cada endpoint grava SO o proprio arquivo no gist.
 * Antes tudo era um state.json unico: o POST /api/status (a cada 10s)
 * podia reescrever o estado lido antes do comando e apagar o comando
 * que o site acabou de enviar. Arquivos separados acabam com essa corrida.
 */
async function readFile(name) {
  const j = await readGist();
  const files = j.files || {};
  if (files[name]) return parse(files[name].content, {});
  // migracao do formato antigo (state.json unico)
  const legacy = parse(files["state.json"] && files["state.json"].content, {});
  if (name === "status.json") return legacy.status || {};
  if (name === "command.json") return legacy.command || null;
  if (name === "config.json") {
    return { rooms: legacy.rooms || {}, version: legacy.version || null };
  }
  return {};
}

async function writeFile(name, obj) {
  const id = env("GH_GIST_ID");
  const token = env("GH_TOKEN");
  const content = JSON.stringify(obj === null ? {} : obj);
  const r = await fetch(`${GH_API}/gists/${id}`, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "Content-Type": "application/json",
      "User-Agent": "imvu-radar-site",
    },
    body: JSON.stringify({ files: { [name]: { content } } }),
  });
  if (!r.ok) throw new Error("gist write " + r.status);
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
