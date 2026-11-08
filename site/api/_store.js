const GH_API = "https://api.github.com";

function env(name) {
  const v = process.env[name];
  if (!v) throw new Error("missing env " + name);
  return v;
}

function auth(req) {
  const expected = process.env.DASHBOARD_TOKEN || "";
  const got = req.headers.authorization || "";
  return expected.length > 0 && got === "Bearer " + expected;
}

async function readState() {
  const id = env("GH_GIST_ID");
  const token = env("GH_TOKEN");
  const r = await fetch(`${GH_API}/gists/${id}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "imvu-radar-site",
    },
  });
  if (!r.ok) throw new Error("gist read " + r.status);
  const j = await r.json();
  const content = (j.files && j.files["state.json"] && j.files["state.json"].content) || "{}";
  try {
    return JSON.parse(content);
  } catch {
    return {};
  }
}

async function writeState(state) {
  const id = env("GH_GIST_ID");
  const token = env("GH_TOKEN");
  const r = await fetch(`${GH_API}/gists/${id}`, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "Content-Type": "application/json",
      "User-Agent": "imvu-radar-site",
    },
    body: JSON.stringify({
      files: { "state.json": { content: JSON.stringify(state) } },
    }),
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

export { readState, writeState, auth, body };
