import { readFile, writeFile, body, noStore } from "./_store.js";

export default async function handler(req, res) {
  noStore(res);
  try {
    const state = await readFile("config.json");
    if (req.method === "GET") {
      res.status(200).json({
        rooms: (state && state.rooms) || {},
        version: (state && state.version) || null,
      });
      return;
    }
    if (req.method === "POST") {
      const b = body(req);
      const rooms = state.rooms || {};
      if (b.roomId) {
        const rid = String(b.roomId).trim();
        const name = String(b.name || "").trim();
        if (name) rooms[rid] = name;
        else delete rooms[rid];
      }
      if (b.version) state.version = b.version;
      state.rooms = rooms;
      await writeFile("config.json", state);
      res.status(200).json({ ok: true, rooms });
      return;
    }
    res.status(405).json({ error: "method" });
  } catch (e) {
    res.status(500).json({ error: String((e && e.message) || e) });
  }
}
