import { readState, writeState, body } from "./_store.js";

export default async function handler(req, res) {
  try {
    if (req.method === "GET") {
      const state = await readState();
      res.status(200).json(state.command || null);
      return;
    }
    if (req.method === "POST") {
      const b = body(req);
      const action = String(b.action || "");
      if (!["track", "stop", "where"].includes(action)) {
        res.status(400).json({ error: "action" });
        return;
      }
      const state = await readState();
      const command = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        action,
        nick: String(b.nick || ""),
        created: Date.now(),
      };
      state.command = command;
      await writeState(state);
      res.status(200).json({ ok: true, command });
      return;
    }
    res.status(405).json({ error: "method" });
  } catch (e) {
    res.status(500).json({ error: String((e && e.message) || e) });
  }
}
