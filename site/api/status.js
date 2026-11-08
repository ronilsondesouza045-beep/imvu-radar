import { readFile, writeFile, body, noStore } from "./_store.js";

export default async function handler(req, res) {
  noStore(res);
  try {
    if (req.method === "GET") {
      const state = await readFile("status.json");
      res.status(200).json(state || {});
      return;
    }
    if (req.method === "POST") {
      const b = body(req);
      const state = { ...b, updated: Date.now() };
      await writeFile("status.json", state);
      res.status(200).json({ ok: true });
      return;
    }
    res.status(405).json({ error: "method" });
  } catch (e) {
    res.status(500).json({ error: String((e && e.message) || e) });
  }
}
