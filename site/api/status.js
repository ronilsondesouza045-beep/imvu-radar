import { readState, writeState, auth, body } from "./_store.js";

export default async function handler(req, res) {
  if (!auth(req)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  try {
    if (req.method === "GET") {
      const state = await readState();
      res.status(200).json(state.status || {});
      return;
    }
    if (req.method === "POST") {
      const b = body(req);
      const state = await readState();
      state.status = { ...b, updated: Date.now() };
      await writeState(state);
      res.status(200).json({ ok: true });
      return;
    }
    res.status(405).json({ error: "method" });
  } catch (e) {
    res.status(500).json({ error: String((e && e.message) || e) });
  }
}
